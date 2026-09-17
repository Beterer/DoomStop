package dev.personal.doomstop.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.personal.doomstop.domain.CheckpointState
import dev.personal.doomstop.domain.DaySlice
import dev.personal.doomstop.domain.RecoveryRequest
import dev.personal.doomstop.monitor.TrackedEvent
import dev.personal.doomstop.monitor.TrackedEventType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Database-level guarantees that cannot be checked with a fake: idempotent grants, the
 * atomic accounting transaction, and rollover being a consequence of the primary key
 * rather than of any scheduled job.
 */
@RunWith(AndroidJUnit4::class)
class LimiterDaoTest {

    private lateinit var database: LimiterDatabase
    private lateinit var dao: LimiterDao

    private val allowanceMs = 30L * 60_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LimiterDatabase::class.java,
        ).build()
        dao = database.dao()
    }

    @After
    fun tearDown() = database.close()

    private fun checkpoint(
        elapsedMs: Long = 1_000,
        wallMs: Long = 2_000,
        settledMs: Long = wallMs,
        visible: Boolean = true,
        recoveryReason: String? = null,
        trackerState: String? = null,
    ) = CheckpointEntity(
        bootId = "boot:1",
        lastElapsedMs = elapsedMs,
        lastWallMs = wallMs,
        settledWallMs = settledMs,
        targetVisible = visible,
        usageCursorWallMs = wallMs,
        acceptedDriftMs = 0,
        recoveryReason = recoveryReason,
        recoveryFromWallMs = 0,
        recoveryToWallMs = 0,
        recoveryDetectedAtWallMs = 0,
        trackerState = trackerState,
    )

    private fun event(t: Long, type: TrackedEventType, pkg: String = "com.instagram.android") =
        PendingEventEntity.from(TrackedEvent(t, pkg, "$pkg.MainActivity", type))

    // -- grants -----------------------------------------------------------------------------

    @Test
    fun redeemingTheSameTokenTwiceGrantsTimeOnce() = runTest {
        val token = "ticket-1"
        assertTrue(dao.redeemExtension(token, "2026-09-08", allowanceMs, 600_000L, 0L))
        // A double tap, a retried coroutine, or a replay after process death.
        assertFalse(dao.redeemExtension(token, "2026-09-08", allowanceMs, 600_000L, 0L))
        assertFalse(dao.redeemExtension(token, "2026-09-08", allowanceMs, 600_000L, 0L))

        assertEquals(600_000L, dao.day("2026-09-08")?.extraGrantedMs)
        assertEquals(1, dao.grantsForDay("2026-09-08").size)
    }

    @Test
    fun distinctTokensEachGrantOneExtension() = runTest {
        assertTrue(dao.redeemExtension("a", "2026-09-08", allowanceMs, 600_000L, 0L))
        assertTrue(dao.redeemExtension("b", "2026-09-08", allowanceMs, 600_000L, 0L))
        assertEquals(1_200_000L, dao.day("2026-09-08")?.extraGrantedMs)
    }

    @Test
    fun extraTimeDoesNotLeakIntoTheNextDay() = runTest {
        dao.redeemExtension("a", "2026-09-08", allowanceMs, 600_000L, 0L)
        val tomorrow = dao.dayOrCreate("2026-09-09", allowanceMs)
        assertEquals(0L, tomorrow.extraGrantedMs)
        assertEquals(allowanceMs, tomorrow.baseAllowanceMs)
    }

    // -- accounting ---------------------------------------------------------------------------

    @Test
    fun accountingCreatesMissingDaysAndAccumulatesCharges() = runTest {
        dao.applyAccounting(
            slices = listOf(DaySlice("2026-09-08", 60_000), DaySlice("2026-09-09", 30_000)),
            baseAllowanceMs = allowanceMs,
            checkpoint = checkpoint(),
            retainedEvents = emptyList(),
            settledThroughWallMs = 2_000,
        )
        dao.applyAccounting(
            slices = listOf(DaySlice("2026-09-08", 15_000)),
            baseAllowanceMs = allowanceMs,
            checkpoint = checkpoint(elapsedMs = 2_000),
            retainedEvents = emptyList(),
            settledThroughWallMs = 2_000,
        )

        assertEquals(75_000L, dao.day("2026-09-08")?.chargedMs)
        assertEquals(30_000L, dao.day("2026-09-09")?.chargedMs)
        assertEquals(2_000L, dao.checkpoint()?.lastElapsedMs)
    }

    @Test
    fun zeroLengthSlicesCreateNothing() = runTest {
        dao.applyAccounting(
            slices = listOf(DaySlice("2026-09-08", 0)),
            baseAllowanceMs = allowanceMs,
            checkpoint = checkpoint(elapsedMs = 0, wallMs = 0, visible = false),
            retainedEvents = emptyList(),
            settledThroughWallMs = 0,
        )
        assertEquals(null, dao.day("2026-09-08"))
    }

    // -- the window that is still open for correction --------------------------------------

    @Test
    fun eventsAreRetainedUntilTheirIntervalIsSettledAndThenDropped() = runTest {
        dao.applyAccounting(
            slices = emptyList(),
            baseAllowanceMs = allowanceMs,
            checkpoint = checkpoint(wallMs = 10_000, settledMs = 5_000),
            retainedEvents = listOf(
                event(4_000, TrackedEventType.ACTIVITY_RESUMED),
                event(6_000, TrackedEventType.ACTIVITY_PAUSED),
                event(8_000, TrackedEventType.ACTIVITY_STOPPED),
            ),
            settledThroughWallMs = 5_000,
        )
        // The one at 4 s belongs to an interval that has just been charged; it goes.
        assertEquals(listOf(6_000L, 8_000L), dao.pendingEvents().map { it.timestampWallMs })

        dao.applyAccounting(
            slices = emptyList(),
            baseAllowanceMs = allowanceMs,
            checkpoint = checkpoint(wallMs = 12_000, settledMs = 7_000),
            retainedEvents = emptyList(),
            settledThroughWallMs = 7_000,
        )
        assertEquals(listOf(8_000L), dao.pendingEvents().map { it.timestampWallMs })
    }

    @Test
    fun anEventDeliveredTwiceIsStoredOnce() = runTest {
        val duplicate = event(6_000, TrackedEventType.ACTIVITY_RESUMED)
        dao.insertPendingEvents(listOf(duplicate))
        dao.insertPendingEvents(listOf(duplicate, duplicate))
        assertEquals(1, dao.pendingEvents().size)
    }

    @Test
    fun aPendingEventRoundTripsBackIntoTheObserversInput() = runTest {
        dao.insertPendingEvents(listOf(event(6_000, TrackedEventType.ACTIVITY_PAUSED)))
        val restored = dao.pendingEvents().single().toTrackedEvent()
        assertNotNull(restored)
        assertEquals(TrackedEventType.ACTIVITY_PAUSED, restored!!.type)
        assertEquals(6_000L, restored.timestampWallMs)
    }

    // -- rollover ------------------------------------------------------------------------------

    @Test
    fun revisitingAnEarlierDayDoesNotGrantAFreshAllowance() = runTest {
        // Use up today.
        dao.dayOrCreate("2026-09-08", allowanceMs)
        dao.addCharge("2026-09-08", allowanceMs)
        assertTrue(dao.day("2026-09-08")!!.toDayBudget().isExhausted)

        // Roll over to a new day: a fresh, untouched allowance.
        val next = dao.dayOrCreate("2026-09-09", allowanceMs)
        assertEquals(0L, next.chargedMs)

        // Wind the clock back. The day row already exists, so its usage comes back with it
        // -- which is what makes "back and forth over midnight" worthless.
        val revisited = dao.dayOrCreate("2026-09-08", allowanceMs)
        assertEquals(allowanceMs, revisited.chargedMs)
        assertTrue(revisited.toDayBudget().isExhausted)
    }

    @Test
    fun changingTodaysAllowanceKeepsTimeAlreadyCharged() = runTest {
        dao.dayOrCreate("2026-09-08", allowanceMs)
        dao.addCharge("2026-09-08", 25L * 60_000L)
        dao.setBaseAllowance("2026-09-08", 10L * 60_000L)

        val day = dao.day("2026-09-08")!!.toDayBudget()
        assertEquals(25L * 60_000L, day.chargedMs)
        assertEquals(0L, day.remainingMs)
        assertTrue(day.isExhausted)
    }

    @Test
    fun carriedInTimeIsFixedOnceAndNeverRewritten() = runTest {
        val created = dao.dayOrCreate("2026-09-09", allowanceMs)
        assertNull("a new day's carryover is undecided until yesterday has settled", created.carriedInMs)

        dao.fixCarriedIn("2026-09-09", 20L * 60_000L)
        dao.fixCarriedIn("2026-09-09", 50L * 60_000L)

        val day = dao.day("2026-09-09")!!.toDayBudget()
        assertEquals("the first decision stands", 20L * 60_000L, day.carriedInMs)
        assertEquals(allowanceMs + 20L * 60_000L, day.remainingMs)
    }

    // -- recovery ------------------------------------------------------------------------------

    @Test
    fun anOutstandingRecoveryRequestSurvivesWithItsReasonAndRange() = runTest {
        dao.upsertCheckpoint(
            CheckpointEntity.from(
                checkpoint().toCheckpoint().copy(
                    recovery = RecoveryRequest(
                        reason = "usage events were unreadable",
                        fromWallMs = 1_000,
                        toWallMs = 61_000,
                        detectedAtWallMs = 61_000,
                    )
                )
            )
        )
        val restored = dao.checkpoint()?.toCheckpoint()
        assertEquals(CheckpointState.UNCERTAIN, restored?.state)
        assertEquals("usage events were unreadable", restored?.recovery?.reason)
        assertEquals(60_000L, restored?.recovery?.gapMs)
    }

    @Test
    fun aCheckpointWithNoRequestIsClean() = runTest {
        dao.upsertCheckpoint(checkpoint())
        val restored = dao.checkpoint()?.toCheckpoint()
        assertEquals(CheckpointState.CLEAN, restored?.state)
        assertNull(restored?.recovery)
    }

    @Test
    fun observerStateIsStoredWithTheCheckpointItBelongsTo() = runTest {
        dao.upsertCheckpoint(checkpoint(trackerState = "11050001"))
        assertEquals("11050001", dao.checkpoint()?.trackerState)
    }

    // -- ledger ---------------------------------------------------------------------------------

    @Test
    fun theLedgerDistinguishesAnAbsentValueFromAnUnknownOne() = runTest {
        dao.upsertLedgerEntry(
            PolicyLedgerEntity(
                key = "chrome.URLBlocklist",
                previousState = PreviousPolicyState.ABSENT.name,
                previousValue = null,
                appliedValue = "[\"instagram.com\"]",
                appliedAtWallMs = 10L,
            )
        )
        dao.upsertLedgerEntry(
            PolicyLedgerEntity(
                key = "suspended.com.example.browser",
                previousState = PreviousPolicyState.UNKNOWN.name,
                previousValue = null,
                appliedValue = null,
                appliedAtWallMs = 10L,
            )
        )

        // Both have a null value; only one of them justifies removing the setting.
        assertEquals(PreviousPolicyState.ABSENT, dao.ledgerEntry("chrome.URLBlocklist")?.previous)
        assertEquals(PreviousPolicyState.UNKNOWN, dao.ledgerEntry("suspended.com.example.browser")?.previous)
        assertEquals(2, dao.ledger().size)
    }
}
