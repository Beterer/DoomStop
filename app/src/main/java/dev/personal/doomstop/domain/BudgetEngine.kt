package dev.personal.doomstop.domain

import kotlin.math.absoluteValue

/**
 * The accounting core. Pure Kotlin with no Android and no ambient clock: every instant it
 * uses arrives in [TickInput], which is what lets the unit tests advance time by years
 * without sleeping.
 *
 * Counting rule (plan section 6): charge real elapsed time once whenever at least one
 * target is visible and the screen is unlocked. Two visible targets still consume one
 * second per second, because the input is a single boolean, not a per-app tally.
 *
 * Three separate defences live here, and they are deliberately not collapsed into one flag:
 *
 *  - [resolveClock] decides what "now" is. Duration comes from the monotonic clock; the
 *    system wall clock is adopted only while it agrees. A clock that jumps therefore cannot
 *    drag the accounting day forward into an unvisited date.
 *  - [account] charges only the SETTLED window, which trails now by [SETTLE_LAG_MS], so a
 *    usage event delivered late still arrives before the interval it belongs to has been
 *    written. The unsettled tail is estimated by [provisionalSlices] on every pass and never
 *    committed, so a correction replaces an estimate instead of double-charging.
 *  - Recovery is LATCHED on [Checkpoint.recovery]. A lost interval stays lost until the PIN
 *    holder acknowledges it; no later successful poll may quietly declare the history whole.
 */
object BudgetEngine {

    /**
     * Wall clock and monotonic clock are allowed to disagree by this much per pass without
     * comment. Ordinary NTP corrections live well inside it.
     */
    const val CLOCK_TOLERANCE_MS = 5_000L

    /**
     * Accepted disagreement is also accumulated, so that a long series of individually
     * tolerable nudges cannot walk the clock somewhere large. Reset by a reboot (where the
     * monotonic reference is gone anyway) and by authorized recovery.
     *
     * These two constants are also the honest bound on how far charged time can diverge from
     * monotonic time. Accounting runs on the accepted clock, so adopting a correction stretches
     * or shrinks that pass's window by the correction -- at most [CLOCK_TOLERANCE_MS] in one
     * pass and at most this in total between reboots, in either direction. Larger disagreement
     * is not absorbed at all; it is refused.
     */
    const val MAX_ACCEPTED_DRIFT_MS = 2L * 60_000L

    /**
     * Usage events are not kept forever. A gap wider than this cannot be reconstructed,
     * so it is refused rather than guessed at.
     */
    const val MAX_REPLAY_MS = 7L * 24L * 60L * 60L * 1000L

    /**
     * How far a reboot may move the wall clock forward before the jump stops being
     * explainable as an ordinary power-off.
     *
     * Across a reboot there is no monotonic reference at all, so this is the only available
     * check. A longer genuine power-off (a holiday) is not refused silently -- it is latched
     * for the PIN holder to acknowledge, which is the honest outcome for an interval nobody
     * can reconstruct.
     */
    const val MAX_TRUSTED_BOOT_GAP_MS = 26L * 60L * 60L * 1000L

    /**
     * The longest stretch of uninterrupted visibility that will be believed on the strength
     * of a carried-over flag alone.
     *
     * Silence from the event stream is normally INFORMATIVE, not ignorance: while the event
     * source is available, leaving an app produces ACTIVITY_PAUSED/STOPPED and locking the
     * phone produces SCREEN_NON_INTERACTIVE. So the absence of those during a gap is real
     * evidence that the app stayed on screen, and a process-death gap is reconstructed
     * rather than forgiven -- which is what stops "kill the monitor and keep scrolling"
     * from being free.
     *
     * Past this bound, though, the claim stops being credible. Rather than guess a large
     * charge, the pass is refused: nothing is charged and recovery is latched, so targets
     * are suspended and the PIN holder resolves it.
     */
    const val MAX_CONTINUOUS_VISIBLE_MS = 4L * 60L * 60L * 1000L

    /**
     * How far accounting trails the present.
     *
     * Usage events are not guaranteed to be queryable the instant they occur, which is why
     * the reader overlaps its query windows. Overlapping is only useful if the interval a
     * late event corrects has not been committed yet, so committing waits this long. It must
     * comfortably exceed the reader's overlap.
     */
    const val SETTLE_LAG_MS = 30_000L

    /**
     * A blind pass shorter than this is treated as a transient read failure: nothing is
     * charged and the monitor already reports itself unhealthy (which suspends targets on
     * the same pass), so at most one poll interval of usage is unobserved. A longer blind
     * stretch could hide real usage and is latched for recovery.
     */
    const val BLIND_GRACE_MS = 10_000L

