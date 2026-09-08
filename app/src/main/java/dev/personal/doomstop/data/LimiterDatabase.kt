package dev.personal.doomstop.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
        PinVerifierEntity::class,
        PinAttemptsEntity::class,
        ExtensionGrantEntity::class,
        DiagnosticEventEntity::class,
        PolicyLedgerEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LimiterDatabase : RoomDatabase() {

    abstract fun dao(): LimiterDao

    companion object {
        const val NAME = "doomstop.db"

        fun build(context: Context): LimiterDatabase =
            Room.databaseBuilder(context.applicationContext, LimiterDatabase::class.java, NAME)
                .build()
    }
}
