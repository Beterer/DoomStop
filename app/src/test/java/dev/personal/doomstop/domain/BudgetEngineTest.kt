package dev.personal.doomstop.domain

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        state: CheckpointState = CheckpointState.CLEAN,
    ) = Checkpoint(
        bootId = bootId,
        lastElapsedMs = elapsedMs,
        lastWallMs = wallMs,
        targetVisible = visible,
        usageCursorWallMs = wallMs,
        state = state,
    )

    private fun tick(
        afterMs: Long,
        visibleNow: Boolean,
        bootId: String = "boot:7",
        eventsAvailable: Boolean = true,
        wallSkewMs: Long = 0,
    ) = TickInput(
        bootId = bootId,
        elapsedMs = t0Elapsed + afterMs,
        wallMs = t0Wall + afterMs + wallSkewMs,
        visibleNow = visibleNow,
        usageCursorWallMs = t0Wall + afterMs + wallSkewMs,
        eventsAvailable = eventsAvailable,
    )

    // -- basic charging --------------------------------------------------------------------

    @Test
    fun `nothing is charged while no target is visible`() {
        val result = BudgetEngine.account(checkpoint(visible = false), tick(60_000, false), emptyList(), boundary)
        assertTrue(result.slices.isEmpty())
        assertEquals(CheckpointState.CLEAN, result.checkpoint.state)
    }

    @Test
    fun `a fully visible interval is charged in full`() {
        val result = BudgetEngine.account(checkpoint(visible = true), tick(1_000, true), emptyList(), boundary)
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
        val result = BudgetEngine.account(checkpoint(visible = true), tick(10_000, true), transitions, boundary)
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
        val result = BudgetEngine.account(checkpoint(visible = true), tick(10_000, true), transitions, boundary)
        assertEquals(10_000L, result.totalChargedMs)
    }

    @Test
    fun `rapid switching neither loses nor invents time`() {
        // Alternate visibility every 250 ms across a 10 s window: exactly half is visible.
        val transitions = (1..39).map {
            VisibilityTransition(t0Wall + it * 250L, it % 2 == 0)
        }
        val result = BudgetEngine.account(checkpoint(visible = true), tick(10_000, false), transitions, boundary)
        assertEquals(5_000L, result.totalChargedMs)
    }

    @Test
    fun `repeated identical transitions are idempotent`() {
        val once = listOf(VisibilityTransition(t0Wall + 4_000, false))
        val twice = once + once + once
        val a = BudgetEngine.account(checkpoint(visible = true), tick(10_000, false), once, boundary)
        val b = BudgetEngine.account(checkpoint(visible = true), tick(10_000, false), twice, boundary)
        assertEquals(a.totalChargedMs, b.totalChargedMs)
        assertEquals(4_000L, b.totalChargedMs)
    }

    @Test
    fun `transitions outside the window are ignored`() {
        val transitions = listOf(
            VisibilityTransition(t0Wall - 60_000, false),
            VisibilityTransition(t0Wall + 999_999, false),
        )
        val result = BudgetEngine.account(checkpoint(visible = true), tick(5_000, true), transitions, boundary)
        assertEquals(5_000L, result.totalChargedMs)
    }

    // -- day boundaries ---------------------------------------------------------------------

    @Test
    fun `an interval crossing midnight is charged to both days`() {
        val start = at("2026-09-08T20:55:00Z") // 23:55 local
        val previous = checkpoint(visible = true, wallMs = start)
        val input = TickInput(
            bootId = "boot:7",
            elapsedMs = t0Elapsed + 10 * 60_000L,
            wallMs = start + 10 * 60_000L,
            visibleNow = true,
            usageCursorWallMs = start + 10 * 60_000L,
            eventsAvailable = true,
        )
        val result = BudgetEngine.account(previous, input, emptyList(), boundary)
        assertEquals(2, result.slices.size)
        assertEquals(5 * 60_000L, result.slices.first { it.dayId == "2026-09-08" }.durationMs)
        assertEquals(5 * 60_000L, result.slices.first { it.dayId == "2026-09-09" }.durationMs)
    }

    // -- clocks -----------------------------------------------------------------------------

    @Test
    fun `duration comes from the monotonic clock, so a wall-clock jump creates no time`() {
        // The wall clock leaps an hour forward, but only one second of monotonic time passed.
        val result = BudgetEngine.account(
            checkpoint(visible = true),
            tick(1_000, true, wallSkewMs = 3_600_000L),
            emptyList(),
            boundary,
        )
        assertEquals(1_000L, result.totalChargedMs)
        assertTrue(result.anomalies.any { it is Anomaly.ClockJump })
    }

    @Test
    fun `a small wall-clock correction is not reported as an anomaly`() {
        val result = BudgetEngine.account(
            checkpoint(visible = true),
            tick(1_000, true, wallSkewMs = 250L),
            emptyList(),
            boundary,
        )
        assertTrue(result.anomalies.isEmpty())
    }

    @Test
    fun `monotonic regression charges nothing and demands recovery`() {
        val previous = checkpoint(visible = true, elapsedMs = t0Elapsed + 10_000)
        val result = BudgetEngine.account(previous, tick(0, true), emptyList(), boundary)
        assertTrue(result.slices.isEmpty())
        assertEquals(CheckpointState.UNCERTAIN, result.checkpoint.state)
        assertTrue(result.anomalies.any { it is Anomaly.MonotonicRegression })
    }

    // -- reboot and gaps ----------------------------------------------------------------------

    @Test
    fun `a reboot does not charge the powered-off period`() {
        val previous = checkpoint(visible = true)
        val input = TickInput(
            bootId = "boot:8",
            elapsedMs = 30_000, // monotonic clock restarted
            wallMs = t0Wall + 8 * 3600_000L,
            visibleNow = false,
            usageCursorWallMs = t0Wall + 8 * 3600_000L,
            eventsAvailable = true,
        )
        val result = BudgetEngine.account(previous, input, emptyList(), boundary)
        assertEquals(0L, result.totalChargedMs)
        assertTrue(result.anomalies.any { it is Anomaly.BootChanged })
        assertEquals(CheckpointState.CLEAN, result.checkpoint.state)
    }

    @Test
    fun `after a reboot, usage after boot is charged from its own events`() {
        val previous = checkpoint(visible = true)
        val bootWall = t0Wall + 3600_000L
        val input = TickInput(
            bootId = "boot:8",
            elapsedMs = 60_000,
            wallMs = bootWall + 60_000L,
            visibleNow = true,
            usageCursorWallMs = bootWall + 60_000L,
            eventsAvailable = true,
        )
        // Instagram resumed 20 s before "now".
        val transitions = listOf(VisibilityTransition(bootWall + 40_000L, true))
        val result = BudgetEngine.account(previous, input, transitions, boundary)
        assertEquals(20_000L, result.totalChargedMs)
    }

    @Test
    fun `an unreadable usage source charges nothing and forces suspension`() {
        val result = BudgetEngine.account(
            checkpoint(visible = true),
            tick(60_000, true, eventsAvailable = false),
            emptyList(),
            boundary,
        )
        assertTrue(result.slices.isEmpty())
        assertEquals(CheckpointState.UNCERTAIN, result.checkpoint.state)
        val decision = BudgetEngine.decide(dayWith(remaining = 10 * 60_000L), healthyMonitor(), result.checkpoint.state)
        assertTrue(decision.suspendTargets)
        assertEquals(EnforcementReason.RECOVERY_REQUIRED, decision.reason)
    }

    @Test
    fun `a process-death gap while scrolling is reconstructed, not forgiven`() {
        // The monitor was dead for ten minutes. The event source is available and reported
        // no pause, no stop and no screen-off, which is positive evidence that the app
        // stayed on screen -- so the time is charged. This is what stops "kill the monitor
        // and keep scrolling" from being free.
        val result = BudgetEngine.account(checkpoint(visible = true), tick(10 * 60_000L, true), emptyList(), boundary)
        assertEquals(10 * 60_000L, result.totalChargedMs)
        assertEquals(CheckpointState.CLEAN, result.checkpoint.state)
    }

    @Test
    fun `a gap during which the phone was locked is not charged`() {
        // Same gap, but the stream shows the screen went off two minutes in.
        val transitions = listOf(VisibilityTransition(t0Wall + 2 * 60_000L, false))
        val result = BudgetEngine.account(checkpoint(visible = true), tick(10 * 60_000L, false), transitions, boundary)
        assertEquals(2 * 60_000L, result.totalChargedMs)
    }

    @Test
    fun `an implausibly long unbroken visible claim is refused rather than guessed at`() {
        val result = BudgetEngine.account(
            checkpoint(visible = true),
            tick(BudgetEngine.MAX_CONTINUOUS_VISIBLE_MS + 60_000L, true),
            emptyList(),
            boundary,
        )
        assertTrue("no large charge is invented", result.slices.isEmpty())
        assertEquals(CheckpointState.UNCERTAIN, result.checkpoint.state)
        assertTrue(result.anomalies.any { it is Anomaly.UnreconciledGap })
    }

    @Test
    fun `a gap beyond the event retention window is refused, not guessed`() {
        val result = BudgetEngine.account(
            checkpoint(visible = true),
            tick(BudgetEngine.MAX_REPLAY_MS + 60_000L, false),
            emptyList(),
            boundary,
        )
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
