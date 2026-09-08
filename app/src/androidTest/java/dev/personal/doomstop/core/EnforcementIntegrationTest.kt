package dev.personal.doomstop.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.personal.doomstop.admin.PolicyController
import dev.personal.doomstop.config.TargetPackages
import dev.personal.doomstop.data.BootMarkerStore
import dev.personal.doomstop.data.LimiterDatabase
import dev.personal.doomstop.data.SettingsEntity
import dev.personal.doomstop.domain.ClockSource
import dev.personal.doomstop.domain.EnforcementReason
import dev.personal.doomstop.monitor.TrackedEvent
import dev.personal.doomstop.monitor.TrackedEventType
import dev.personal.doomstop.monitor.UsageReadResult
import dev.personal.doomstop.monitor.UsageSource
import dev.personal.doomstop.security.AuthorizationTicket
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A clock the test owns outright, so a whole day can pass between two statements. */
private class FakeClock(
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

/** A scripted usage-event stream. */
private class FakeUsageSource : UsageSource {
    var available = true
    private val events = mutableListOf<TrackedEvent>()

    fun resume(pkg: String, atWallMs: Long) = add(pkg, atWallMs, TrackedEventType.ACTIVITY_RESUMED)
    fun pause(pkg: String, atWallMs: Long) = add(pkg, atWallMs, TrackedEventType.ACTIVITY_PAUSED)
    fun stop(pkg: String, atWallMs: Long) = add(pkg, atWallMs, TrackedEventType.ACTIVITY_STOPPED)

    private fun add(pkg: String, atWallMs: Long, type: TrackedEventType) {
        events += TrackedEvent(atWallMs, pkg, "$pkg.MainActivity", type)
    }

    override fun hasUsageAccess(): Boolean = available

    override fun read(sinceWallMs: Long, nowWallMs: Long): UsageReadResult {
        if (!available) return UsageReadResult(emptyList(), false, sinceWallMs, "usage access not granted")
        // Inclusive lower bound, matching the real reader's overlapping query window: an
        // event exactly at the cursor must still be delivered, and re-delivery is harmless
        // because the tracker collapses transitions that change nothing.
        val window = events.filter { it.timestampWallMs >= sinceWallMs && it.timestampWallMs <= nowWallMs }
        return UsageReadResult(window, true, nowWallMs)
    }
}

/**
 * End-to-end: scripted usage against a fake clock, but REAL device-owner enforcement.
 *
 * This is the acceptance matrix's short-allowance run (60 s) executed without waiting 60
 * seconds, and with the suspension state read back from the platform rather than inferred
 * from the coordinator's own opinion of itself.
 */
@RunWith(AndroidJUnit4::class)
class EnforcementIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: LimiterDatabase
    private lateinit var policy: PolicyController
    private lateinit var clock: FakeClock
    private lateinit var usage: FakeUsageSource
    private lateinit var coordinator: LimiterCoordinator

    private val allowanceMs = 60_000L
    private val extensionMs = 10_000L

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        policy = PolicyController(context)
        assumeTrue("requires a provisioned device owner", policy.isDeviceOwner)
        assumeTrue("requires the target packages to be installed", policy.installedTargets().isNotEmpty())

        database = Room.inMemoryDatabaseBuilder(context, LimiterDatabase::class.java).build()
        clock = FakeClock()
        usage = FakeUsageSource()
        coordinator = LimiterCoordinator(
            context = context,
            dao = database.dao(),
            policy = policy,
            reader = usage,
            bootMarker = BootMarkerStore(context),
            clock = clock,
            deadlines = DeadlineScheduler(context),
        )
        coordinator.serviceRunning = true

        database.dao().upsertSettings(
            SettingsEntity(
                dailyAllowanceMs = allowanceMs,
                extensionMs = extensionMs,
                zoneId = ZoneId.of("Europe/Bucharest").id,
                setupCompleted = true,
            )
        )
        // Establish the checkpoint at "now" so the first tick has a clean anchor.
        coordinator.tick(Trigger.UI)
    }

    @After
    fun tearDown() {
        if (this::policy.isInitialized && policy.isDeviceOwner) policy.release(policy.manageablePackages())
        if (this::database.isInitialized) database.close()
    }

    /** Advance the fake clock and run a tick, the way the poll loop would. */
    private suspend fun runFor(ms: Long) {
        clock.advance(ms)
        coordinator.tick(Trigger.POLL)
    }

    @Test
    fun usageAcrossAllThreeAppsSharesOneAllowanceAndThenSuspendsThem() = runTest {
        // Instagram for 20 s.
        usage.resume(TargetPackages.INSTAGRAM, clock.wallMs)
        runFor(20_000)
        var status = coordinator.status.value
        assertEquals(20_000L, status.chargedMs)
        assertFalse("time remains, nothing should be suspended", status.enforcement.suspendTargets)

        // Switch to Reddit for 20 s. The shared budget keeps counting; it does not restart.
        usage.pause(TargetPackages.INSTAGRAM, clock.wallMs)
        usage.resume(TargetPackages.REDDIT, clock.wallMs)
        usage.stop(TargetPackages.INSTAGRAM, clock.wallMs)
        runFor(20_000)
        assertEquals(40_000L, coordinator.status.value.chargedMs)

        // Then TikTok for the last 20 s, which exhausts the shared allowance.
        usage.pause(TargetPackages.REDDIT, clock.wallMs)
        usage.resume(TargetPackages.TIKTOK, clock.wallMs)
        usage.stop(TargetPackages.REDDIT, clock.wallMs)
        runFor(20_000)

        status = coordinator.status.value
        assertEquals(60_000L, status.chargedMs)
        assertEquals(0L, status.remainingMs)
        assertTrue(status.enforcement.suspendTargets)
        assertEquals(EnforcementReason.ALLOWANCE_EXHAUSTED, status.enforcement.reason)

        // The claim that matters: the PLATFORM says they are suspended.
        for (packageName in policy.installedTargets()) {
            assertEquals(
                "$packageName should be suspended on the device",
                true,
                status.suspension?.outcomes?.first { it.packageName == packageName }?.actualSuspended,
            )
        }
    }

    @Test
    fun timeOnTheHomeScreenIsNotCharged() = runTest {
        usage.resume(TargetPackages.INSTAGRAM, clock.wallMs)
        runFor(10_000)
        assertEquals(10_000L, coordinator.status.value.chargedMs)

        usage.pause(TargetPackages.INSTAGRAM, clock.wallMs)
        usage.stop(TargetPackages.INSTAGRAM, clock.wallMs)
        runFor(30_000)
        assertEquals("no debit while away from the app", 10_000L, coordinator.status.value.chargedMs)
    }

    @Test
    fun twoTargetsVisibleAtOnceConsumeOneSecondPerSecond() = runTest {
        usage.resume(TargetPackages.INSTAGRAM, clock.wallMs)
        usage.resume(TargetPackages.REDDIT, clock.wallMs) // split screen, multi-resume
        runFor(15_000)
        assertEquals(15_000L, coordinator.status.value.chargedMs)
    }

    @Test
    fun oneAuthorizationGrantsExactlyOneExtensionHoweverOftenItIsReplayed() = runTest {
        usage.resume(TargetPackages.INSTAGRAM, clock.wallMs)
        runFor(allowanceMs)
        assertTrue(coordinator.status.value.enforcement.suspendTargets)

        val ticket = AuthorizationTicket("integration-ticket", clock.wallMs)
        assertTrue("first redemption grants", coordinator.grantExtension(ticket))
        assertFalse("a repeated tap grants nothing", coordinator.grantExtension(ticket))
        assertFalse("nor does a third", coordinator.grantExtension(ticket))

        val status = coordinator.status.value
        assertEquals(extensionMs, status.extraGrantedMs)
        assertEquals(extensionMs, status.remainingMs)
        assertFalse("apps are available again", status.enforcement.suspendTargets)
        for (packageName in policy.installedTargets()) {
            assertEquals(
                false,
                status.suspension?.outcomes?.first { it.packageName == packageName }?.actualSuspended,
            )
        }

        // ...and the websites are NOT unblocked by an extension.
        assertTrue("Chrome blocklist must survive an extension", status.chrome?.storedPolicyVerified == true)
    }

    @Test
    fun theExtensionRunsOutAndSuspendsAgain() = runTest {
        usage.resume(TargetPackages.INSTAGRAM, clock.wallMs)
        runFor(allowanceMs)
        coordinator.grantExtension(AuthorizationTicket("t1", clock.wallMs))
        assertFalse(coordinator.status.value.enforcement.suspendTargets)

        runFor(extensionMs)
        assertTrue(coordinator.status.value.enforcement.suspendTargets)
        assertEquals(allowanceMs + extensionMs, coordinator.status.value.chargedMs)
    }

    @Test
    fun crossingMidnightResetsExactlyOnceAndKeepsYesterdaysUsage() = runTest {
        usage.resume(TargetPackages.INSTAGRAM, clock.wallMs)
        runFor(allowanceMs)
        val exhaustedDay = coordinator.status.value.dayId
        assertTrue(coordinator.status.value.enforcement.suspendTargets)

        // Jump past local midnight.
        usage.pause(TargetPackages.INSTAGRAM, clock.wallMs)
        usage.stop(TargetPackages.INSTAGRAM, clock.wallMs)
        runFor(14 * 60 * 60_000L)

        val newDay = coordinator.status.value
        assertFalse("a new day is a new allowance", newDay.dayId == exhaustedDay)
        assertEquals(0L, newDay.chargedMs)
        assertEquals(allowanceMs, newDay.remainingMs)
        assertFalse(newDay.enforcement.suspendTargets)

        // Yesterday's usage is still on record, so winding back grants nothing.
        assertEquals(allowanceMs, database.dao().day(exhaustedDay)?.chargedMs)
    }

    @Test
    fun changingTheDateDoesNotGrantASecondAllowance() = runTest {
        usage.resume(TargetPackages.INSTAGRAM, clock.wallMs)
        runFor(allowanceMs)
        val day = coordinator.status.value.dayId
        assertTrue(coordinator.status.value.enforcement.suspendTargets)

        // Put the phone down first, so the jump itself is the only thing under test.
        usage.pause(TargetPackages.INSTAGRAM, clock.wallMs)
        usage.stop(TargetPackages.INSTAGRAM, clock.wallMs)
        runFor(1_000)

        // Set the date forward past midnight while a second of real time passes. The jump
        // is refused outright, so the accounting day does not move and no unvisited date is
        // reached; the apps stay suspended until the PIN holder resolves it.
        clock.wallMs += 14 * 60 * 60_000L
        clock.elapsedMs += 1_000
        coordinator.tick(Trigger.POLL)

        var status = coordinator.status.value
        assertEquals("the accounting day did not move", day, status.dayId)
        assertEquals(0L, status.remainingMs)
        assertTrue("still suspended", status.enforcement.suspendTargets)
        assertEquals(EnforcementReason.RECOVERY_REQUIRED, status.enforcement.reason)

        // Putting it back does not help either.
        clock.wallMs -= 14 * 60 * 60_000L
        clock.elapsedMs += 1_000
        coordinator.tick(Trigger.POLL)
        status = coordinator.status.value
        assertEquals(day, status.dayId)
        assertTrue(status.enforcement.suspendTargets)
    }

    @Test
    fun losingUsageAccessSuspendsEvenThoughTimeRemains() = runTest {
        usage.resume(TargetPackages.INSTAGRAM, clock.wallMs)
        runFor(5_000)
        assertFalse(coordinator.status.value.enforcement.suspendTargets)

        usage.available = false
        runFor(1_000)

        val status = coordinator.status.value
        assertTrue("a blind monitor must not leave the apps open", status.enforcement.suspendTargets)
        for (packageName in policy.installedTargets()) {
            assertEquals(
                true,
                status.suspension?.outcomes?.first { it.packageName == packageName }?.actualSuspended,
            )
        }
    }

    @Test
    fun aRebootPreservesTheBalanceAndDoesNotGrantAFreshDay() = runTest {
        usage.resume(TargetPackages.INSTAGRAM, clock.wallMs)
        runFor(40_000)
        assertEquals(40_000L, coordinator.status.value.chargedMs)

        // Reboot: monotonic clock restarts, wall clock carries on, boot identity changes.
        usage.pause(TargetPackages.INSTAGRAM, clock.wallMs)
        usage.stop(TargetPackages.INSTAGRAM, clock.wallMs)
        clock.wallMs += 120_000
        clock.elapsedMs = 5_000
        clock.boot = "boot:2"
        coordinator.tick(Trigger.BOOT)

        assertEquals("the balance survives", 40_000L, coordinator.status.value.chargedMs)
        assertEquals(20_000L, coordinator.status.value.remainingMs)

        // And the remaining 20 s still runs out normally.
        usage.resume(TargetPackages.INSTAGRAM, clock.wallMs)
        runFor(20_000)
        assertTrue(coordinator.status.value.enforcement.suspendTargets)
    }
}
