package dev.personal.doomstop.domain

import kotlin.math.absoluteValue

/**
 * One observation of the world, taken at a single instant by the caller.
 *
 * [eventsAvailable] distinguishes "the usage-event query returned nothing" from "the
 * usage-event query could not run at all"; the plan requires those two to be handled
 * differently, because only the second one means the app has gone blind.
 */
data class TickInput(
    val bootId: String,
    val elapsedMs: Long,
    val wallMs: Long,
    /** Authoritative visibility at this instant (tracker state, gated by screen/keyguard). */
    val visibleNow: Boolean,
    /** New usage-event cursor after reading; carried into the next checkpoint. */
    val usageCursorWallMs: Long,
    val eventsAvailable: Boolean,
)

/**
 * The accounting core. Pure Kotlin with no Android and no ambient clock: every instant it
 * uses arrives in [TickInput], which is what lets the unit tests advance time by years
 * without sleeping.
 *
 * Counting rule (plan section 6): charge real elapsed time once whenever at least one
 * target is visible and the screen is unlocked. Two visible targets still consume one
 * second per second, because the input is a single boolean, not a per-app tally.
 */
object BudgetEngine {

    /**
     * Wall clock and monotonic clock are allowed to disagree by this much without comment.
     * Ordinary NTP corrections live well inside it.
     */
    const val CLOCK_TOLERANCE_MS = 5_000L

    /** Beyond this, a wall-clock jump is reported to the PIN holder as an anomaly. */
    const val CLOCK_ANOMALY_MS = 15L * 60_000L

    /**
     * Usage events are not kept forever. A gap wider than this cannot be reconstructed,
     * so it is refused rather than guessed at.
     */
    const val MAX_REPLAY_MS = 7L * 24L * 60L * 60L * 1000L

    /**
     * How long visibility carried over from the previous checkpoint may be believed when
     * no event contradicts it. Live ticks are ~1 s apart so this never binds in normal
     * operation; it exists so that a silent multi-hour gap cannot be charged wholesale on
     * the strength of a stale "Instagram was open" flag.
     */
    const val MAX_ASSUMED_CONTINUOUS_VISIBILITY_MS = 120_000L

    /**
     * Reconcile everything that happened between [previous] and [tick] exactly once.
     *
     * The window's DURATION comes from the monotonic clock (so changing the wall clock can
     * neither create nor destroy allowance); the window's POSITION on the wall-clock axis
     * comes from the previous anchor, which is what lets usage-event timestamps and day
     * boundaries be applied to it.
     */
    fun account(
        previous: Checkpoint,
        tick: TickInput,
        transitions: List<VisibilityTransition>,
        boundary: DayBoundary,
    ): Accounting {
        val anomalies = mutableListOf<Anomaly>()
        val sameBoot = previous.bootId == tick.bootId

        // Blind: the usage-event source is unavailable, so nothing about this window can be
        // reconstructed. Charge nothing and say so, rather than trusting a stale flag.
        if (!tick.eventsAvailable) {
            anomalies += Anomaly.UnreconciledGap(
                gapMs = (tick.wallMs - previous.lastWallMs).coerceAtLeast(0L),
                reason = "usage events unavailable",
            )
            return Accounting(emptyList(), reanchor(previous, tick, CheckpointState.UNCERTAIN), anomalies)
        }

        var initiallyVisible = previous.targetVisible
        val durationMs: Long

        if (sameBoot) {
            val delta = tick.elapsedMs - previous.lastElapsedMs
            if (delta < 0) {
                // Monotonic time cannot regress within a boot. Something is wrong enough
                // that charging would be a guess.
                anomalies += Anomaly.MonotonicRegression(delta)
                return Accounting(emptyList(), reanchor(previous, tick, CheckpointState.UNCERTAIN), anomalies)
            }
            if (delta > MAX_REPLAY_MS) {
                anomalies += Anomaly.UnreconciledGap(delta, "gap exceeds usage-event retention")
                return Accounting(emptyList(), reanchor(previous, tick, CheckpointState.UNCERTAIN), anomalies)
            }
            durationMs = delta

            val skew = (tick.wallMs - previous.lastWallMs) - delta
            if (skew.absoluteValue > CLOCK_TOLERANCE_MS) anomalies += Anomaly.ClockJump(skew)
        } else {
            // A reboot restarts elapsedRealtime, so there is no monotonic reference across
            // it and the wall clock is all that is left.
            anomalies += Anomaly.BootChanged(previous.bootId, tick.bootId)
            val wallDelta = tick.wallMs - previous.lastWallMs
            when {
                wallDelta < 0 -> {
                    anomalies += Anomaly.UnreconciledGap(wallDelta, "clock moved backwards across a reboot")
                    return Accounting(emptyList(), reanchor(previous, tick, CheckpointState.UNCERTAIN), anomalies)
                }

                wallDelta > MAX_REPLAY_MS -> {
                    anomalies += Anomaly.UnreconciledGap(wallDelta, "gap exceeds usage-event retention")
                    return Accounting(emptyList(), reanchor(previous, tick, CheckpointState.UNCERTAIN), anomalies)
                }

                else -> durationMs = wallDelta
            }
            // Nothing is visible while the device is off or rebooting. Any real visibility
            // after boot arrives as an ACTIVITY_RESUMED transition, so starting the window
            // "not visible" costs at most the sub-second sliver before shutdown and never
            // charges the powered-off period.
            initiallyVisible = false
        }

        val slices = integrateVisible(
            startWallMs = previous.lastWallMs,
            durationMs = durationMs,
            initiallyVisible = initiallyVisible,
            transitions = transitions,
            boundary = boundary,
            onLeadingClamp = { clampedMs ->
                anomalies += Anomaly.UnreconciledGap(
                    gapMs = clampedMs,
                    reason = "no usage events to confirm continued visibility across a gap",
                )
            },
        )

        return Accounting(
            slices = slices,
            checkpoint = reanchor(previous, tick, CheckpointState.CLEAN),
            anomalies = anomalies,
        )
    }

