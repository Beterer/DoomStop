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
 * A usage event, reduced to the fields this app needs. Deliberately carries no window
 * content, no text and no URLs -- only which activity instance of which package changed
 * lifecycle state, and when.
 */
data class TrackedEvent(
    val timestampWallMs: Long,
    val packageName: String,
    val className: String?,
    /** UsageEvents.Event.getInstanceId(); 0 when the platform did not supply one. */
    val instanceId: Int,
    val type: TrackedEventType,
)

/** Everything the diagnostics screen needs to explain the current decision. */
data class TrackerSnapshot(
    val visible: Boolean,
    val screenInteractive: Boolean,
    val keyguardShown: Boolean,
    val instances: List<InstanceSnapshot>,
) {
    data class InstanceSnapshot(
        val packageName: String,
        val state: String,
        val ageMs: Long,
    )
}

/**
 * Decides whether at least one target application is visible, from activity lifecycle
 * events plus screen and keyguard state.
 *
 * What this is NOT: `ACTIVITY_RESUMED`/`ACTIVITY_PAUSED` are lifecycle signals, not a
 * visible-window API, so this class does not claim to know exactly what is on screen. It
 * tracks activity INSTANCES (via instanceId) rather than "the last package that resumed",
 * because the latter is wrong in every multi-window case.
 *
 * Visibility model:
 *  - RESUMED counts. On Android 10+ split-screen uses multi-resume, so both visible apps
 *    are resumed and this covers split-screen directly.
 *  - PAUSED but not yet STOPPED also counts, because that is the state a
 *    picture-in-picture activity sits in while it is plainly still on screen.
 *  - ...but only for [pausedGraceMs]. Leaving an app produces PAUSED then STOPPED a short
 *    moment later, so the ordinary cost of counting PAUSED is one STOPPED-delivery
 *    latency, which is charged rather than lost. The grace bounds the pathological case
 *    where STOPPED never arrives at all (killed process) to a known maximum instead of
 *    charging forever. The measured latency and the chosen grace are recorded in the test
 *    report; this is the tracker parameter Gate D exists to settle.
 *
 * Screen gating: nothing is charged while the screen is off or the keyguard is shown,
 * which is what keeps background audio and a pocketed phone free.
 *
 * Pure Kotlin, no Android imports, so the whole model is unit-testable.
 */
