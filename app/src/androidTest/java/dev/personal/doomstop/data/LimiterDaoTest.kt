package dev.personal.doomstop.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.personal.doomstop.domain.CheckpointState
import dev.personal.doomstop.domain.DaySlice
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        val checkpoint = CheckpointEntity(
            bootId = "boot:1",
            lastElapsedMs = 1_000,
            lastWallMs = 2_000,
            targetVisible = true,
            usageCursorWallMs = 2_000,
            state = CheckpointState.CLEAN.name,
        )
        dao.applyAccounting(
            slices = listOf(DaySlice("2026-09-08", 60_000), DaySlice("2026-09-09", 30_000)),
            baseAllowanceMs = allowanceMs,
            checkpoint = checkpoint,
        )
        dao.applyAccounting(
            slices = listOf(DaySlice("2026-09-08", 15_000)),
            baseAllowanceMs = allowanceMs,
            checkpoint = checkpoint.copy(lastElapsedMs = 2_000),
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
            checkpoint = CheckpointEntity(
                bootId = "boot:1",
                lastElapsedMs = 0,
                lastWallMs = 0,
                targetVisible = false,
                usageCursorWallMs = 0,
                state = CheckpointState.CLEAN.name,
            ),
        )
        assertEquals(null, dao.day("2026-09-08"))
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

    // -- recovery ------------------------------------------------------------------------------

    @Test
    fun checkpointStateSurvivesAndRoundTrips() = runTest {
        val uncertain = CheckpointEntity(
            bootId = "boot:9",
            lastElapsedMs = 500,
            lastWallMs = 1_500,
            targetVisible = true,
            usageCursorWallMs = 1_400,
            state = CheckpointState.UNCERTAIN.name,
        )
        dao.upsertCheckpoint(uncertain)
        assertEquals(CheckpointState.UNCERTAIN, dao.checkpoint()?.toCheckpoint()?.state)
    }

    @Test
    fun anUnreadableCheckpointStateFailsSafeToUncertain() = runTest {
        dao.upsertCheckpoint(
            CheckpointEntity(
                bootId = "boot:9",
                lastElapsedMs = 0,
                lastWallMs = 0,
                targetVisible = false,
                usageCursorWallMs = 0,
                state = "GARBAGE_FROM_A_FUTURE_VERSION",
            )
        )
        // Failing closed matters: an unreadable history must suspend, not resume.
        assertEquals(CheckpointState.UNCERTAIN, dao.checkpoint()?.toCheckpoint()?.state)
    }

    // -- ledger ---------------------------------------------------------------------------------

    @Test
    fun theLedgerRemembersChromesPreviousValue() = runTest {
        dao.upsertLedgerEntry(
            PolicyLedgerEntity(
                key = "chrome.URLBlocklist",
                previousValue = null,
                appliedValue = "[\"instagram.com\"]",
                appliedAtWallMs = 10L,
            )
        )
        val entry = dao.ledgerEntry("chrome.URLBlocklist")
        assertNotNull(entry)
        assertEquals(null, entry?.previousValue)
        assertEquals(1, dao.ledger().size)
    }
}