    private fun reanchor(previous: Checkpoint, tick: TickInput, state: CheckpointState) = previous.copy(
        bootId = tick.bootId,
        lastElapsedMs = tick.elapsedMs,
        lastWallMs = tick.wallMs,
        targetVisible = tick.visibleNow,
        usageCursorWallMs = tick.usageCursorWallMs,
        state = state,
    )

    /**
     * Integrate "at least one target visible" over the window and split the result across
     * accounting days. Transitions outside the window are ignored; duplicated or redundant
     * transitions collapse, so a missed callback costs nothing and a repeated one adds
     * nothing -- intervals are reconciled, not callbacks counted.
     */
    private fun integrateVisible(
        startWallMs: Long,
        durationMs: Long,
        initiallyVisible: Boolean,
        transitions: List<VisibilityTransition>,
        boundary: DayBoundary,
        onLeadingClamp: (Long) -> Unit,
    ): List<DaySlice> {
        if (durationMs <= 0) return emptyList()
        val endWallMs = startWallMs + durationMs

        val ordered = transitions
            .filter { it.atWallMs in startWallMs..endWallMs }
            .sortedBy { it.atWallMs }

        val totals = LinkedHashMap<DayId, Long>()
        var cursor = startWallMs
        var visible = initiallyVisible
        var leadingSegment = true

        fun charge(from: Long, to: Long) {
            if (to <= from) return
            var effectiveTo = to
            if (leadingSegment && visible) {
                // Only the first segment inherits its visibility from the previous
                // checkpoint rather than from an observed event, so only it needs the
                // continuity clamp.
                val span = to - from
                if (span > MAX_ASSUMED_CONTINUOUS_VISIBILITY_MS) {
                    effectiveTo = from + MAX_ASSUMED_CONTINUOUS_VISIBILITY_MS
                    onLeadingClamp(span - MAX_ASSUMED_CONTINUOUS_VISIBILITY_MS)
                }
            }
            if (visible) {
                for (slice in boundary.split(from, effectiveTo - from)) {
                    totals[slice.dayId] = (totals[slice.dayId] ?: 0L) + slice.durationMs
                }
            }
        }

        for (transition in ordered) {
            if (transition.visible == visible) continue
            charge(cursor, transition.atWallMs)
            leadingSegment = false
            cursor = transition.atWallMs
            visible = transition.visible
        }
        charge(cursor, endWallMs)

        return totals.map { (dayId, ms) -> DaySlice(dayId, ms) }
    }

    /**
     * The enforcement question, answered from state alone.
     *
     * Order matters: a hole in the history or a blind monitor withdraws access even when
     * the arithmetic says time remains, because an unenforced allowance is not an allowance.
     */
    fun decide(day: DayBudget, health: MonitorHealth, state: CheckpointState): EnforcementDecision = when {
        state == CheckpointState.UNCERTAIN ->
            EnforcementDecision(suspendTargets = true, reason = EnforcementReason.RECOVERY_REQUIRED)

        !health.isHealthy ->
            EnforcementDecision(suspendTargets = true, reason = EnforcementReason.MONITOR_UNHEALTHY)

        day.isExhausted ->
            EnforcementDecision(suspendTargets = true, reason = EnforcementReason.ALLOWANCE_EXHAUSTED)

        else ->
            EnforcementDecision(suspendTargets = false, reason = EnforcementReason.ALLOWANCE_AVAILABLE)
    }

    /**
     * Wall-clock instant at which enforcement must next act, used to arm a backup alarm so
     * that a stalled poll loop is not the only thing standing between an exhausted
     * allowance and continued scrolling.
     *
     * While a target is visible that is when the remaining time runs out; otherwise it is
     * the next day boundary. Returns null when there is nothing to wait for.
     */
    fun nextDeadlineWallMs(
        day: DayBudget,
        nowWallMs: Long,
        targetVisible: Boolean,
        boundary: DayBoundary,
    ): Long? = when {
        targetVisible && !day.isExhausted -> nowWallMs + day.remainingMs
        targetVisible -> nowWallMs
        else -> boundary.nextBoundaryAfter(nowWallMs)
    }

    /** Grant one configured extension. Extra time lives on the day's row, so it expires with it. */
    fun withExtension(day: DayBudget, settings: LimiterSettings): DayBudget =
        day.copy(extraGrantedMs = day.extraGrantedMs + settings.extensionMs)

    /**
     * Change today's base allowance without resetting time already charged. If the new
     * total lands below what has been used, the day is simply exhausted and the caller
     * suspends immediately -- charged time is never rewritten to make the numbers agree.
     */
    fun withBaseAllowance(day: DayBudget, newBaseMs: Long): DayBudget =
        day.copy(baseAllowanceMs = newBaseMs.coerceAtLeast(0L))
}
