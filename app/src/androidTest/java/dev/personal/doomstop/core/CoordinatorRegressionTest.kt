package dev.personal.doomstop.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.personal.doomstop.config.TargetPackages
import dev.personal.doomstop.data.BootMarkerStore
import dev.personal.doomstop.data.LimiterDatabase
import dev.personal.doomstop.data.PinVerifierEntity
import dev.personal.doomstop.data.PreviousPolicyState
import dev.personal.doomstop.data.SettingsEntity
import dev.personal.doomstop.domain.CheckpointState
import dev.personal.doomstop.domain.ClockSource
import dev.personal.doomstop.domain.DayBoundary
import dev.personal.doomstop.domain.EnforcementReason
import dev.personal.doomstop.monitor.AppPermissions
import dev.personal.doomstop.monitor.TrackedEvent
import dev.personal.doomstop.monitor.TrackedEventType
import dev.personal.doomstop.monitor.UsageReadResult
import dev.personal.doomstop.monitor.UsageSource
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A clock the test owns outright, so a whole day can pass between two statements. */
private class TestClock(
    var wallMs: Long = 1_788_000_000_000L, // 2026-09-08T12:00:00Z, fixed
    var elapsedMs: Long = 1_000_000L,
    var boot: String = "boot:1",
) : ClockSource {
    override fun elapsedRealtimeMs(): Long = elapsedMs
    override fun wallTimeMs(): Long = wallMs
    override fun bootId(): String = boot

    /** Advance both clocks together, the way real time does. */
    fun advance(ms: Long) {
        wallMs += ms
        elapsedMs += ms
    }
}

/**
 * A usage-event stream whose DELIVERY can be delayed independently of when the event
 * happened -- which is the whole point of this suite.
 */
private class ScriptedUsage : UsageSource {
    var available = true

    private val emitted = mutableListOf<TrackedEvent>()
    private val held = mutableListOf<TrackedEvent>()

    fun resume(pkg: String, atWallMs: Long) = add(pkg, atWallMs, TrackedEventType.ACTIVITY_RESUMED)
    fun pause(pkg: String, atWallMs: Long) = add(pkg, atWallMs, TrackedEventType.ACTIVITY_PAUSED)
    fun stop(pkg: String, atWallMs: Long) = add(pkg, atWallMs, TrackedEventType.ACTIVITY_STOPPED)

    /** Record an event that happened now but will not be queryable until [deliver] is called. */
    fun holdPause(pkg: String, atWallMs: Long) = hold(pkg, atWallMs, TrackedEventType.ACTIVITY_PAUSED)
    fun holdStop(pkg: String, atWallMs: Long) = hold(pkg, atWallMs, TrackedEventType.ACTIVITY_STOPPED)

    fun deliver() {
        emitted += held
        held.clear()
    }

    private fun add(pkg: String, atWallMs: Long, type: TrackedEventType) {
        emitted += TrackedEvent(atWallMs, pkg, "$pkg.MainActivity", type)
    }

    private fun hold(pkg: String, atWallMs: Long, type: TrackedEventType) {
        held += TrackedEvent(atWallMs, pkg, "$pkg.MainActivity", type)
    }

    override fun hasUsageAccess(): Boolean = available

    override fun read(sinceWallMs: Long, nowWallMs: Long): UsageReadResult {
        if (!available) return UsageReadResult(emptyList(), false, sinceWallMs, "usage access not granted")
        // The real reader deliberately overlaps its query windows, so it re-delivers a tail
        // of already-seen events; de-duplication downstream must make that a no-op.
        val from = sinceWallMs - 10_000L
        val window = emitted.filter { it.timestampWallMs >= from && it.timestampWallMs <= nowWallMs }
        return UsageReadResult(window, true, nowWallMs)
    }
}

/**
 * The regressions from the code review, at the level they actually occurred: several
 * sequential passes against persisted state, and a coordinator rebuilt from that state the
 * way a restarted process rebuilds it.
 *
 * Device ownership is faked so these run on any device; the real policy calls have their own
 * suite against a provisioned one.
 */
