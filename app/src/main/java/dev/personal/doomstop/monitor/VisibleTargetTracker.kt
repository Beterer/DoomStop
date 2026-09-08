package dev.personal.doomstop.monitor

import dev.personal.doomstop.domain.VisibilityTransition

/** The usage-event kinds this app reacts to. Everything else is discarded on read. */
enum class TrackedEventType {
    ACTIVITY_RESUMED,
    ACTIVITY_PAUSED,
    ACTIVITY_STOPPED,
    SCREEN_INTERACTIVE,
    SCREEN_NON_INTERACTIVE,
    KEYGUARD_SHOWN,
    KEYGUARD_HIDDEN,
    DEVICE_SHUTDOWN,
    DEVICE_STARTUP,
}

/**
 * A usage event reduced to the fields this app needs. Deliberately carries no window
 * content, no text and no URLs -- only which activity of which package changed lifecycle
 * state, and when.
 */
data class TrackedEvent(
    val timestampWallMs: Long,
    val packageName: String,
    val className: String?,
    val type: TrackedEventType,
)

/** Everything the diagnostics screen needs in order to explain the current decision. */
data class TrackerSnapshot(
    val visible: Boolean,
    val screenInteractive: Boolean,
    val keyguardShown: Boolean,
    val activities: List<ActivitySnapshot>,
) {
    data class ActivitySnapshot(
        val packageName: String,
        val className: String,
        val state: String,
        val ageMs: Long,
    )
}

/**
 * Decides whether at least one target application is visible, from activity lifecycle
 * events plus screen and keyguard state.
 *
 * What this is NOT: ACTIVITY_RESUMED/PAUSED/STOPPED are lifecycle signals, not a
 * visible-window API, so this class does not claim to know exactly what is on screen.
 *
 * Activity identity: the plan suggested keying on activity instance IDs, but
 * `UsageEvents.Event.getInstanceId()` is not public API on this platform (verified
 * against android.jar for API 37), so activities are keyed by package + class name
 * instead. The consequence is recorded honestly below.
 *
 * Visibility model:
 *  - RESUMED counts. On Android 10+ split-screen uses multi-resume, so both visible apps
 *    are resumed and split-screen is covered directly.
 *  - PAUSED-but-not-STOPPED also counts, because that is the state a picture-in-picture
 *    activity sits in while it is plainly still on screen...
 *  - ...but only for [pausedGraceMs]. Leaving an app produces PAUSED then STOPPED a
 *    moment later, so the ordinary cost of counting PAUSED is one STOPPED-delivery
 *    latency, which is charged rather than lost. The grace bounds the pathological case
 *    where STOPPED never arrives (killed process) instead of charging indefinitely.
 *  - A STOPPED is honoured only when the tracked state for that key is PAUSED. The
 *    platform guarantees onPause() runs before onStop(), so a STOPPED arriving while the
 *    key is RESUMED must belong to an OLDER instance of the same activity class -- the
 *    one case where the missing instance ID would otherwise make the app go blind
 *    mid-session.
 *
 * Screen gating: nothing is charged while the screen is off or the keyguard is shown,
 * which is what keeps background audio and a pocketed phone free of charge.
 *
 * Pure Kotlin, no Android imports, so the whole model is unit-testable.
 */
