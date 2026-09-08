package dev.personal.doomstop.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.personal.doomstop.domain.CheckpointState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The schema change is a migration, not a reset.
 *
 * `fallbackToDestructiveMigration` is deliberately not used anywhere in this project,
 * because dropping the tables would erase the day's balance and hand back a fresh allowance.
 * This checks the upgrade actually works, and -- more importantly -- that an unresolved
 * history does not become resolved just because the app was updated.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val databaseName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LimiterDatabase::class.java,
    )

    @Test
    fun version1UpgradesWithoutLosingTheBalanceOrTheOutstandingRequest() = runTest {
        helper.createDatabase(databaseName, 1).use { db ->
            db.execSQL(
                "INSERT INTO settings (id, dailyAllowanceMs, extensionMs, zoneId, setupCompleted) " +
                    "VALUES (1, 1800000, 600000, 'Europe/Bucharest', 1)"
            )
            db.execSQL(
                "INSERT INTO day_budget (dayId, baseAllowanceMs, chargedMs, extraGrantedMs) " +
                    "VALUES ('2026-09-08', 1800000, 1234567, 600000)"
            )
            db.execSQL(
                "INSERT INTO checkpoint (id, bootId, lastElapsedMs, lastWallMs, targetVisible, " +
                    "usageCursorWallMs, state) VALUES (1, 'boot:4', 5000, 1788000000000, 1, " +
                    "1788000000000, 'UNCERTAIN')"
            )
            db.execSQL(
                "INSERT INTO pin_attempts (id, consecutiveFailures, lastFailureWallMs, nextAllowedWallMs) " +
                    "VALUES (1, 6, 1788000000000, 1788000060000)"
            )
            db.execSQL(
                "INSERT INTO policy_ledger (key, previousValue, appliedValue, appliedAtWallMs) " +
                    "VALUES ('suspended.com.instagram.android', NULL, 'true', 1788000000000)"
            )
        }

        val db = helper.runMigrationsAndValidate(databaseName, 2, true, LimiterDatabase.MIGRATION_1_2)
        db.close()

        val database = androidx.room.Room
            .databaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                LimiterDatabase::class.java,
                databaseName,
            )
            .addMigrations(*LimiterDatabase.MIGRATIONS)
            .build()

        try {
            val dao = database.dao()
            assertEquals(1_234_567L, dao.day("2026-09-08")?.chargedMs)
            assertEquals(600_000L, dao.day("2026-09-08")?.extraGrantedMs)
            assertEquals(true, dao.settings()?.setupCompleted)
            assertEquals("an upgrade must not start maintenance", false, dao.settings()?.maintenanceMode)

            val checkpoint = dao.checkpoint()?.toCheckpoint()
            assertNotNull(checkpoint)
            assertEquals(
                "an app update must not resolve a history that was never resolved",
                CheckpointState.UNCERTAIN,
                checkpoint?.state,
            )
            assertNotNull(checkpoint?.recovery)
            assertNull("no observer state existed in version 1", checkpoint?.trackerState)
            assertEquals(1_788_000_000_000L, checkpoint?.settledWallMs)

            val attempts = dao.pinAttempts()
            assertEquals("the cooldown counter survives", 6, attempts?.consecutiveFailures)
            assertEquals("", attempts?.lockBootId)

            val ledger = dao.ledgerEntry("suspended.com.instagram.android")
            assertEquals(
                "version 1 recorded null for both 'was not suspended' and 'could not tell', " +
                    "so the honest reading of an old row is that it is unknown",
                PreviousPolicyState.UNKNOWN,
                ledger?.previous,
            )

            assertTrue("the new table is usable", dao.pendingEvents().isEmpty())
        } finally {
            database.close()
        }
    }
}