class VisibleTargetTracker(
    private val targets: Set<String>,
    private val pausedGraceMs: Long = DEFAULT_PAUSED_GRACE_MS,
) {

    private enum class ActivityState { RESUMED, PAUSED }

    private class Instance(
        val packageName: String,
        var state: ActivityState,
        var sinceMs: Long,
        var graceExpired: Boolean = false,
    )

    private val instances = LinkedHashMap<String, Instance>()

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
     * core integrates over. Redundant events collapse: replaying the same event twice
     * produces no extra transition, so overlapping query windows are harmless.
     */
    fun apply(events: List<TrackedEvent>, nowWallMs: Long): List<VisibilityTransition> {
        val out = mutableListOf<VisibilityTransition>()
        if (logicalNowMs == Long.MIN_VALUE) logicalNowMs = events.minOfOrNull { it.timestampWallMs } ?: nowWallMs

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
    fun observeScreenState(interactive: Boolean, keyguardLocked: Boolean, atWallMs: Long): List<VisibilityTransition> {
        val out = mutableListOf<VisibilityTransition>()
        advanceTo(atWallMs, out)
        screenInteractive = interactive
        keyguardShown = keyguardLocked
        emitIfChanged(atWallMs, out)
        return out
    }

    /** Drop all activity state, e.g. after a reboot is detected. Screen state is re-observed. */
    fun reset(atWallMs: Long) {
        instances.clear()
        logicalNowMs = atWallMs
        lastVisible = false
    }

    fun snapshot(): TrackerSnapshot = TrackerSnapshot(
        visible = computeVisible(),
        screenInteractive = screenInteractive,
        keyguardShown = keyguardShown,
        instances = instances.values.map {
            TrackerSnapshot.InstanceSnapshot(
                packageName = it.packageName,
                state = if (it.state == ActivityState.RESUMED) {
                    "resumed"
                } else if (it.graceExpired) {
                    "paused (grace expired)"
                } else {
                    "paused"
                },
                ageMs = (logicalNowMs - it.sinceMs).coerceAtLeast(0L),
            )
        },
    )

    // -- internals ---------------------------------------------------------------------

    /**
     * Identity of one activity instance. instanceId is stable per instance since API 29 and
     * is what distinguishes two activities of the same package; class name is only a
     * fallback for events that arrive without one.
     */
    private fun keyOf(event: TrackedEvent): String =
        if (event.instanceId != 0) "${event.packageName}#${event.instanceId}"
        else "${event.packageName}@${event.className.orEmpty()}"

    private fun applyEvent(event: TrackedEvent) {
        when (event.type) {
            TrackedEventType.SCREEN_INTERACTIVE -> screenInteractive = true
            TrackedEventType.SCREEN_NON_INTERACTIVE -> screenInteractive = false
            TrackedEventType.KEYGUARD_SHOWN -> keyguardShown = true
            TrackedEventType.KEYGUARD_HIDDEN -> keyguardShown = false
            // Nothing is on screen across a shutdown or a fresh start.
            TrackedEventType.DEVICE_SHUTDOWN, TrackedEventType.DEVICE_STARTUP -> instances.clear()

            TrackedEventType.ACTIVITY_RESUMED -> {
                if (event.packageName !in targets) return
                val key = keyOf(event)
                val existing = instances[key]
                if (existing != null) {
                    existing.state = ActivityState.RESUMED
                    existing.sinceMs = event.timestampWallMs
                    existing.graceExpired = false
                } else {
                    evictIfFull()
                    instances[key] = Instance(event.packageName, ActivityState.RESUMED, event.timestampWallMs)
                }
            }

            TrackedEventType.ACTIVITY_PAUSED -> {
                val existing = instances[keyOf(event)] ?: return
                if (existing.state == ActivityState.PAUSED) return // idempotent
                existing.state = ActivityState.PAUSED
                existing.sinceMs = event.timestampWallMs
                existing.graceExpired = false
            }

            TrackedEventType.ACTIVITY_STOPPED -> instances.remove(keyOf(event))
        }
    }

    /**
     * Move logical time forward, expiring paused-instance grace periods on the way so that
     * visibility can drop between events rather than only when one happens to arrive.
     */
    private fun advanceTo(targetMs: Long, out: MutableList<VisibilityTransition>) {
        if (targetMs < logicalNowMs) {
            // Out-of-order or duplicated event; state is already at or past this point.
            return
        }
        while (true) {
            val next = nextGraceExpiryMs() ?: break
            if (next > targetMs) break
            logicalNowMs = next
            instances.values.forEach {
                if (it.state == ActivityState.PAUSED && !it.graceExpired && it.sinceMs + pausedGraceMs <= next) {
                    it.graceExpired = true
                }
            }
            emitIfChanged(next, out)
        }
        logicalNowMs = targetMs
    }

    private fun nextGraceExpiryMs(): Long? = instances.values
        .filter { it.state == ActivityState.PAUSED && !it.graceExpired }
        .minOfOrNull { it.sinceMs + pausedGraceMs }

    private fun computeVisible(): Boolean {
        if (!screenInteractive || keyguardShown) return false
        return instances.values.any {
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
        while (instances.size >= MAX_TRACKED_INSTANCES) {
            val oldest = instances.keys.firstOrNull() ?: return
            instances.remove(oldest)
        }
    }

    companion object {
        /**
         * Default grace for a PAUSED-but-never-STOPPED activity. Long enough for a short
         * picture-in-picture session to keep counting, short enough that a lost STOPPED
         * event cannot quietly drain the allowance.
         */
        const val DEFAULT_PAUSED_GRACE_MS = 90_000L

        /** Guards against unbounded growth if STOPPED events are systematically missing. */
        const val MAX_TRACKED_INSTANCES = 64
    }
}