    // -- step one: what time is it, and may we believe it? -----------------------------------

    /**
     * Establish the interval this pass covers before anything is charged for it.
     *
     * Within a boot the DURATION is monotonic, so no wall-clock change can create or destroy
     * allowance, and the accepted wall time advances by exactly that duration. The system
     * clock is adopted only while it stays within [CLOCK_TOLERANCE_MS] of that projection
     * (and while the accumulated adoption stays within [MAX_ACCEPTED_DRIFT_MS]); a larger
     * jump is refused, which is what stops "set the date forward" from selecting an unvisited
     * day with a full allowance.
     */
    fun resolveClock(previous: Checkpoint, tick: TickInput): ClockResolution {
        val anomalies = mutableListOf<Anomaly>()
        val sameBoot = previous.bootId == tick.bootId

        if (!sameBoot) {
            anomalies += Anomaly.BootChanged(previous.bootId, tick.bootId)
            val wallDelta = tick.reportedWallMs - previous.lastWallMs
            val refusal = when {
                wallDelta < 0 -> RecoveryRequest(
                    reason = "the clock moved backwards across a reboot",
                    fromWallMs = tick.reportedWallMs,
                    toWallMs = previous.lastWallMs,
                    detectedAtWallMs = tick.reportedWallMs,
                )

                wallDelta > MAX_REPLAY_MS -> RecoveryRequest(
                    reason = "the gap across a reboot exceeds usage-event retention",
                    fromWallMs = previous.lastWallMs,
                    toWallMs = tick.reportedWallMs,
                    detectedAtWallMs = tick.reportedWallMs,
                )

                wallDelta > MAX_TRUSTED_BOOT_GAP_MS -> RecoveryRequest(
                    reason = "the clock advanced further across a reboot than a power-off explains",
                    fromWallMs = previous.lastWallMs,
                    toWallMs = tick.reportedWallMs,
                    detectedAtWallMs = tick.reportedWallMs,
                )

                else -> null
            }
            // The reported clock is adopted even when refused: refusing to move forward at
            // all would freeze accounting in the past. The latch is what withholds access.
            return ClockResolution(
                acceptedNowWallMs = maxOf(tick.reportedWallMs, previous.lastWallMs),
                durationMs = wallDelta.coerceIn(0L, MAX_REPLAY_MS),
                sameBoot = false,
                acceptedDriftMs = 0L,
                anomalies = anomalies,
                refusal = refusal,
            )
        }

        val delta = tick.elapsedMs - previous.lastElapsedMs
        if (delta < 0) {
            anomalies += Anomaly.MonotonicRegression(delta)
            return ClockResolution(
                acceptedNowWallMs = previous.lastWallMs,
                durationMs = 0L,
                sameBoot = true,
                acceptedDriftMs = previous.acceptedDriftMs,
                anomalies = anomalies,
                refusal = RecoveryRequest(
                    reason = "monotonic time went backwards within one boot",
                    fromWallMs = previous.lastWallMs,
                    toWallMs = previous.lastWallMs,
                    detectedAtWallMs = tick.reportedWallMs,
                ),
            )
        }
        if (delta > MAX_REPLAY_MS) {
            return ClockResolution(
                acceptedNowWallMs = previous.lastWallMs + delta,
                durationMs = delta,
                sameBoot = true,
                acceptedDriftMs = previous.acceptedDriftMs,
                anomalies = anomalies,
                refusal = RecoveryRequest(
                    reason = "the gap exceeds usage-event retention",
                    fromWallMs = previous.lastWallMs,
                    toWallMs = previous.lastWallMs + delta,
                    detectedAtWallMs = tick.reportedWallMs,
                ),
            )
        }

        val projectedWallMs = previous.lastWallMs + delta
        val skew = tick.reportedWallMs - projectedWallMs
        val drift = previous.acceptedDriftMs + skew

        // Small, and cumulatively small, disagreement: adopt the system clock so ordinary
        // time synchronisation is tracked instead of drifting away from the real calendar.
        if (skew.absoluteValue <= CLOCK_TOLERANCE_MS && drift.absoluteValue <= MAX_ACCEPTED_DRIFT_MS) {
            return ClockResolution(
                acceptedNowWallMs = tick.reportedWallMs,
                durationMs = delta,
                sameBoot = true,
                acceptedDriftMs = drift,
                anomalies = anomalies,
                refusal = null,
            )
        }

        anomalies += Anomaly.ClockJump(skew)
        return ClockResolution(
            acceptedNowWallMs = projectedWallMs,
            durationMs = delta,
            sameBoot = true,
            acceptedDriftMs = previous.acceptedDriftMs,
            anomalies = anomalies,
            refusal = RecoveryRequest(
                reason = if (skew > 0) {
                    "the system clock jumped forward relative to elapsed time"
                } else {
                    "the system clock jumped backwards relative to elapsed time"
                },
                fromWallMs = projectedWallMs,
                toWallMs = tick.reportedWallMs,
                detectedAtWallMs = tick.reportedWallMs,
            ),
        )
    }

