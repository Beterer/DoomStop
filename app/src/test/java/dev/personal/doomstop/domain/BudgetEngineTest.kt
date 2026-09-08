package dev.personal.doomstop.domain

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accounting arithmetic, exercised with a fake clock so time advances instantly.
 *
 * The property these tests really defend is "no time is created and none is lost": every
 * scenario checks the total charged against what actually elapsed while visible.
 */
class BudgetEngineTest {

    private val zone = ZoneId.of("Europe/Bucharest")
    private val boundary = DayBoundary(zone)

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private val t0Wall = at("2026-09-08T09:00:00Z")
    private val t0Elapsed = 5_000_000L

    private fun checkpoint(
        visible: Boolean = false,
        wallMs: Long = t0Wall,
        elapsedMs: Long = t0Elapsed,
        bootId: String = "boot:7",
        recovery: RecoveryRequest? = null,
        driftMs: Long = 0,
    ) = Checkpoint(
        bootId = bootId,
        lastElapsedMs = elapsedMs,
        lastWallMs = wallMs,
        settledWallMs = wallMs,
        targetVisible = visible,
        usageCursorWallMs = wallMs,
        acceptedDriftMs = driftMs,
        recovery = recovery,
        trackerState = null,
    )

    /**
     * One pass. [settleLagMs] defaults to zero so most tests can reason about a window that
     * ends at "now"; the coordinator's real lag is exercised where it matters.
     */
    private fun pass(
        previous: Checkpoint,
        afterMs: Long,
        visibleAtEnd: Boolean = false,
        transitions: List<VisibilityTransition> = emptyList(),
        bootId: String = "boot:7",
        eventsAvailable: Boolean = true,
        wallSkewMs: Long = 0,
        elapsedMs: Long = t0Elapsed + afterMs,
        settleLagMs: Long = 0,
    ): Accounting {
        val tick = TickInput(
            bootId = bootId,
            elapsedMs = elapsedMs,
            reportedWallMs = t0Wall + afterMs + wallSkewMs,
            eventsAvailable = eventsAvailable,
        )
        val resolution = BudgetEngine.resolveClock(previous, tick)
        val end = maxOf(previous.settledWallMs, resolution.acceptedNowWallMs - settleLagMs)
        return BudgetEngine.account(
            previous = previous,
            tick = tick,
            resolution = resolution,
            window = SettledWindow(end, visibleAtEnd, null, tick.reportedWallMs),
            transitions = transitions,
            boundary = boundary,
        )
    }

    // -- basic charging --------------------------------------------------------------------

    @Test
    fun `nothing is charged while no target is visible`() {
        val result = pass(checkpoint(visible = false), 60_000)
        assertTrue(result.slices.isEmpty())
        assertEquals(CheckpointState.CLEAN, result.checkpoint.state)
    }

    @Test
    fun `a fully visible interval is charged in full`() {
        val result = pass(checkpoint(visible = true), 1_000, visibleAtEnd = true)
        assertEquals(1_000L, result.totalChargedMs)
        assertEquals("2026-09-08", result.slices.single().dayId)
    }

    @Test
    fun `charging follows the visibility transitions inside the window`() {
        // Window of 10 s; visible for the first 3 s, then again for the last 2 s.
        val transitions = listOf(
            VisibilityTransition(t0Wall + 3_000, false),
            VisibilityTransition(t0Wall + 8_000, true),
        )
        val result = pass(checkpoint(visible = true), 10_000, true, transitions)
        assertEquals(5_000L, result.totalChargedMs)
    }

    @Test
    fun `two targets visible at once still consume one second per second`() {
        // The engine's input is a single boolean, so overlapping targets cannot double-charge.
        val transitions = listOf(
            VisibilityTransition(t0Wall + 1_000, true),
            VisibilityTransition(t0Wall + 2_000, true),
            VisibilityTransition(t0Wall + 3_000, true),
        )
        val result = pass(checkpoint(visible = true), 10_000, true, transitions)
        assertEquals(10_000L, result.totalChargedMs)
    }

    @Test
    fun `rapid switching neither loses nor invents time`() {
        // Alternate visibility every 250 ms across a 10 s window: exactly half is visible.
        val transitions = (1..39).map {
            VisibilityTransition(t0Wall + it * 250L, it % 2 == 0)
        }
        val result = pass(checkpoint(visible = true), 10_000, false, transitions)
        assertEquals(5_000L, result.totalChargedMs)
    }

