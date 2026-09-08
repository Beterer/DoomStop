package dev.personal.doomstop.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.personal.doomstop.domain.Checkpoint
import dev.personal.doomstop.domain.CheckpointState
import dev.personal.doomstop.domain.DayBudget
import dev.personal.doomstop.domain.LimiterSettings

/**
 * Room entities. Every table is small and every write goes through the single serialized
 * coordinator, so the schema stays deliberately boring.
 *
 * Binary material (PIN salt and verifier) is stored Base64-encoded rather than as BLOBs:
 * it keeps entities as value types with sane equality, and the database file is the thing
 * being protected, not the encoding.
 */

/** Single-row configuration table. */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val dailyAllowanceMs: Long,
    val extensionMs: Long,
    /** Fixed accounting timezone captured at setup; never follows the device's timezone. */
    val zoneId: String,
    val setupCompleted: Boolean,
) {
    fun toSettings() = LimiterSettings(dailyAllowanceMs, extensionMs, zoneId)

    companion object {
        const val SINGLETON_ID = 1
    }
}

/**
 * One row per accounting day. The day ID being the primary key is what makes rollover
 * idempotent and what stops "wind the clock back and forth" from granting a second
 * allowance: returning to a day returns its existing charged total.
 */
@Entity(tableName = "day_budget")
data class DayBudgetEntity(
    @PrimaryKey val dayId: String,
    val baseAllowanceMs: Long,
    val chargedMs: Long,
    val extraGrantedMs: Long,
) {
    fun toDayBudget() = DayBudget(dayId, baseAllowanceMs, chargedMs, extraGrantedMs)

    companion object {
        fun from(budget: DayBudget) = DayBudgetEntity(
            dayId = budget.dayId,
            baseAllowanceMs = budget.baseAllowanceMs,
            chargedMs = budget.chargedMs,
            extraGrantedMs = budget.extraGrantedMs,
        )
    }
}

/** Single-row accounting anchor; see [Checkpoint] for the meaning of each clock. */
@Entity(tableName = "checkpoint")
data class CheckpointEntity(
    @PrimaryKey val id: Int = SettingsEntity.SINGLETON_ID,
    val bootId: String,
    val lastElapsedMs: Long,
    val lastWallMs: Long,
    val targetVisible: Boolean,
    val usageCursorWallMs: Long,
    val state: String,
) {
    fun toCheckpoint() = Checkpoint(
        bootId = bootId,
        lastElapsedMs = lastElapsedMs,
        lastWallMs = lastWallMs,
        targetVisible = targetVisible,
        usageCursorWallMs = usageCursorWallMs,
        state = runCatching { CheckpointState.valueOf(state) }.getOrDefault(CheckpointState.UNCERTAIN),
    )

    companion object {
        fun from(checkpoint: Checkpoint) = CheckpointEntity(
            bootId = checkpoint.bootId,
            lastElapsedMs = checkpoint.lastElapsedMs,
            lastWallMs = checkpoint.lastWallMs,
            targetVisible = checkpoint.targetVisible,
            usageCursorWallMs = checkpoint.usageCursorWallMs,
            state = checkpoint.state.name,
        )
    }
}

/**
 * PIN verifier. Six digits have limited entropy and this row does not change that: it is
 * protected by the app sandbox, by backup being disabled, and by online throttling, not by
 * the KDF alone.
 */
@Entity(tableName = "pin_verifier")
data class PinVerifierEntity(
    @PrimaryKey val id: Int = SettingsEntity.SINGLETON_ID,
    /** Verifier format version, so parameters can be upgraded without guessing. */
    val version: Int,
    val algorithm: String,
    val iterations: Int,
    val saltBase64: String,
    val verifierBase64: String,
)

/**
 * Throttling state. Persisted so a reboot neither clears the counter nor shortens an
 * active cooldown, and [lastFailureWallMs] is kept so that winding the clock backwards
 * cannot end a cooldown early either.
 */
@Entity(tableName = "pin_attempts")
data class PinAttemptsEntity(
    @PrimaryKey val id: Int = SettingsEntity.SINGLETON_ID,
    val consecutiveFailures: Int,
    val lastFailureWallMs: Long,
    val nextAllowedWallMs: Long,
)

/**
 * One redeemed authorization. The token is the primary key, so replaying a grant -- a
 * double tap, a retried coroutine, a process death between verify and apply -- inserts
 * nothing and adds no time.
 */
@Entity(tableName = "extension_grant")
data class ExtensionGrantEntity(
    @PrimaryKey val token: String,
    val dayId: String,
    val grantedMs: Long,
    val grantedAtWallMs: Long,
)

/**
 * Minimal diagnostics: protection errors and extension grants. Never a PIN, never window
 * content, never anything about what was on screen.
 */
@Entity(tableName = "diagnostic_event")
data class DiagnosticEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atWallMs: Long,
    val type: String,
    val detail: String,
)

/**
 * Ledger of policy values this app changed, with the previous value where the platform
 * exposed one. Recovery restores from here instead of guessing, so unrelated Chrome
 * restrictions and unrelated suspensions are left alone.
 */
@Entity(tableName = "policy_ledger")
data class PolicyLedgerEntity(
    @PrimaryKey val key: String,
    val previousValue: String?,
    val appliedValue: String?,
    val appliedAtWallMs: Long,
)