@RunWith(AndroidJUnit4::class)
class CoordinatorRegressionTest {

    private lateinit var context: Context
    private lateinit var database: LimiterDatabase
    private lateinit var policy: FakePolicyGateway
    private lateinit var clock: TestClock
    private lateinit var usage: ScriptedUsage
    private lateinit var coordinator: LimiterCoordinator

    private val allowanceMs = 120_000L
    private val extensionMs = 10_000L
    private val zone = ZoneId.of("Europe/Bucharest")
    private val instagram = TargetPackages.INSTAGRAM

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        assumeTrue(
            "these tests meter screen time, so the device's screen must be on and unlocked",
            AppPermissions.isScreenInteractive(context) && !AppPermissions.isKeyguardLocked(context),
        )
        database = Room.inMemoryDatabaseBuilder(context, LimiterDatabase::class.java).build()
        policy = FakePolicyGateway()
        clock = TestClock()
        usage = ScriptedUsage()
        coordinator = newCoordinator()

        database.dao().upsertSettings(
            SettingsEntity(
                dailyAllowanceMs = allowanceMs,
                extensionMs = extensionMs,
                zoneId = zone.id,
                setupCompleted = true,
            )
        )
        // A PIN is one of the prerequisites for enforcement; these tests are not about the
        // KDF, so a placeholder verifier stands in for one the trusted person chose.
        database.dao().upsertPinVerifier(
            PinVerifierEntity(
                version = 1,
                algorithm = "PBKDF2withHmacSHA256",
                iterations = 1_000,
                saltBase64 = "AAAAAAAAAAAAAAAAAAAAAA==",
                verifierBase64 = "AAAAAAAAAAAAAAAAAAAAAA==",
            )
        )
        coordinator.tick(Trigger.UI)
    }

    /** Forget every policy value this app has recorded, as if it had never run before. */
    private suspend fun forgetLedger() {
        database.dao().ledger().forEach { database.dao().deleteLedgerEntry(it.key) }
    }

    @After
    fun tearDown() {
        if (this::database.isInitialized) database.close()
    }

    private fun newCoordinator() = LimiterCoordinator(
        context = context,
        dao = database.dao(),
        policy = policy,
        reader = usage,
        bootMarker = BootMarkerStore(context),
        clock = clock,
        deadlines = DeadlineScheduler(context),
    ).also { it.serviceRunning = true }

    /** Exactly what a restarted process does: same database, brand new objects in memory. */
    private fun restartProcess() {
        coordinator = newCoordinator()
    }

    /** Advance the fake clock and run a tick, the way the poll loop would. */
    private suspend fun runFor(ms: Long, steps: Int = 1) {
        repeat(steps) {
            clock.advance(ms / steps)
            coordinator.tick(Trigger.POLL)
        }
    }

    private suspend fun settledChargeMs(dayId: String? = null): Long {
        val day = dayId ?: coordinator.status.value.dayId
        return database.dao().day(day)?.chargedMs ?: 0L
    }

    // -- F1: a lost interval stays lost until it is acknowledged ----------------------------

    @Test
    fun losingTheUsageSourceLeavesRecoveryOutstandingAcrossManyHealthyPolls() = runTest {
        usage.resume(instagram, clock.wallMs)
        runFor(20_000)
        assertFalse(coordinator.status.value.enforcement.suspendTargets)

        // Usage access goes away for long enough that real usage could have been missed.
        usage.available = false
        runFor(60_000)
        assertEquals(CheckpointState.UNCERTAIN, coordinator.status.value.checkpointState)

        // It comes back. Twenty ordinary polls must not declare the history whole again.
        usage.available = true
        runFor(20_000, steps = 20)
        val status = coordinator.status.value
        assertEquals(CheckpointState.UNCERTAIN, status.checkpointState)
        assertEquals(EnforcementReason.RECOVERY_REQUIRED, status.enforcement.reason)
        assertTrue(status.enforcement.suspendTargets)
        assertNotNull("the reason and range are kept for the PIN holder", status.recovery)
    }

    @Test
    fun anOutstandingRecoveryRequestSurvivesACoordinatorRestart() = runTest {
        usage.available = false
        runFor(60_000)
        assertEquals(CheckpointState.UNCERTAIN, coordinator.status.value.checkpointState)

        usage.available = true
        restartProcess()
        runFor(5_000)
        assertEquals(
            "a fresh process must read the outstanding request back, not start clean",
            CheckpointState.UNCERTAIN,
            coordinator.status.value.checkpointState,
        )
    }

    @Test
    fun acknowledgingRecoveryResumesAccessAccordingToTheRetainedBalance() = runTest {
        usage.resume(instagram, clock.wallMs)
        runFor(90_000) // most of the two-minute allowance
        usage.pause(instagram, clock.wallMs)
        usage.stop(instagram, clock.wallMs)

        usage.available = false
        runFor(60_000)
        assertTrue(coordinator.status.value.enforcement.suspendTargets)

        usage.available = true
        val after = coordinator.acknowledgeRecovery()
        assertEquals(CheckpointState.CLEAN, after.checkpointState)
        assertNull(after.recovery)
        assertFalse("time remained, so access comes back", after.enforcement.suspendTargets)
        assertEquals("and the balance is what was actually used", 90_000L, after.chargedMs)
    }

    // -- F2: an app left open across a restart ------------------------------------------------

    @Test
    fun anAppLeftOpenAcrossAProcessRestartKeepsBeingCharged() = runTest {
        usage.resume(instagram, clock.wallMs)
        runFor(60_000)
        assertEquals(60_000L, coordinator.status.value.chargedMs)

        // The monitor is killed and comes back. No new lifecycle event is produced, because
        // the app never left the screen -- which is exactly the case that used to go free.
        restartProcess()
        runFor(30_000, steps = 30)

        val status = coordinator.status.value
        assertEquals("the whole session is still being charged", 90_000L, status.chargedMs)
        assertTrue("and it is still on screen", status.targetVisible)
    }

    @Test
    fun leavingTheAppAfterARestartStopsTheChargeAtTheRightMoment() = runTest {
        usage.resume(instagram, clock.wallMs)
        runFor(60_000)
        restartProcess()

        runFor(10_000)
        usage.pause(instagram, clock.wallMs)
        usage.stop(instagram, clock.wallMs)
        runFor(60_000)

        assertEquals("nothing after the app was closed", 70_000L, coordinator.status.value.chargedMs)
        assertFalse(coordinator.status.value.targetVisible)
    }

    @Test
    fun anAppOpenAtShutdownIsChargedUpToTheRebootRatherThanForgiven() = runTest {
        usage.resume(instagram, clock.wallMs)
        runFor(45_000)
        assertEquals(45_000L, coordinator.status.value.chargedMs)

        // Reboot: monotonic clock restarts, wall clock carries on, boot identity changes.
        clock.wallMs += 120_000
        clock.elapsedMs = 5_000
        clock.boot = "boot:2"
        restartProcess()
        coordinator.tick(Trigger.BOOT)

        assertEquals(
            "the window still open at shutdown is settled, not discarded",
            45_000L,
            settledChargeMs(),
        )
        assertFalse("nothing is charged for the time the phone was off", coordinator.status.value.targetVisible)
    }

    // -- F3: the clock cannot buy a new day ----------------------------------------------------

    @Test
    fun aForwardWallClockJumpDoesNotCreateANewSpendableDay() = runTest {
        usage.resume(instagram, clock.wallMs)
        runFor(allowanceMs)
        val exhaustedDay = coordinator.status.value.dayId
        assertTrue(coordinator.status.value.enforcement.suspendTargets)

        usage.pause(instagram, clock.wallMs)
        usage.stop(instagram, clock.wallMs)
        runFor(1_000)

        // The date is set a day forward while one second of real time passes.
        clock.wallMs += 24 * 3600_000L
        clock.elapsedMs += 1_000
        coordinator.tick(Trigger.POLL)

        val status = coordinator.status.value
        assertEquals("the accounting day did not move", exhaustedDay, status.dayId)
        assertEquals(0L, status.remainingMs)
        assertTrue(status.enforcement.suspendTargets)
        assertEquals(EnforcementReason.RECOVERY_REQUIRED, status.enforcement.reason)
        assertNull("and no fresh day row was created", database.dao().day("2026-09-09"))
    }

    @Test
    fun windingTheClockBackwardsIsRefusedAndKeepsTheDayExhausted() = runTest {
        usage.resume(instagram, clock.wallMs)
        runFor(allowanceMs)
        val day = coordinator.status.value.dayId
        usage.pause(instagram, clock.wallMs)
        usage.stop(instagram, clock.wallMs)
        runFor(1_000)

        clock.wallMs -= 6 * 3600_000L
        clock.elapsedMs += 1_000
        coordinator.tick(Trigger.POLL)

        val status = coordinator.status.value
        assertEquals(day, status.dayId)
        assertEquals(0L, status.remainingMs)
        assertTrue(status.enforcement.suspendTargets)
    }

    @Test
    fun ordinaryMidnightRolloverStillGrantsExactlyOneAllowance() = runTest {
        usage.resume(instagram, clock.wallMs)
        runFor(allowanceMs)
        val exhaustedDay = coordinator.status.value.dayId
        usage.pause(instagram, clock.wallMs)
        usage.stop(instagram, clock.wallMs)

        // Real time passing through midnight: both clocks move together.
        runFor(14 * 3600_000L, steps = 14)

        val status = coordinator.status.value
        assertFalse(exhaustedDay == status.dayId)
        assertEquals(allowanceMs, status.remainingMs)
        assertFalse(status.enforcement.suspendTargets)
        assertEquals("yesterday's usage is still on record", allowanceMs, settledChargeMs(exhaustedDay))
    }

    // -- unused time carries over --------------------------------------------------------------

    /** Idle polls, an hour apart at most, until the clock reaches [wallMs]. */
    private suspend fun idleUntil(wallMs: Long) {
        while (clock.wallMs < wallMs) {
            clock.advance(minOf(3600_000L, wallMs - clock.wallMs))
            coordinator.tick(Trigger.POLL)
        }
    }

    private fun nextMidnight(): Long = DayBoundary(zone).nextBoundaryAfter(clock.wallMs)

    @Test
    fun unusedTimeCarriesIntoTheNextDay() = runTest {
        usage.resume(instagram, clock.wallMs)
        runFor(30_000, steps = 30)
        usage.pause(instagram, clock.wallMs)
        usage.stop(instagram, clock.wallMs)
        val firstDay = coordinator.status.value.dayId

        idleUntil(nextMidnight() + 3600_000L)

        val status = coordinator.status.value
        assertFalse(firstDay == status.dayId)
        assertEquals("the 90 s left unused yesterday", 90_000L, status.carriedInMs)
        assertEquals(allowanceMs + 90_000L, status.remainingMs)
        assertEquals("and it is fixed on the day's row", 90_000L, database.dao().day(status.dayId)?.carriedInMs)
    }

    @Test
    fun previewingAnAllowanceChangeKeepsTheCarriedTime() = runTest {
        usage.resume(instagram, clock.wallMs)
        runFor(30_000, steps = 30)
        usage.pause(instagram, clock.wallMs)
        usage.stop(instagram, clock.wallMs)
        idleUntil(nextMidnight() + 3600_000L)

        val (before, after) = coordinator.previewAllowanceChange(60_000L)
        assertEquals(allowanceMs + 90_000L, before)
        assertEquals("a new base does not discard what was carried in", 60_000L + 90_000L, after)
    }

    @Test
    fun unusedTimeKeepsBuildingUpAcrossSeveralDays() = runTest {
        idleUntil(nextMidnight() + 3600_000L)
        assertEquals(allowanceMs, coordinator.status.value.carriedInMs)

        idleUntil(nextMidnight() + 3600_000L)
        val status = coordinator.status.value
        assertEquals("two untouched days, and no cap", 2 * allowanceMs, status.carriedInMs)
        assertEquals(3 * allowanceMs, status.remainingMs)
    }

    @Test
    fun theCarryIsFixedOnlyOnceYesterdaysLastSecondsHaveSettled() = runTest {
        idleUntil(nextMidnight() - 60_000L)

        // Fifty seconds of use ending ten seconds before midnight.
        usage.resume(instagram, clock.wallMs)
        runFor(50_000, steps = 50)
        usage.pause(instagram, clock.wallMs)
        usage.stop(instagram, clock.wallMs)
        runFor(20_000, steps = 20)

        // It is now ten seconds past midnight, so yesterday's last half-minute has not settled.
        val early = coordinator.status.value
        assertEquals("the estimate already counts yesterday's unsettled tail", 70_000L, early.carriedInMs)
        assertEquals(allowanceMs + 70_000L, early.remainingMs)
        assertNull("but nothing is written while yesterday is still open", database.dao().day(early.dayId)?.carriedInMs)

        runFor(60_000, steps = 60)
        val settled = coordinator.status.value
        assertEquals(70_000L, database.dao().day(settled.dayId)?.carriedInMs)
        assertEquals(70_000L, settled.carriedInMs)
    }

    @Test
    fun anOutstandingRecoveryWhenYesterdaySettlesCarriesNothing() = runTest {
        // Nothing is used, but the usage source is lost for long enough to latch recovery.
        usage.available = false
        runFor(60_000)
        usage.available = true
        assertEquals(CheckpointState.UNCERTAIN, coordinator.status.value.checkpointState)

        idleUntil(nextMidnight() + 3600_000L)
        assertEquals(0L, coordinator.status.value.carriedInMs)

        val after = coordinator.acknowledgeRecovery()
        assertEquals("acknowledging later does not bring the withheld time back", 0L, after.carriedInMs)
        assertEquals(allowanceMs, after.remainingMs)
    }

    // -- F5: events that arrive late -------------------------------------------------------------

    @Test
    fun aLateArrivingPauseCorrectsTheChargeInsteadOfBeingIgnored() = runTest {
        usage.resume(instagram, clock.wallMs)
        runFor(10_000)

        // The user leaves the app now, but the events will not be queryable for another
        // few polls -- the case the reader's overlapping window exists for.
        usage.holdPause(instagram, clock.wallMs)
        usage.holdStop(instagram, clock.wallMs)

        runFor(5_000, steps = 5)
        usage.deliver()
        runFor(60_000, steps = 60)

        assertEquals(
            "only the time before they actually left is charged",
            10_000L,
            coordinator.status.value.chargedMs,
        )
        assertFalse(coordinator.status.value.targetVisible)
    }

    @Test
    fun deliveringTheSameEventsLateProducesTheSameDayTotal() = runTest {
        // Punctual delivery.
        usage.resume(instagram, clock.wallMs)
        runFor(30_000, steps = 30)
        usage.pause(instagram, clock.wallMs)
        usage.stop(instagram, clock.wallMs)
        runFor(60_000, steps = 60)
        val punctualTotal = coordinator.status.value.chargedMs

        // The same twenty seconds again, but with the exit delivered five polls late.
        usage.resume(instagram, clock.wallMs)
        runFor(30_000, steps = 30)
        usage.holdPause(instagram, clock.wallMs)
        usage.holdStop(instagram, clock.wallMs)
        runFor(5_000, steps = 5)
        usage.deliver()
        runFor(60_000, steps = 60)

        assertEquals(
            "a late delivery must not change what the day cost",
            punctualTotal * 2,
            coordinator.status.value.chargedMs,
        )
    }

    @Test
    fun replayingTheOverlapWindowNeverChargesAnIntervalTwice() = runTest {
        usage.resume(instagram, clock.wallMs)
        // One-second polls: the reader re-delivers a ten-second tail on every one of them.
        runFor(40_000, steps = 40)
        assertEquals(40_000L, coordinator.status.value.chargedMs)
    }

    // -- F8: the ledger records the original before it is changed ---------------------------------

    @Test
    fun theLedgerRecordsEachPackagesStateBeforeDoomStopTouchedIt() = runTest {
        // One browser was already suspended by somebody else before this app existed.
        val alreadySuspended = policy.installedBlockedBrowsers().first()
        forgetLedger()
        policy.suspended[alreadySuspended] = true

        restartProcess()
        coordinator.tick(Trigger.ADMIN_ACTION)

        val entries = database.dao().ledger().filter { it.key.startsWith(LimiterCoordinator.LEDGER_SUSPENDED_PREFIX) }
        assertTrue(entries.isNotEmpty())
        val recorded = entries.first { it.key.endsWith(alreadySuspended) }
        assertEquals(PreviousPolicyState.VALUE, recorded.previous)
        assertEquals("true", recorded.previousValue)

        val ours = entries.first { it.key.endsWith(instagram) }
        assertEquals(PreviousPolicyState.VALUE, ours.previous)
        assertEquals("false", ours.previousValue)
    }

    @Test
    fun theLedgerRecordsChromesOriginalBlocklistNotDoomStopsOwn() = runTest {
        forgetLedger()
        policy.chromeBlocklist = "[\"example.invalid\"]"
        restartProcess()
        coordinator.tick(Trigger.ADMIN_ACTION)

        val entry = database.dao().ledgerEntry(LimiterCoordinator.LEDGER_CHROME_BLOCKLIST)
        assertEquals(PreviousPolicyState.VALUE, entry?.previous)
        assertEquals("[\"example.invalid\"]", entry?.previousValue)
        assertTrue("and the policy really was applied over it", policy.chromeBlocklist!!.contains("instagram.com"))
    }

    // -- F7: a partial restore must not release ownership ------------------------------------------

    @Test
    fun aFailedUnsuspendStopsTheRestoreAndKeepsOwnership() = runTest {
        coordinator.tick(Trigger.ADMIN_ACTION)
        policy.failRelease = true

        val report = coordinator.restoreDevice(relinquishOwnership = true)

        assertFalse(report.completed)
        assertTrue(report.maintenanceRetained)
        assertNull("ownership must not even have been attempted", report.ownershipRelinquished)
        assertEquals(0, policy.relinquishCalls)
        assertTrue("still the device owner", policy.isDeviceOwner)
        assertTrue("and setup is not silently switched off", database.dao().settings()!!.setupCompleted)
    }

    @Test
    fun aFailedChromeRestoreStopsBeforeOwnershipIsReleased() = runTest {
        coordinator.tick(Trigger.ADMIN_ACTION)
        policy.failChromeRestore = true

        val report = coordinator.restoreDevice(relinquishOwnership = true)

        assertFalse(report.completed)
        assertEquals("Restore Chrome site policy", report.failures.single().name)
        assertEquals(0, policy.relinquishCalls)
        assertTrue(policy.isDeviceOwner)
    }

    @Test
    fun retryingAfterTheInjectedFailureIsClearedCompletesTheRestore() = runTest {
        coordinator.tick(Trigger.ADMIN_ACTION)
        policy.failChromeRestore = true
        assertFalse(coordinator.restoreDevice(relinquishOwnership = false).completed)

        policy.failChromeRestore = false
        val retry = coordinator.restoreDevice(relinquishOwnership = false)

        assertTrue(retry.failures.joinToString { it.name }, retry.completed)
        assertFalse(retry.maintenanceRetained)
        assertNull("Chrome's original had no blocklist, so the key is removed", policy.chromeBlocklist)
        assertFalse(database.dao().settings()!!.setupCompleted)
        assertFalse("this app's own uninstall protection is dropped too", policy.uninstallBlocked)
        assertTrue("ownership is kept when it was not asked for", policy.isDeviceOwner)
    }

    @Test
    fun aSuccessfulRestorePutsBackWhatWasThereBeforeAndOnlyThat() = runTest {
        val otherApp = "com.example.unrelated"
        forgetLedger()
        policy.chromeBlocklist = "[\"example.invalid\"]"
        policy.userControlDisabled = mutableListOf(otherApp)
        val alreadySuspended = policy.installedBlockedBrowsers().first()
        policy.suspended[alreadySuspended] = true

        restartProcess()
        coordinator.completeSetup()
        assertTrue(policy.uninstallBlocked)

        val report = coordinator.restoreDevice(relinquishOwnership = false)
        assertTrue(report.failures.joinToString { "${it.name}: ${it.detail}" }, report.completed)

        assertEquals("Chrome's own value is back", "[\"example.invalid\"]", policy.chromeBlocklist)
        assertEquals(
            "a package suspended before DoomStop existed stays suspended",
            true,
            policy.suspended[alreadySuspended],
        )
        assertEquals("and one this app suspended does not", false, policy.suspended[instagram])
        assertEquals(
            "an unrelated app's task-manager control is restored, not wiped",
            listOf(otherApp),
            policy.userControlDisabled,
        )
    }

    @Test
    fun packagesWhosePriorStateIsUnknownAreNotReleasedWithoutAnExplicitDecision() = runTest {
        forgetLedger()
        policy.unreadableSuspension = true
        restartProcess()
        coordinator.tick(Trigger.ADMIN_ACTION)

        val blocked = coordinator.restoreDevice(relinquishOwnership = false)
        assertFalse(blocked.completed)
        assertTrue(blocked.ambiguousPackages.isNotEmpty())

        val authorized = coordinator.restoreDevice(relinquishOwnership = false, releaseUnknownPackages = true)
        assertTrue(authorized.failures.joinToString { it.name }, authorized.completed)
    }

    @Test
    fun maintenanceModeStopsThePollLoopFightingTheRestore() = runTest {
        coordinator.tick(Trigger.ADMIN_ACTION)
        policy.failChromeRestore = true
        coordinator.restoreDevice(relinquishOwnership = false)

        // Whatever the monitor does next, it must not re-suspend what was just released.
        policy.suspended[instagram] = false
        runFor(60_000, steps = 4)
        assertEquals(false, policy.suspended[instagram])
        assertTrue(coordinator.status.value.maintenanceMode)
        assertEquals(EnforcementReason.MAINTENANCE, coordinator.status.value.enforcement.reason)

        coordinator.cancelMaintenance()
        assertFalse(coordinator.status.value.maintenanceMode)
    }

    // -- setup prerequisites --------------------------------------------------------------------

    @Test
    fun setupIsRefusedUntilItsPrerequisitesAreActuallyMet() = runTest {
        database.dao().upsertSettings(database.dao().settings()!!.copy(setupCompleted = false))
        policy.isDeviceOwner = false
        restartProcess()

        val refused = coordinator.completeSetup()
        assertFalse(refused.protectionActive)
        assertTrue(refused.missing.contains("Device owner"))
        assertFalse(database.dao().settings()!!.setupCompleted)

        policy.isDeviceOwner = true
        val outcome = coordinator.completeSetup()
        assertTrue(outcome.missing.joinToString(), outcome.missing.isEmpty())
        assertTrue(database.dao().settings()!!.setupCompleted)
        assertEquals(true, outcome.selfProtection?.uninstallBlocked)
    }

    @Test
    fun aFailedAntiRemovalControlIsNotHiddenBehindProtectionActive() = runTest {
        database.dao().upsertSettings(database.dao().settings()!!.copy(setupCompleted = false))
        policy.failProtectSelf = true
        restartProcess()

        val outcome = coordinator.completeSetup()
        assertFalse("uninstall protection failed, so this is not 'protected'", outcome.protectionActive)
        assertFalse(coordinator.status.value.protectionActive)
        assertTrue(
            coordinator.status.value.readiness
                .first { it.label == "This app cannot be uninstalled" }
                .satisfied
                .not()
        )
    }
}