    @Test
    fun `repeated identical transitions are idempotent`() {
        val once = listOf(VisibilityTransition(t0Wall + 4_000, false))
        val twice = once + once + once
        val a = pass(checkpoint(visible = true), 10_000, false, once)
        val b = pass(checkpoint(visible = true), 10_000, false, twice)
        assertEquals(a.totalChargedMs, b.totalChargedMs)
        assertEquals(4_000L, b.totalChargedMs)
    }

    @Test
    fun `transitions outside the window are ignored`() {
        val transitions = listOf(
            VisibilityTransition(t0Wall - 60_000, false),
            VisibilityTransition(t0Wall + 999_999, false),
        )
        val result = pass(checkpoint(visible = true), 5_000, true, transitions)
        assertEquals(5_000L, result.totalChargedMs)
    }

    // -- the settled window ------------------------------------------------------------------

    @Test
    fun `only the settled part of the window is charged`() {
        // Ten seconds of visible use, but the last three are still open to correction.
        val result = pass(checkpoint(visible = true), 10_000, true, settleLagMs = 3_000)
        assertEquals(7_000L, result.totalChargedMs)
        assertEquals(t0Wall + 7_000, result.checkpoint.settledWallMs)
        // The anchor itself still tracks now, so the next window starts where this one ended.
        assertEquals(t0Wall + 10_000, result.checkpoint.lastWallMs)
    }

    @Test
    fun `consecutive settled windows charge each interval exactly once`() {
        var previous = checkpoint(visible = true)
        var total = 0L
        // Ten one-second passes with a three-second settle lag.
        for (step in 1..10) {
            val result = pass(previous, step * 1_000L, true, settleLagMs = 3_000)
            total += result.totalChargedMs
            previous = result.checkpoint
        }
        assertEquals("nothing settled twice and nothing was skipped", 7_000L, total)
        assertEquals(t0Wall + 7_000, previous.settledWallMs)
    }

    @Test
    fun `a correction inside the open window replaces an estimate instead of adding to it`() {
        // The tail is never written, so recomputing it cannot double-charge: the estimate
        // for the same interval simply changes.
        val optimistic = BudgetEngine.provisionalSlices(
            fromWallMs = t0Wall,
            toWallMs = t0Wall + 2_000,
            visibleAtStart = true,
            transitions = emptyList(),
            boundary = boundary,
        )
        val corrected = BudgetEngine.provisionalSlices(
            fromWallMs = t0Wall,
            toWallMs = t0Wall + 2_000,
            visibleAtStart = true,
            // The PAUSE arrived a poll late and belongs half a second in.
            transitions = listOf(VisibilityTransition(t0Wall + 500, false)),
            boundary = boundary,
        )
        assertEquals(2_000L, optimistic.sumOf { it.durationMs })
        assertEquals(500L, corrected.sumOf { it.durationMs })
    }

    // -- day boundaries ---------------------------------------------------------------------

    @Test
    fun `an interval crossing midnight is charged to both days`() {
        val start = at("2026-09-08T20:55:00Z") // 23:55 local
        val previous = checkpoint(visible = true, wallMs = start)
        val tick = TickInput("boot:7", t0Elapsed + 10 * 60_000L, start + 10 * 60_000L, true)
        val resolution = BudgetEngine.resolveClock(previous, tick)
        val result = BudgetEngine.account(
            previous = previous,
            tick = tick,
            resolution = resolution,
            window = SettledWindow(resolution.acceptedNowWallMs, true, null, tick.reportedWallMs),
            transitions = emptyList(),
            boundary = boundary,
        )
        assertEquals(2, result.slices.size)
        assertEquals(5 * 60_000L, result.slices.first { it.dayId == "2026-09-08" }.durationMs)
        assertEquals(5 * 60_000L, result.slices.first { it.dayId == "2026-09-09" }.durationMs)
    }

    // -- clocks -----------------------------------------------------------------------------

    @Test
    fun `a wall-clock jump forward is refused, so it can neither create time nor a new day`() {
        val previous = checkpoint(visible = true)
        // One second of real time; the wall clock claims a whole day has gone by.
        val result = pass(previous, 1_000, true, wallSkewMs = 24 * 3600_000L)

        assertTrue("no time is invented", result.slices.isEmpty())
        assertTrue(result.anomalies.any { it is Anomaly.ClockJump })
        assertEquals(CheckpointState.UNCERTAIN, result.checkpoint.state)
        assertEquals(
            "the accepted clock advanced by the elapsed second, not by a day",
            t0Wall + 1_000,
            result.checkpoint.lastWallMs,
        )
        assertEquals(
            "so the accounting day is unchanged",
            "2026-09-08",
            boundary.dayIdAt(result.checkpoint.lastWallMs),
        )
    }

