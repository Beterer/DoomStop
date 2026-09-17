package dev.personal.doomstop.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Local, transactional state. There is no server and no sync; this file is the whole
 * record of the user's balance, PIN verifier and applied policies.
 *
 * The database lives in CREDENTIAL-protected storage, which means it is unreadable until
 * the first unlock after boot. That is intentional: the PIN verifier must not be moved to
 * device-protected storage merely to make early boot easier. Early-boot enforcement uses
 * the small marker in [dev.personal.doomstop.data.BootMarkerStore] instead.
 *
 * Schemas are exported to app/schemas and committed, so migrations are written explicitly.
 * `fallbackToDestructiveMigration` is deliberately NOT used: silently dropping the table
 * would erase the day's balance and hand back a fresh allowance.
 */
@Database(
    entities = [
        SettingsEntity::class,
        DayBudgetEntity::class,
        CheckpointEntity::class,
        PendingEventEntity::class,
        PinVerifierEntity::class,
        PinAttemptsEntity::class,
        ExtensionGrantEntity::class,
        DiagnosticEventEntity::class,
        PolicyLedgerEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class LimiterDatabase : RoomDatabase() {

    abstract fun dao(): LimiterDao

    companion object {
        const val NAME = "doomstop.db"

        /**
         * Version 2 is the review fix set: a latched recovery request instead of a
         * recomputed CLEAN/UNCERTAIN flag, a settled accounting boundary with durable
         * observer state and retained events, a write-ahead policy ledger that distinguishes
         * "absent" from "unknown", monotonic PIN cooldowns, and a maintenance flag.
         *
         * An existing row is carried across rather than reset. A checkpoint that was
         * UNCERTAIN stays UNCERTAIN, because the whole point of the change is that an
         * unreconstructable interval is not cleared by anything except an authorized
         * acknowledgement -- least of all by an app update.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN maintenanceMode INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS checkpoint_v2 (
                        id INTEGER NOT NULL,
                        bootId TEXT NOT NULL,
                        lastElapsedMs INTEGER NOT NULL,
                        lastWallMs INTEGER NOT NULL,
                        settledWallMs INTEGER NOT NULL,
                        targetVisible INTEGER NOT NULL,
                        usageCursorWallMs INTEGER NOT NULL,
                        acceptedDriftMs INTEGER NOT NULL,
                        recoveryReason TEXT,
                        recoveryFromWallMs INTEGER NOT NULL,
                        recoveryToWallMs INTEGER NOT NULL,
                        recoveryDetectedAtWallMs INTEGER NOT NULL,
                        trackerState TEXT,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO checkpoint_v2 (
                        id, bootId, lastElapsedMs, lastWallMs, settledWallMs, targetVisible,
                        usageCursorWallMs, acceptedDriftMs, recoveryReason, recoveryFromWallMs,
                        recoveryToWallMs, recoveryDetectedAtWallMs, trackerState
                    )
                    SELECT
                        id, bootId, lastElapsedMs, lastWallMs, lastWallMs, targetVisible,
                        usageCursorWallMs, 0,
                        CASE WHEN state = 'UNCERTAIN'
                             THEN 'an unresolved gap was carried over from a previous version'
                             ELSE NULL END,
                        lastWallMs, lastWallMs, lastWallMs, NULL
                    FROM checkpoint
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE checkpoint")
                db.execSQL("ALTER TABLE checkpoint_v2 RENAME TO checkpoint")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_event (
                        eventKey TEXT NOT NULL,
                        timestampWallMs INTEGER NOT NULL,
                        packageName TEXT NOT NULL,
                        className TEXT,
                        type TEXT NOT NULL,
                        PRIMARY KEY(eventKey)
                    )
                    """.trimIndent()
                )

                db.execSQL("ALTER TABLE pin_attempts ADD COLUMN lockBootId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pin_attempts ADD COLUMN nextAllowedElapsedMs INTEGER NOT NULL DEFAULT 0")

                // Rows written by version 1 recorded previousValue = NULL for suspension
                // whether the package had been suspended before or not, so the honest
                // classification for every one of them is UNKNOWN rather than ABSENT.
                db.execSQL("ALTER TABLE policy_ledger ADD COLUMN previousState TEXT NOT NULL DEFAULT 'UNKNOWN'")
            }
        }

        /**
         * Version 3 carries unused time into the next day. Existing rows are left undecided
         * (NULL) rather than given a guessed value: the next pass decides today's carryover
         * from yesterday's row, and an older day's NULL reads as nothing carried in.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE day_budget ADD COLUMN carriedInMs INTEGER")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        fun build(context: Context): LimiterDatabase =
            Room.databaseBuilder(context.applicationContext, LimiterDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
