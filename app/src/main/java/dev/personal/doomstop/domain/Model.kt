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
 * One accounting day's balance. There is no rollover of unused time, and extra time
 * expires with the day because it lives on the day's own row.
 */
data class DayBudget(
    val dayId: DayId,
    val baseAllowanceMs: Long,
    val chargedMs: Long,
    val extraGrantedMs: Long,
) {
    val totalAllowanceMs: Long get() = baseAllowanceMs + extraGrantedMs
    val remainingMs: Long get() = (totalAllowanceMs - chargedMs).coerceAtLeast(0L)
    val isExhausted: Boolean get() = remainingMs == 0L
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
 * Durable accounting anchor.
 *
 * [lastElapsedMs] is [android.os.SystemClock.elapsedRealtime], valid only within the boot
 * identified by [bootId] -- monotonic timestamps from different boots are never compared.
 * [lastWallMs] anchors that monotonic reading to wall time so usage-event timestamps
 * (which are wall time) can be placed on the same axis.
 */
data class Checkpoint(
    val bootId: String,
    val lastElapsedMs: Long,
    val lastWallMs: Long,
    /** Whether a target was visible at the moment of this checkpoint. */
    val targetVisible: Boolean,
    /** Wall-clock cursor into the usage-event stream; events at or before this are consumed. */
    val usageCursorWallMs: Long,
    val state: CheckpointState,
) {
    companion object {
        fun initial(bootId: String, elapsedMs: Long, wallMs: Long) = Checkpoint(
            bootId = bootId,
            lastElapsedMs = elapsedMs,
            lastWallMs = wallMs,
            targetVisible = false,
            usageCursorWallMs = wallMs,
            state = CheckpointState.CLEAN,
        )
    }
}

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