    @Test
    fun `a wall-clock jump backwards is refused too`() {
        val result = pass(checkpoint(visible = true), 1_000, true, wallSkewMs = -6 * 3600_000L)
        assertTrue(result.slices.isEmpty())
        assertEquals(CheckpointState.UNCERTAIN, result.checkpoint.state)
        assertEquals(t0Wall + 1_000, result.checkpoint.lastWallMs)
    }

    @Test
    fun `a small wall-clock correction is adopted without comment`() {
        val result = pass(checkpoint(visible = true), 1_000, true, wallSkewMs = 250L)
        assertTrue(result.anomalies.isEmpty())
        // Accounting runs on the ACCEPTED clock, so adopting a correction shifts the window
        // by exactly that correction. The effect is bounded per pass by CLOCK_TOLERANCE_MS
        // and cumulatively by MAX_ACCEPTED_DRIFT_MS; anything larger is refused outright.
        assertEquals(1_250L, result.totalChargedMs)
        assertEquals(t0Wall + 1_250, result.checkpoint.lastWallMs)
        assertEquals(250L, result.checkpoint.acceptedDriftMs)
    }

    @Test
    fun `repeated small corrections cannot be accumulated into a large one`() {
        // Each nudge is individually tolerable; the accumulated total is not, which is what
        // stops the clock from being walked forward a few seconds at a time.
        var previous = checkpoint()
        var elapsed = t0Elapsed
        var reportedWall = t0Wall
        var latched: RecoveryRequest? = null
        repeat(60) {
            elapsed += 1_000
            reportedWall += 5_000 // one second of real time, four seconds of nudge
            val tick = TickInput("boot:7", elapsed, reportedWall, true)
            val resolution = BudgetEngine.resolveClock(previous, tick)
            val result = BudgetEngine.account(
                previous = previous,
                tick = tick,
                resolution = resolution,
                window = SettledWindow(resolution.acceptedNowWallMs, false, null, reportedWall),
                transitions = emptyList(),
                boundary = boundary,
            )
            previous = result.checkpoint
            if (latched == null) latched = previous.recovery
        }
        assertNotNull("accumulated drift must eventually be refused", latched)
        assertTrue(
            "the accepted clock stayed close to real elapsed time",
            previous.lastWallMs - t0Wall <= 60_000L + BudgetEngine.MAX_ACCEPTED_DRIFT_MS,
        )
        assertTrue(
            "while the reported clock ran far ahead of it",
            reportedWall - previous.lastWallMs > 60_000L,
        )
    }

    @Test
    fun `monotonic regression charges nothing and demands recovery`() {
        val previous = checkpoint(visible = true, elapsedMs = t0Elapsed + 10_000)
        val result = pass(previous, 0, true)
        assertTrue(result.slices.isEmpty())
        assertEquals(CheckpointState.UNCERTAIN, result.checkpoint.state)
        assertTrue(result.anomalies.any { it is Anomaly.MonotonicRegression })
    }

    // -- reboot and gaps ----------------------------------------------------------------------

    @Test
    fun `a reboot does not charge the powered-off period`() {
        val previous = checkpoint(visible = true)
        val result = pass(previous, 8 * 3600_000L, false, bootId = "boot:8", elapsedMs = 30_000)
        assertEquals(0L, result.totalChargedMs)
        assertTrue(result.anomalies.any { it is Anomaly.BootChanged })
        assertEquals(CheckpointState.CLEAN, result.checkpoint.state)
    }

    @Test
    fun `after a reboot, usage after boot is charged from its own events`() {
        val previous = checkpoint(visible = true)
        val bootWall = t0Wall + 3600_000L
        val transitions = listOf(VisibilityTransition(bootWall + 40_000L, true))
        val result = pass(
            previous = previous,
            afterMs = 3600_000L + 60_000L,
            visibleAtEnd = true,
            transitions = transitions,
            bootId = "boot:8",
            elapsedMs = 60_000,
        )
        assertEquals(20_000L, result.totalChargedMs)
    }

    @Test
    fun `a reboot that moves the clock further than a power-off explains is refused`() {
        val previous = checkpoint(visible = false)
        val result = pass(
            previous = previous,
            afterMs = BudgetEngine.MAX_TRUSTED_BOOT_GAP_MS + 3600_000L,
            bootId = "boot:8",
            elapsedMs = 30_000,
        )
        assertEquals(CheckpointState.UNCERTAIN, result.checkpoint.state)
        assertTrue(result.checkpoint.recovery!!.reason.contains("power-off"))
    }

