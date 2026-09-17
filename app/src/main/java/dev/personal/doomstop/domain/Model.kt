package dev.personal.doomstop.domain

/**
 * Pure, Android-free model shared by the accounting core, the persistence layer and the UI.
 */

/** Configuration owned by the PIN holder. */
data class LimiterSettings(
    val dailyAllowanceMs: Long,
    val extensionMs: Long,
    /** Fixed accounting timezone ID, captured at setup (see [DayBoundary]). */
    val zoneId: String,
) {
    companion object {
        /**
         * PROPOSED defaults, not previously confirmed decisions. The plan asks for them to
         * be labelled as such in the UI and to be editable by the PIN holder.
         */
        const val DEFAULT_DAILY_ALLOWANCE_MS = 30L * 60L * 1000L
        const val DEFAULT_EXTENSION_MS = 10L * 60L * 1000L
    }
}

/**
 * One accounting day's balance. Unused time carries into the next day without a cap (see
 * [BudgetEngine.carryInto]); extra time does not, because it is counted as spent last and
 * lives on the day's own row.
 */
data class DayBudget(
    val dayId: DayId,
    val baseAllowanceMs: Long,
    val chargedMs: Long,
    val extraGrantedMs: Long,
    /** Unused time carried in from the previous day. */
    val carriedInMs: Long = 0L,
) {
    val totalAllowanceMs: Long get() = baseAllowanceMs + carriedInMs + extraGrantedMs
    val remainingMs: Long get() = (totalAllowanceMs - chargedMs).coerceAtLeast(0L)
    val isExhausted: Boolean get() = remainingMs == 0L

    /**
     * What this day passes on to the next. Base and carried time are spent before extra time,
     * so anything left of an extension is exactly what expires, and an extension granted early
     * can never be converted into carried time.
     */
    val unusedToCarryMs: Long get() = (baseAllowanceMs + carriedInMs - chargedMs).coerceAtLeast(0L)
}

/** Whether the accounting core believes its own history is complete. */
enum class CheckpointState {
    /** Every interval since the last checkpoint was reconciled. */
    CLEAN,

    /**
     * A gap could not be reconstructed. Targets stay suspended and the UI shows a
     * recovery state; the PIN holder resolves it. The app never silently awards a fresh
     * allowance or invents a charge to paper over this.
     */
    UNCERTAIN,
}

/**
 * A LATCHED demand for authorized recovery.
 *
 * The distinction this type exists to enforce: whether the usage source is readable RIGHT
 * NOW is a health question that comes and goes on its own, but whether the recorded history
 * is complete is a fact about the past that no later successful poll can undo. Once an
 * interval has been lost, this survives every subsequent tick, process restart and reboot
 * until [dev.personal.doomstop.core.LimiterCoordinator.acknowledgeRecovery] clears it behind
 * the PIN.
 *
 * The reason and the wall-clock range are kept so the PIN holder is told what they are
 * acknowledging rather than being asked to approve an anonymous error state.
 */
data class RecoveryRequest(
    val reason: String,
    val fromWallMs: Long,
    val toWallMs: Long,
    val detectedAtWallMs: Long,
) {
    val gapMs: Long get() = (toWallMs - fromWallMs).coerceAtLeast(0L)
}

/**
 * Durable accounting anchor, positioned at the SETTLED boundary rather than at "now".
 *
 * [lastElapsedMs] is [android.os.SystemClock.elapsedRealtime], valid only within the boot
 * identified by [bootId] -- monotonic timestamps from different boots are never compared.
 * [lastWallMs] anchors that monotonic reading to accepted wall time so usage-event
 * timestamps (which are wall time) can be placed on the same axis.
 *
 * "Accepted" is load-bearing: [lastWallMs] advances by measured monotonic duration, and the
 * system clock is only adopted when the two agree. A wall clock that jumps therefore cannot
 * drag the accounting day with it (see [BudgetEngine.resolveClock]).
 */
data class Checkpoint(
    val bootId: String,
    /** Monotonic reading at the previous pass. */
    val lastElapsedMs: Long,
    /** Accepted wall time at the previous pass. */
    val lastWallMs: Long,
    /**
     * The committed accounting boundary, which trails [lastWallMs] by
     * [BudgetEngine.SETTLE_LAG_MS]. Everything before it has been charged exactly once and
     * is never revisited; everything after it is recomputed on every pass so that late
     * usage events still land in a window nothing has been written for.
     */
    val settledWallMs: Long,
    /** Whether a target was visible at [settledWallMs]. */
    val targetVisible: Boolean,
    /** Wall-clock cursor into the usage-event stream; events at or before this are captured. */
    val usageCursorWallMs: Long,
    /** Accumulated accepted difference between system and monotonic time, reset each boot. */
    val acceptedDriftMs: Long,
    /** Latched; cleared only by authorized recovery. */
    val recovery: RecoveryRequest?,
    /**
     * Opaque serialized visibility-observer state as of [settledWallMs], written in the same
     * transaction as the rest of this row. The accounting core never parses it; it exists so
     * that a restarted process resumes observing from a known state instead of from an empty
     * one, which is what stops a long open session from becoming free after a restart.
     */
    val trackerState: String?,
) {
    /** Derived, never stored twice: uncertainty IS an outstanding recovery request. */
    val state: CheckpointState
        get() = if (recovery == null) CheckpointState.CLEAN else CheckpointState.UNCERTAIN

    companion object {
        fun initial(bootId: String, elapsedMs: Long, wallMs: Long) = Checkpoint(
            bootId = bootId,
            lastElapsedMs = elapsedMs,
            lastWallMs = wallMs,
            settledWallMs = wallMs,
            targetVisible = false,
            usageCursorWallMs = wallMs,
            acceptedDriftMs = 0L,
            recovery = null,
            trackerState = null,
        )
    }
}