    // -- step two: charge the settled window -------------------------------------------------

    /**
     * Reconcile `[previous.settledWallMs, window.endWallMs]` exactly once.
     *
     * Only this interval is ever written. It is chosen by the caller as
     * `acceptedNow - SETTLE_LAG_MS`, never runs backwards, and never overlaps a window that
     * has already been charged -- which is what makes replayed and late events safe.
     */
    fun account(
        previous: Checkpoint,
        tick: TickInput,
        resolution: ClockResolution,
        window: SettledWindow,
        transitions: List<VisibilityTransition>,
        boundary: DayBoundary,
    ): Accounting {
        val anomalies = resolution.anomalies.toMutableList()
        // Latched: an existing request is never replaced or cleared here, only by
        // authorized recovery. A later clean pass cannot relabel lost history complete.
        var latched: RecoveryRequest? = previous.recovery ?: resolution.refusal

        if (!tick.eventsAvailable) {
            val gapMs = resolution.durationMs
            anomalies += Anomaly.UnreconciledGap(gapMs, "usage events unavailable")
            if (latched == null && gapMs > BLIND_GRACE_MS) {
                latched = RecoveryRequest(
                    reason = "the usage-event source was unreadable for a stretch of time",
                    fromWallMs = previous.settledWallMs,
                    toWallMs = window.endWallMs,
                    detectedAtWallMs = resolution.acceptedNowWallMs,
                )
            }
            return Accounting(emptyList(), reanchor(previous, tick, resolution, window, latched), anomalies)
        }

        if (resolution.refusal != null) {
            return Accounting(emptyList(), reanchor(previous, tick, resolution, window, latched), anomalies)
        }

        val windowStartMs = previous.settledWallMs
        val windowEndMs = window.endWallMs
        // Nothing is visible while the device is off or rebooting. Any real visibility after
        // boot arrives as an ACTIVITY_RESUMED transition, so starting the window "not
        // visible" costs at most the sub-second sliver before shutdown.
        val initiallyVisible = resolution.sameBoot && previous.targetVisible

        val ordered = orderedInside(transitions, windowStartMs, windowEndMs)

        // The leading segment is the only one whose visibility is inherited rather than
        // observed, so it is the only one that can be implausibly long.
        if (initiallyVisible) {
            val firstEndOfVisibility = ordered.firstOrNull { !it.visible }?.atWallMs ?: windowEndMs
            val leadingMs = firstEndOfVisibility - windowStartMs
            if (leadingMs > MAX_CONTINUOUS_VISIBLE_MS) {
                anomalies += Anomaly.UnreconciledGap(
                    gapMs = leadingMs,
                    reason = "no event contradicted visibility for longer than is credible",
                )
                if (latched == null) {
                    latched = RecoveryRequest(
                        reason = "a target appeared to stay on screen for longer than is credible",
                        fromWallMs = windowStartMs,
                        toWallMs = firstEndOfVisibility,
                        detectedAtWallMs = resolution.acceptedNowWallMs,
                    )
                }
                return Accounting(emptyList(), reanchor(previous, tick, resolution, window, latched), anomalies)
            }
        }

        val slices = integrateVisible(windowStartMs, windowEndMs, initiallyVisible, ordered, boundary)
        return Accounting(slices, reanchor(previous, tick, resolution, window, latched), anomalies)
    }