    @Test
    fun `an unreadable usage source charges nothing and forces suspension`() {
        val result = pass(checkpoint(visible = true), 60_000, true, eventsAvailable = false)
        assertTrue(result.slices.isEmpty())
        assertEquals(CheckpointState.UNCERTAIN, result.checkpoint.state)
        val decision = BudgetEngine.decide(dayWith(remaining = 10 * 60_000L), healthyMonitor(), result.checkpoint.state)
        assertTrue(decision.suspendTargets)
        assertEquals(EnforcementReason.RECOVERY_REQUIRED, decision.reason)
    }

    @Test
    fun `a single unreadable poll is not treated as a hole in the history`() {
        // One failed query between two one-second polls hides nothing: the monitor already
        // reports itself unhealthy on the same pass, which suspends the targets anyway.
        val result = pass(checkpoint(visible = true), 1_000, true, eventsAvailable = false)
        assertEquals(CheckpointState.CLEAN, result.checkpoint.state)
        assertTrue("but nothing is charged for it either", result.slices.isEmpty())
    }

    @Test
    fun `a lost interval stays lost until it is acknowledged`() {
        // The defect this defends: the next successful poll used to re-anchor as CLEAN, so
        // losing usage access briefly discarded the interval AND reopened the apps.
        val blind = pass(checkpoint(visible = true), 60_000, true, eventsAvailable = false)
        assertEquals(CheckpointState.UNCERTAIN, blind.checkpoint.state)

        var previous = blind.checkpoint
        repeat(20) {
            val tick = TickInput("boot:7", previous.lastElapsedMs + 1_000, previous.lastWallMs + 1_000, true)
            val resolution = BudgetEngine.resolveClock(previous, tick)
            val healthy = BudgetEngine.account(
                previous = previous,
                tick = tick,
                resolution = resolution,
                window = SettledWindow(resolution.acceptedNowWallMs, false, null, tick.reportedWallMs),
                transitions = emptyList(),
                boundary = boundary,
            )
            previous = healthy.checkpoint
            assertEquals(
                "a successful poll must not declare the missing history whole",
                CheckpointState.UNCERTAIN,
                previous.state,
            )
        }
        assertEquals(
            EnforcementReason.RECOVERY_REQUIRED,
            BudgetEngine.decide(dayWith(remaining = 600_000), healthyMonitor(), previous.state).reason,
        )
    }

    @Test
    fun `an already latched request is not overwritten by a later one`() {
        val first = RecoveryRequest("the first thing that went wrong", t0Wall, t0Wall + 1, t0Wall)
        val result = pass(checkpoint(visible = true, recovery = first), 60_000, true, eventsAvailable = false)
        assertEquals(first, result.checkpoint.recovery)
    }

    @Test
    fun `charging resumes normally while a request is still outstanding`() {
        // The latch withholds access; it does not stop the arithmetic, so acknowledging it
        // later does not also hand back time that was genuinely used in the meantime.
        val outstanding = RecoveryRequest("something earlier", t0Wall, t0Wall + 1, t0Wall)
        val result = pass(checkpoint(visible = true, recovery = outstanding), 5_000, true)
        assertEquals(5_000L, result.totalChargedMs)
        assertEquals(CheckpointState.UNCERTAIN, result.checkpoint.state)
    }

    @Test
    fun `a process-death gap while scrolling is reconstructed, not forgiven`() {
        // The monitor was dead for ten minutes. The event source is available and reported
        // no pause, no stop and no screen-off, which is positive evidence that the app
        // stayed on screen -- so the time is charged. This is what stops "kill the monitor
        // and keep scrolling" from being free.
        val result = pass(checkpoint(visible = true), 10 * 60_000L, true)
        assertEquals(10 * 60_000L, result.totalChargedMs)
        assertEquals(CheckpointState.CLEAN, result.checkpoint.state)
    }

    @Test
    fun `a gap during which the phone was locked is not charged`() {
        // Same gap, but the stream shows the screen went off two minutes in.
        val transitions = listOf(VisibilityTransition(t0Wall + 2 * 60_000L, false))
        val result = pass(checkpoint(visible = true), 10 * 60_000L, false, transitions)
        assertEquals(2 * 60_000L, result.totalChargedMs)
    }

    @Test
    fun `an implausibly long unbroken visible claim is refused rather than guessed at`() {
        val result = pass(
            checkpoint(visible = true),
            BudgetEngine.MAX_CONTINUOUS_VISIBLE_MS + 60_000L,
            true,
        )
        assertTrue("no large charge is invented", result.slices.isEmpty())
        assertEquals(CheckpointState.UNCERTAIN, result.checkpoint.state)
        assertTrue(result.anomalies.any { it is Anomaly.UnreconciledGap })
    }