/** The raw clock reading and source availability for one pass, taken by the caller. */
data class TickInput(
    val bootId: String,
    val elapsedMs: Long,
    /** The system wall clock AS REPORTED. Not assumed to be trustworthy. */
    val reportedWallMs: Long,
    /**
     * Distinguishes "the usage-event query returned nothing" from "the query could not run":
     * the first is ordinary idleness, the second means the app has gone blind.
     */
    val eventsAvailable: Boolean,
)

/**
 * The part of a pass that is being made permanent.
 *
 * Accounting deliberately lags "now" by [BudgetEngine.SETTLE_LAG_MS] so that usage events
 * which arrive late still land inside a window that has not been committed yet. Everything
 * after [endWallMs] is recomputed from scratch on every pass and only estimated, never
 * written.
 */
data class SettledWindow(
    val endWallMs: Long,
    /** Visibility at [endWallMs], from replaying the observer to exactly that instant. */
    val visibleAtEnd: Boolean,
    /** Observer state at [endWallMs], stored with the checkpoint. */
    val trackerState: String?,
    /** How far the usage-event stream has been captured, in reported wall time. */
    val usageCursorWallMs: Long,
)

/** What the clocks say once continuity has actually been checked. */
data class ClockResolution(
    /** The instant the app is willing to treat as now. May lag a jumping system clock. */
    val acceptedNowWallMs: Long,
    /** Real time that passed since the previous checkpoint. */
    val durationMs: Long,
    val sameBoot: Boolean,
    val acceptedDriftMs: Long,
    val anomalies: List<Anomaly>,
    /** Non-null when the interval cannot be reconstructed and recovery must be latched. */
    val refusal: RecoveryRequest?,
)

/** Something happened that the accounting core cannot treat as ordinary progress. */
sealed interface Anomaly {
    /** The device rebooted since the last checkpoint; monotonic time restarted. */
    data class BootChanged(val previousBootId: String, val currentBootId: String) : Anomaly

    /** Wall clock and monotonic clock disagreed by more than tolerance. */
    data class ClockJump(val deltaMs: Long) : Anomaly

    /** Monotonic time went backwards, which should be impossible within a boot. */
    data class MonotonicRegression(val deltaMs: Long) : Anomaly

    /** A gap too large or too opaque to reconstruct from usage events. */
    data class UnreconciledGap(val gapMs: Long, val reason: String) : Anomaly
}

/** A half-open wall-clock interval during which at least one target was visible. */
data class VisibleSpan(val startWallMs: Long, val endWallMs: Long) {
    init {
        require(endWallMs >= startWallMs) { "VisibleSpan must not run backwards" }
    }

    val durationMs: Long get() = endWallMs - startWallMs
}

/** A change in whether any target is visible, at a wall-clock instant. */
data class VisibilityTransition(val atWallMs: Long, val visible: Boolean)

/** Everything one accounting pass decided. */
data class Accounting(
    /** Time to add to each day's charged total. Empty when nothing was consumed. */
    val slices: List<DaySlice>,
    val checkpoint: Checkpoint,
    val anomalies: List<Anomaly>,
) {
    val totalChargedMs: Long get() = slices.sumOf { it.durationMs }
}

/** Why targets are or are not suspended right now. */
enum class EnforcementReason {
    /** Allowance (including any granted extension) remains. */
    ALLOWANCE_AVAILABLE,

    /** The shared daily allowance is used up. */
    ALLOWANCE_EXHAUSTED,

    /** Monitoring cannot be trusted, so access is withdrawn rather than assumed safe. */
    MONITOR_UNHEALTHY,

    /** History has a hole; the PIN holder must resolve it. */
    RECOVERY_REQUIRED,

    /** Authorized maintenance is in progress; this app is deliberately not enforcing. */
    MAINTENANCE,
}

data class EnforcementDecision(
    val suspendTargets: Boolean,
    val reason: EnforcementReason,
)

/** Capabilities the app actually has right now, as opposed to what it hopes to have. */
data class MonitorHealth(
    val deviceOwner: Boolean,
    val usageAccessGranted: Boolean,
    val serviceRunning: Boolean,
    val notificationsEnabled: Boolean,
) {
    /** Enforcement is only meaningful when the app both sees usage and can act on it. */
    val canEnforce: Boolean get() = deviceOwner
    val isHealthy: Boolean get() = deviceOwner && usageAccessGranted && serviceRunning
}
