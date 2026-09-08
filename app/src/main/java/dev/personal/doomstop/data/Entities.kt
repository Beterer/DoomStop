package dev.personal.doomstop.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.personal.doomstop.domain.Checkpoint
import dev.personal.doomstop.domain.DayBudget
import dev.personal.doomstop.domain.LimiterSettings
import dev.personal.doomstop.domain.RecoveryRequest
import dev.personal.doomstop.monitor.TrackedEvent
import dev.personal.doomstop.monitor.TrackedEventType

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
    /**
     * Set while PIN-authorized maintenance is in progress, and cleared only when a restore
     * has fully succeeded or the operator abandons it. While it is set the coordinator does
     * not assert any policy, so the poll loop cannot race a restore and re-suspend what has
     * just been released.
     */
    val maintenanceMode: Boolean = false,
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

/**
 * Single-row accounting anchor; see [Checkpoint] for the meaning of each clock.
 *
 * The recovery columns are flattened rather than nullable-as-a-group: a reason of null IS
 * the absence of an outstanding request, and the range is kept so the PIN holder can be
 * told what they are acknowledging.
 */
@Entity(tableName = "checkpoint")
data class CheckpointEntity(
    @PrimaryKey val id: Int = SettingsEntity.SINGLETON_ID,
    val bootId: String,
    val lastElapsedMs: Long,
    val lastWallMs: Long,
    val settledWallMs: Long,
    val targetVisible: Boolean,
    val usageCursorWallMs: Long,
    val acceptedDriftMs: Long,
    val recoveryReason: String?,
    val recoveryFromWallMs: Long,
    val recoveryToWallMs: Long,
    val recoveryDetectedAtWallMs: Long,
    val trackerState: String?,
) {
    fun toCheckpoint() = Checkpoint(
        bootId = bootId,
        lastElapsedMs = lastElapsedMs,
        lastWallMs = lastWallMs,
        settledWallMs = settledWallMs,
        targetVisible = targetVisible,
        usageCursorWallMs = usageCursorWallMs,
        acceptedDriftMs = acceptedDriftMs,
        recovery = recoveryReason?.let {
            RecoveryRequest(
                reason = it,
                fromWallMs = recoveryFromWallMs,
                toWallMs = recoveryToWallMs,
                detectedAtWallMs = recoveryDetectedAtWallMs,
            )
        },
        trackerState = trackerState,
    )

    companion object {
        fun from(checkpoint: Checkpoint) = CheckpointEntity(
            bootId = checkpoint.bootId,
            lastElapsedMs = checkpoint.lastElapsedMs,
            lastWallMs = checkpoint.lastWallMs,
            settledWallMs = checkpoint.settledWallMs,
            targetVisible = checkpoint.targetVisible,
            usageCursorWallMs = checkpoint.usageCursorWallMs,
            acceptedDriftMs = checkpoint.acceptedDriftMs,
            recoveryReason = checkpoint.recovery?.reason,
            recoveryFromWallMs = checkpoint.recovery?.fromWallMs ?: 0L,
            recoveryToWallMs = checkpoint.recovery?.toWallMs ?: 0L,
            recoveryDetectedAtWallMs = checkpoint.recovery?.detectedAtWallMs ?: 0L,
            trackerState = checkpoint.trackerState,
        )
    }
}

/**
 * Usage events inside the not-yet-settled window.
 *
 * Accounting deliberately trails the present, and the interval that has not been committed
 * yet is recomputed from scratch on every pass. That recomputation has to see the same
 * events after a process restart as before it, so the events themselves are durable rather
 * than living only in the reader's memory. The primary key is the event's natural identity,
 * which is what makes an overlapping re-read a no-op instead of a second debit.
 *
 * Rows never outlive the settled boundary: everything at or before it is deleted in the same
 * transaction that commits the charge for it.
 */
@Entity(tableName = "pending_event")
data class PendingEventEntity(
    @PrimaryKey val eventKey: String,
    val timestampWallMs: Long,
    val packageName: String,
    val className: String?,
    val type: String,
) {
    /** Null for a type this build does not understand, which is simply ignored. */
    fun toTrackedEvent(): TrackedEvent? {
        val parsed = runCatching { TrackedEventType.valueOf(type) }.getOrNull() ?: return null
        return TrackedEvent(timestampWallMs, packageName, className, parsed)
    }

    companion object {
        fun from(event: TrackedEvent) = PendingEventEntity(
            eventKey = event.key,
            timestampWallMs = event.timestampWallMs,
            packageName = event.packageName,
            className = event.className,
            type = event.type.name,
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
 * Throttling state, deliberately anchored to BOTH clocks.
 *
 * [lastFailureWallMs] stops a rewound clock from ending a cooldown early. [lockBootId] and
 * [nextAllowedElapsedMs] stop a clock wound FORWARD from doing the same, because within one
 * boot the monotonic deadline is unaffected by anything the date settings can do. Across a
 * reboot the monotonic reference is void and the wall-clock deadline is what remains, which
 * is why both are stored rather than one.
 */
@Entity(tableName = "pin_attempts")
data class PinAttemptsEntity(
    @PrimaryKey val id: Int = SettingsEntity.SINGLETON_ID,
    val consecutiveFailures: Int,
    val lastFailureWallMs: Long,
    val nextAllowedWallMs: Long,
    val lockBootId: String = "",
    val nextAllowedElapsedMs: Long = 0L,
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

/** How much is actually known about a policy value before this app first touched it. */
enum class PreviousPolicyState {
    /** The setting was not present at all, so restoring means removing it again. */
    ABSENT,

    /** A value was read successfully and is recorded in [PolicyLedgerEntity.previousValue]. */
    VALUE,

    /**
     * The platform would not say. Restoring must not invent a value or quietly assume the
     * default; the ambiguity is reported to the PIN holder instead.
     */
    UNKNOWN,
}

/**
 * WRITE-AHEAD ledger of policy values this app changed.
 *
 * The row is written BEFORE the corresponding policy call, not after it. That ordering is
 * the whole point: a crash between the two can then only leave a ledger entry for a change
 * that never happened, which restore handles harmlessly, rather than leaving a changed
 * setting whose original value was never recorded -- or, worse, recording this app's own
 * value as the original.
 */
@Entity(tableName = "policy_ledger")
data class PolicyLedgerEntity(
    @PrimaryKey val key: String,
    val previousState: String,
    val previousValue: String?,
    val appliedValue: String?,
    val appliedAtWallMs: Long,
) {
    val previous: PreviousPolicyState
        get() = runCatching { PreviousPolicyState.valueOf(previousState) }
            .getOrDefault(PreviousPolicyState.UNKNOWN)
}