    @Test
    fun `a gap beyond the event retention window is refused, not guessed`() {
        val result = pass(checkpoint(visible = true), BudgetEngine.MAX_REPLAY_MS + 60_000L)
        assertTrue(result.slices.isEmpty())
        assertEquals(CheckpointState.UNCERTAIN, result.checkpoint.state)
    }

    // -- enforcement decisions -----------------------------------------------------------------

    private fun dayWith(remaining: Long, charged: Long = 0) = DayBudget(
        dayId = "2026-09-08",
        baseAllowanceMs = remaining + charged,
        chargedMs = charged,
        extraGrantedMs = 0,
    )

    private fun healthyMonitor() = MonitorHealth(
        deviceOwner = true,
        usageAccessGranted = true,
        serviceRunning = true,
        notificationsEnabled = true,
    )

    @Test
    fun `time remaining keeps targets available`() {
        val decision = BudgetEngine.decide(dayWith(remaining = 60_000), healthyMonitor(), CheckpointState.CLEAN)
        assertFalse(decision.suspendTargets)
        assertEquals(EnforcementReason.ALLOWANCE_AVAILABLE, decision.reason)
    }

    @Test
    fun `an exhausted allowance suspends`() {
        val decision = BudgetEngine.decide(dayWith(remaining = 0, charged = 1800_000), healthyMonitor(), CheckpointState.CLEAN)
        assertTrue(decision.suspendTargets)
        assertEquals(EnforcementReason.ALLOWANCE_EXHAUSTED, decision.reason)
    }

    @Test
    fun `losing usage access suspends even with time remaining`() {
        val blind = healthyMonitor().copy(usageAccessGranted = false)
        val decision = BudgetEngine.decide(dayWith(remaining = 600_000), blind, CheckpointState.CLEAN)
        assertTrue(decision.suspendTargets)
        assertEquals(EnforcementReason.MONITOR_UNHEALTHY, decision.reason)
    }

    @Test
    fun `recovery outranks an exhausted allowance in the reported reason`() {
        val decision = BudgetEngine.decide(dayWith(remaining = 0), healthyMonitor(), CheckpointState.UNCERTAIN)
        assertEquals(EnforcementReason.RECOVERY_REQUIRED, decision.reason)
    }

    @Test
    fun `authorized maintenance stops this app asserting anything`() {
        val decision = BudgetEngine.decide(
            dayWith(remaining = 0),
            healthyMonitor(),
            CheckpointState.UNCERTAIN,
            maintenance = true,
        )
        assertFalse(decision.suspendTargets)
        assertEquals(EnforcementReason.MAINTENANCE, decision.reason)
    }

    // -- balances and grants --------------------------------------------------------------------

    @Test
    fun `an extension adds exactly one configured block`() {
        val settings = LimiterSettings(30 * 60_000L, 10 * 60_000L, zone.id)
        val day = DayBudget("2026-09-08", 30 * 60_000L, 30 * 60_000L, 0)
        assertTrue(day.isExhausted)
        val extended = BudgetEngine.withExtension(day, settings)
        assertEquals(10 * 60_000L, extended.remainingMs)
        assertFalse(extended.isExhausted)
    }

    @Test
    fun `lowering the allowance below what is used exhausts the day without rewriting usage`() {
        val day = DayBudget("2026-09-08", 30 * 60_000L, 25 * 60_000L, 0)
        val reduced = BudgetEngine.withBaseAllowance(day, 10 * 60_000L)
        assertEquals(25 * 60_000L, reduced.chargedMs)
        assertEquals(0L, reduced.remainingMs)
        assertTrue(reduced.isExhausted)
    }

    @Test
    fun `remaining never goes negative`() {
        val day = DayBudget("2026-09-08", 60_000, 90_000, 0)
        assertEquals(0L, day.remainingMs)
    }

    // -- deadlines --------------------------------------------------------------------------------

    @Test
    fun `the deadline while visible is when the remaining time runs out`() {
        val day = DayBudget("2026-09-08", 30 * 60_000L, 25 * 60_000L, 0)
        val deadline = BudgetEngine.nextDeadlineWallMs(day, t0Wall, targetVisible = true, boundary = boundary)
        assertEquals(t0Wall + 5 * 60_000L, deadline)
    }

    @Test
    fun `the deadline while idle is the next day boundary`() {
        val day = DayBudget("2026-09-08", 30 * 60_000L, 0, 0)
        val deadline = BudgetEngine.nextDeadlineWallMs(day, t0Wall, targetVisible = false, boundary = boundary)
        assertEquals(boundary.nextBoundaryAfter(t0Wall), deadline)
        assertNull(null)
    }
}