    /**
     * Settle the interval that was still open for correction when the previous boot ended.
     *
     * Accounting deliberately trails the present, so at any instant the last [SETTLE_LAG_MS]
     * is an estimate that has not been written. A reboot would otherwise discard it: the new
     * boot has no monotonic reference to the old one, so the ordinary pass starts from "not
     * visible" and charges nothing for it. That would have made rebooting worth up to half a
     * minute of free use, repeatedly.
     *
     * The events for that tail are durable, so it is charged here from the same replay the
     * next ordinary pass would have used, before the boot gap is considered at all.
     */
    fun flushOpenTail(
        previous: Checkpoint,
        transitions: List<VisibilityTransition>,
        visibleAtEnd: Boolean,
        trackerState: String?,
        boundary: DayBoundary,
    ): Accounting {
        if (previous.lastWallMs <= previous.settledWallMs) return Accounting(emptyList(), previous, emptyList())
        val ordered = orderedInside(transitions, previous.settledWallMs, previous.lastWallMs)
        val slices = integrateVisible(
            startWallMs = previous.settledWallMs,
            endWallMs = previous.lastWallMs,
            initiallyVisible = previous.targetVisible,
            ordered = ordered,
            boundary = boundary,
        )
        return Accounting(
            slices = slices,
            checkpoint = previous.copy(
                settledWallMs = previous.lastWallMs,
                targetVisible = visibleAtEnd,
                trackerState = trackerState,
            ),
            anomalies = emptyList(),
        )
    }

    /**
     * Charge for the tail that has not settled yet, for the enforcement decision only.
     *
     * This is recomputed from scratch on every pass and never written, so a late event that
     * changes it corrects an estimate rather than adding a second debit. It is what keeps
     * cut-off within a poll interval despite accounting itself lagging by [SETTLE_LAG_MS].
     */
    fun provisionalSlices(
        fromWallMs: Long,
        toWallMs: Long,
        visibleAtStart: Boolean,
        transitions: List<VisibilityTransition>,
        boundary: DayBoundary,
    ): List<DaySlice> = integrateVisible(
        startWallMs = fromWallMs,
        endWallMs = toWallMs,
        initiallyVisible = visibleAtStart,
        ordered = orderedInside(transitions, fromWallMs, toWallMs),
        boundary = boundary,
    )

    private fun orderedInside(
        transitions: List<VisibilityTransition>,
        startMs: Long,
        endMs: Long,
    ): List<VisibilityTransition> = transitions
        .filter { it.atWallMs in startMs..endMs }
        .sortedBy { it.atWallMs }

    private fun reanchor(
        previous: Checkpoint,
        tick: TickInput,
        resolution: ClockResolution,
        window: SettledWindow,
        recovery: RecoveryRequest?,
    ) = previous.copy(
        bootId = tick.bootId,
        lastElapsedMs = tick.elapsedMs,
        lastWallMs = resolution.acceptedNowWallMs,
        settledWallMs = window.endWallMs,
        targetVisible = window.visibleAtEnd,
        usageCursorWallMs = window.usageCursorWallMs,
        acceptedDriftMs = resolution.acceptedDriftMs,
        recovery = recovery,
        trackerState = window.trackerState,
    )

    /**
     * Integrate "at least one target visible" over the window and split the result across
     * accounting days. Transitions outside the window are ignored; duplicated or redundant
     * transitions collapse, so a missed callback costs nothing and a repeated one adds
     * nothing -- intervals are reconciled, not callbacks counted.
     */
    private fun integrateVisible(
        startWallMs: Long,
        endWallMs: Long,
        initiallyVisible: Boolean,
        ordered: List<VisibilityTransition>,
        boundary: DayBoundary,
    ): List<DaySlice> {
        if (endWallMs <= startWallMs) return emptyList()

        val totals = LinkedHashMap<DayId, Long>()
        var cursor = startWallMs
        var visible = initiallyVisible

        fun charge(from: Long, to: Long) {
            if (to <= from || !visible) return
            for (slice in boundary.split(from, to - from)) {
                totals[slice.dayId] = (totals[slice.dayId] ?: 0L) + slice.durationMs
            }
        }

        for (transition in ordered) {
            // A transition that does not change the state contributes nothing, which is
            // what makes duplicated and replayed events harmless.
            if (transition.visible == visible) continue
            charge(cursor, transition.atWallMs)
            cursor = transition.atWallMs
            visible = transition.visible
        }
        charge(cursor, endWallMs)

        return totals.map { (dayId, ms) -> DaySlice(dayId, ms) }
    }

    // -- step three: decide ------------------------------------------------------------------

    /**
     * The enforcement question, answered from state alone.
     *
     * Order matters: a hole in the history or a blind monitor withdraws access even when
     * the arithmetic says time remains, because an unenforced allowance is not an allowance.
     */
    fun decide(
        day: DayBudget,
        health: MonitorHealth,
        state: CheckpointState,
        maintenance: Boolean = false,
    ): EnforcementDecision = when {
        // Authorized maintenance is the one state in which this app deliberately stops
        // asserting policy, so that a restore is not fought by the next poll.
        maintenance ->
            EnforcementDecision(suspendTargets = false, reason = EnforcementReason.MAINTENANCE)

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
     * the next day boundary.
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
