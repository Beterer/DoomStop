package dev.personal.doomstop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.personal.doomstop.domain.DaySlice
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LimiterDao {

    // -- settings ----------------------------------------------------------------------

    @Query("SELECT * FROM settings WHERE id = 1")
    abstract suspend fun settings(): SettingsEntity?

    @Query("SELECT * FROM settings WHERE id = 1")
    abstract fun settingsFlow(): Flow<SettingsEntity?>

    @Upsert
    abstract suspend fun upsertSettings(settings: SettingsEntity)

    // -- day budgets -------------------------------------------------------------------

    @Query("SELECT * FROM day_budget WHERE dayId = :dayId")
    abstract suspend fun day(dayId: String): DayBudgetEntity?

    @Query("SELECT * FROM day_budget WHERE dayId = :dayId")
    abstract fun dayFlow(dayId: String): Flow<DayBudgetEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertDayIfAbsent(day: DayBudgetEntity): Long

    @Upsert
    abstract suspend fun upsertDay(day: DayBudgetEntity)

    @Query("UPDATE day_budget SET chargedMs = chargedMs + :deltaMs WHERE dayId = :dayId")
    abstract suspend fun addCharge(dayId: String, deltaMs: Long)

    @Query("UPDATE day_budget SET extraGrantedMs = extraGrantedMs + :deltaMs WHERE dayId = :dayId")
    abstract suspend fun addExtra(dayId: String, deltaMs: Long)

    @Query("UPDATE day_budget SET baseAllowanceMs = :baseMs WHERE dayId = :dayId")
    abstract suspend fun setBaseAllowance(dayId: String, baseMs: Long)

    /** Decide a day's carried-in time. Only the first decision lands; later calls change nothing. */
    @Query("UPDATE day_budget SET carriedInMs = :carriedInMs WHERE dayId = :dayId AND carriedInMs IS NULL")
    abstract suspend fun fixCarriedIn(dayId: String, carriedInMs: Long)

    @Query("DELETE FROM day_budget WHERE dayId < :oldestDayIdToKeep")
    abstract suspend fun pruneDaysBefore(oldestDayIdToKeep: String)

    // -- checkpoint --------------------------------------------------------------------

    @Query("SELECT * FROM checkpoint WHERE id = 1")
    abstract suspend fun checkpoint(): CheckpointEntity?

    @Query("SELECT * FROM checkpoint WHERE id = 1")
    abstract fun checkpointFlow(): Flow<CheckpointEntity?>

    @Upsert
    abstract suspend fun upsertCheckpoint(checkpoint: CheckpointEntity)

    // -- pending (not yet settled) usage events -----------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPendingEvents(events: List<PendingEventEntity>)

    @Query("SELECT * FROM pending_event ORDER BY timestampWallMs ASC")
    abstract suspend fun pendingEvents(): List<PendingEventEntity>

    @Query("DELETE FROM pending_event WHERE timestampWallMs <= :settledWallMs")
    abstract suspend fun prunePendingEventsThrough(settledWallMs: Long)

    @Query("DELETE FROM pending_event")
    abstract suspend fun clearPendingEvents()

    // -- PIN ---------------------------------------------------------------------------

    @Query("SELECT * FROM pin_verifier WHERE id = 1")
    abstract suspend fun pinVerifier(): PinVerifierEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM pin_verifier WHERE id = 1)")
    abstract fun pinIsSetFlow(): Flow<Boolean>

    @Upsert
    abstract suspend fun upsertPinVerifier(verifier: PinVerifierEntity)

    @Query("SELECT * FROM pin_attempts WHERE id = 1")
    abstract suspend fun pinAttempts(): PinAttemptsEntity?

    @Upsert
    abstract suspend fun upsertPinAttempts(attempts: PinAttemptsEntity)

    // -- extensions --------------------------------------------------------------------

    /** Returns -1 when the token was already redeemed, which is what makes grants idempotent. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertGrantIfAbsent(grant: ExtensionGrantEntity): Long

    @Query("SELECT * FROM extension_grant WHERE dayId = :dayId ORDER BY grantedAtWallMs DESC")
    abstract suspend fun grantsForDay(dayId: String): List<ExtensionGrantEntity>

    // -- diagnostics -------------------------------------------------------------------

    @Insert
    abstract suspend fun insertDiagnostic(event: DiagnosticEventEntity)

    @Query("SELECT * FROM diagnostic_event ORDER BY atWallMs DESC LIMIT :limit")
    abstract fun recentDiagnosticsFlow(limit: Int): Flow<List<DiagnosticEventEntity>>

    @Query("DELETE FROM diagnostic_event WHERE atWallMs < :beforeWallMs")
    abstract suspend fun pruneDiagnosticsBefore(beforeWallMs: Long)

    // -- policy ledger -----------------------------------------------------------------

    @Upsert
    abstract suspend fun upsertLedgerEntry(entry: PolicyLedgerEntity)

    @Query("SELECT * FROM policy_ledger WHERE key = :key")
    abstract suspend fun ledgerEntry(key: String): PolicyLedgerEntity?

    @Query("SELECT * FROM policy_ledger")
    abstract suspend fun ledger(): List<PolicyLedgerEntity>

    @Query("DELETE FROM policy_ledger WHERE key = :key")
    abstract suspend fun deleteLedgerEntry(key: String)

    // -- composite transactions --------------------------------------------------------

    /**
     * Persist an accounting pass atomically: create any missing day rows with the base
     * allowance in force, add each slice's charge, retain the events that belong to the
     * window still open for correction, drop the ones the new checkpoint has just made
     * permanent, and store that checkpoint.
     *
     * All five happen in one transaction because they are one fact. The observer state and
     * the retained events are exactly what the next process needs in order to recompute the
     * unsettled tail identically; committing the checkpoint without them would recreate the
     * bug where a restart forgot that an app was open.
     *
     * Accounting is persisted BEFORE the caller decides whether to keep targets available,
     * so a crash between the two can only ever err toward having charged time.
     */
    @Transaction
    open suspend fun applyAccounting(
        slices: List<DaySlice>,
        baseAllowanceMs: Long,
        checkpoint: CheckpointEntity,
        retainedEvents: List<PendingEventEntity>,
        settledThroughWallMs: Long,
    ) {
        for (slice in slices) {
            if (slice.durationMs <= 0) continue
            insertDayIfAbsent(
                DayBudgetEntity(
                    dayId = slice.dayId,
                    baseAllowanceMs = baseAllowanceMs,
                    chargedMs = 0,
                    extraGrantedMs = 0,
                )
            )
            addCharge(slice.dayId, slice.durationMs)
        }
        if (retainedEvents.isNotEmpty()) insertPendingEvents(retainedEvents)
        prunePendingEventsThrough(settledThroughWallMs)
        upsertCheckpoint(checkpoint)
    }

    /**
     * Redeem one authorization token. Returns true only the first time a given token is
     * seen, so repeated taps, retries and replays after process death add no extra time.
     */
    @Transaction
    open suspend fun redeemExtension(
        token: String,
        dayId: String,
        baseAllowanceMs: Long,
        extensionMs: Long,
        nowWallMs: Long,
    ): Boolean {
        insertDayIfAbsent(
            DayBudgetEntity(
                dayId = dayId,
                baseAllowanceMs = baseAllowanceMs,
                chargedMs = 0,
                extraGrantedMs = 0,
            )
        )
        val inserted = insertGrantIfAbsent(
            ExtensionGrantEntity(
                token = token,
                dayId = dayId,
                grantedMs = extensionMs,
                grantedAtWallMs = nowWallMs,
            )
        )
        if (inserted == -1L) return false
        addExtra(dayId, extensionMs)
        return true
    }

    /** Fetch a day, creating it with the current base allowance if this is its first sighting. */
    @Transaction
    open suspend fun dayOrCreate(dayId: String, baseAllowanceMs: Long): DayBudgetEntity {
        insertDayIfAbsent(
            DayBudgetEntity(
                dayId = dayId,
                baseAllowanceMs = baseAllowanceMs,
                chargedMs = 0,
                extraGrantedMs = 0,
            )
        )
        return requireNotNull(day(dayId)) { "day row disappeared immediately after insert: $dayId" }
    }
}