class VisibleTargetTracker(
    private val targets: Set<String>,
    private val pausedGraceMs: Long = DEFAULT_PAUSED_GRACE_MS,
) {

    private enum class ActivityState { RESUMED, PAUSED }

    private class TrackedActivity(
        val packageName: String,
        val className: String,
        var state: ActivityState,
        var sinceMs: Long,
        var graceExpired: Boolean = false,
    )

    private val activities = LinkedHashMap<String, TrackedActivity>()

    private var screenInteractive = true
    private var keyguardShown = false
    private var logicalNowMs = Long.MIN_VALUE
    private var lastVisible = false

    /** True when at least one target is visible and the screen is unlocked, as of the last advance. */
    val isVisible: Boolean get() = computeVisible()

    /**
     * Feed events (in any order) and advance the tracker's notion of "now" to [nowWallMs].
     *
     * Returns every point at which overall visibility flipped, which is what the accounting
     * core integrates over. Redundant events collapse, so replaying an overlapping query
     * window produces no extra transitions and no extra charge.
     */
    fun apply(events: List<TrackedEvent>, nowWallMs: Long): List<VisibilityTransition> {
        val out = mutableListOf<VisibilityTransition>()
        if (logicalNowMs == Long.MIN_VALUE) {
            logicalNowMs = events.minOfOrNull { it.timestampWallMs } ?: nowWallMs
        }
        for (event in events.sortedBy { it.timestampWallMs }) {
            advanceTo(event.timestampWallMs, out)
            applyEvent(event)
            emitIfChanged(event.timestampWallMs, out)
        }
        advanceTo(nowWallMs, out)
        return out
    }

    /**
     * Correct screen/keyguard state from the live system, which is authoritative and does
     * not depend on the event stream having delivered anything yet.
     */
    fun observeScreenState(
        interactive: Boolean,
        keyguardLocked: Boolean,
        atWallMs: Long,
    ): List<VisibilityTransition> {
        val out = mutableListOf<VisibilityTransition>()
        if (logicalNowMs == Long.MIN_VALUE) logicalNowMs = atWallMs
        advanceTo(atWallMs, out)
        screenInteractive = interactive
        keyguardShown = keyguardLocked
        emitIfChanged(atWallMs, out)
        return out
    }

    /** Drop all activity state, e.g. once a reboot has been detected. */
    fun reset(atWallMs: Long) {
        activities.clear()
        logicalNowMs = atWallMs
        lastVisible = false
    }

    fun snapshot(): TrackerSnapshot = TrackerSnapshot(
        visible = computeVisible(),
        screenInteractive = screenInteractive,
        keyguardShown = keyguardShown,
        activities = activities.values.map {
            TrackerSnapshot.ActivitySnapshot(
                packageName = it.packageName,
                className = it.className,
                state = when {
                    it.state == ActivityState.RESUMED -> "resumed"
                    it.graceExpired -> "paused (grace expired)"
                    else -> "paused"
                },
                ageMs = (logicalNowMs - it.sinceMs).coerceAtLeast(0L),
            )
        },
    )

    // -- internals ---------------------------------------------------------------------

    private fun keyOf(event: TrackedEvent): String = "${event.packageName}@${event.className.orEmpty()}"

    private fun applyEvent(event: TrackedEvent) {
        when (event.type) {
            TrackedEventType.SCREEN_INTERACTIVE -> screenInteractive = true
            TrackedEventType.SCREEN_NON_INTERACTIVE -> screenInteractive = false
            TrackedEventType.KEYGUARD_SHOWN -> keyguardShown = true
            TrackedEventType.KEYGUARD_HIDDEN -> keyguardShown = false
            // Nothing is on screen across a shutdown or a fresh start.
            TrackedEventType.DEVICE_SHUTDOWN, TrackedEventType.DEVICE_STARTUP -> activities.clear()

            TrackedEventType.ACTIVITY_RESUMED -> {
                if (event.packageName !in targets) return
                val existing = activities[keyOf(event)]
                if (existing != null) {
                    existing.state = ActivityState.RESUMED
                    existing.sinceMs = event.timestampWallMs
                    existing.graceExpired = false
                } else {
                    evictIfFull()
                    activities[keyOf(event)] = TrackedActivity(
                        packageName = event.packageName,
                        className = event.className.orEmpty(),
                        state = ActivityState.RESUMED,
                        sinceMs = event.timestampWallMs,
                    )
                }
            }

            TrackedEventType.ACTIVITY_PAUSED -> {
                val existing = activities[keyOf(event)] ?: return
                if (existing.state == ActivityState.PAUSED) return // idempotent replay
                existing.state = ActivityState.PAUSED
                existing.sinceMs = event.timestampWallMs
                existing.graceExpired = false
            }

            TrackedEventType.ACTIVITY_STOPPED -> {
                val existing = activities[keyOf(event)] ?: return
                // onPause() always precedes onStop(). A STOPPED while this key is RESUMED
                // therefore belongs to an older instance of the same class, and dropping
                // the key here would blind the tracker for the rest of the session.
                if (existing.state == ActivityState.RESUMED) return
                activities.remove(keyOf(event))
            }
        }
    }

    /**
     * Move logical time forward, expiring paused-activity grace periods on the way, so that
     * visibility can drop between events rather than only when one happens to arrive.
     */
    private fun advanceTo(targetMs: Long, out: MutableList<VisibilityTransition>) {
        if (targetMs < logicalNowMs) return // out-of-order or duplicate; already accounted
        while (true) {
            val next = nextGraceExpiryMs() ?: break
            if (next > targetMs) break
            logicalNowMs = next
            for (activity in activities.values) {
                if (activity.state == ActivityState.PAUSED &&
                    !activity.graceExpired &&
                    activity.sinceMs + pausedGraceMs <= next
                ) {
                    activity.graceExpired = true
                }
            }
            emitIfChanged(next, out)
        }
        logicalNowMs = targetMs
    }

    private fun nextGraceExpiryMs(): Long? = activities.values
        .filter { it.state == ActivityState.PAUSED && !it.graceExpired }
        .minOfOrNull { it.sinceMs + pausedGraceMs }

    private fun computeVisible(): Boolean {
        if (!screenInteractive || keyguardShown) return false
        return activities.values.any {
            it.packageName in targets && (it.state == ActivityState.RESUMED || !it.graceExpired)
        }
    }

    private fun emitIfChanged(atWallMs: Long, out: MutableList<VisibilityTransition>) {
        val visible = computeVisible()
        if (visible != lastVisible) {
            lastVisible = visible
            out += VisibilityTransition(atWallMs, visible)
        }
    }

    private fun evictIfFull() {
        while (activities.size >= MAX_TRACKED_ACTIVITIES) {
            val oldest = activities.keys.firstOrNull() ?: return
            activities.remove(oldest)
        }
    }

    companion object {
        /**
         * Default grace for a PAUSED-but-never-STOPPED activity: long enough for a short
         * picture-in-picture session to keep counting, short enough that a lost STOPPED
         * event cannot quietly drain the allowance. Settled by measurement on hardware.
         */
        const val DEFAULT_PAUSED_GRACE_MS = 90_000L

        /** Guards against unbounded growth if STOPPED events are systematically missing. */
        const val MAX_TRACKED_ACTIVITIES = 64
    }
}
