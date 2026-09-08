package dev.personal.doomstop.core

import android.content.Context
import android.util.Log
import dev.personal.doomstop.admin.ChromePolicyReport
import dev.personal.doomstop.admin.HardeningOptions
import dev.personal.doomstop.admin.PolicyController
import dev.personal.doomstop.admin.SuspensionReport
import dev.personal.doomstop.config.BlockedSites
import dev.personal.doomstop.config.TargetPackages
import dev.personal.doomstop.data.BootMarkerStore
import dev.personal.doomstop.data.CheckpointEntity
import dev.personal.doomstop.data.DiagnosticEventEntity
import dev.personal.doomstop.data.LimiterDao
import dev.personal.doomstop.data.PolicyLedgerEntity
import dev.personal.doomstop.data.SettingsEntity
import dev.personal.doomstop.domain.Anomaly
import dev.personal.doomstop.domain.BudgetEngine
import dev.personal.doomstop.domain.Checkpoint
import dev.personal.doomstop.domain.CheckpointState
import dev.personal.doomstop.domain.ClockSource
import dev.personal.doomstop.domain.DayBoundary
import dev.personal.doomstop.domain.DayBudget
import dev.personal.doomstop.domain.EnforcementDecision
import dev.personal.doomstop.domain.EnforcementReason
import dev.personal.doomstop.domain.LimiterSettings
import dev.personal.doomstop.domain.MonitorHealth
import dev.personal.doomstop.domain.TickInput
import dev.personal.doomstop.domain.VisibilityTransition
import dev.personal.doomstop.monitor.AppPermissions
import dev.personal.doomstop.monitor.UsageEventReader
import dev.personal.doomstop.monitor.VisibleTargetTracker
import dev.personal.doomstop.security.AuthorizationTicket
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What woke the coordinator up. Recorded in diagnostics; does not change the arithmetic. */
enum class Trigger {
    POLL,
    SCREEN_STATE,
    VISIBILITY_HINT,
    PACKAGE_CHANGE,
    BOOT,
    DEADLINE_ALARM,
    ADMIN_ACTION,
    UI,
}

/**
 * The single serialized owner of accounting and policy.
 *
 * Everything -- the one-second poll, broadcast receivers, alarms and the UI -- funnels
 * through [tick] and the other suspend functions, all guarded by one mutex. Receivers
 * never mutate balances concurrently, so there is no interleaving to reason about.
 *
 * Ordering inside a pass is deliberate: reconcile, PERSIST, then enforce. A crash between
 * persisting and enforcing can only leave time already charged, never time silently given
 * back.
 */
class LimiterCoordinator(
    private val context: Context,
    private val dao: LimiterDao,
    private val policy: PolicyController,
    private val reader: UsageEventReader,
    private val bootMarker: BootMarkerStore,
    private val clock: ClockSource,
    private val deadlines: DeadlineScheduler,
) {

    private val mutex = Mutex()

    private var tracker = VisibleTargetTracker(TargetPackages.ALL)

    /** Last enforcement state actually pushed to the platform, to avoid a DPM call per second. */
    private var lastAppliedSuspendTargets: Boolean? = null
    private var lastSuspensionReport: SuspensionReport? = null
    private var lastEnforcementSyncElapsedMs = 0L

    private var lastChromeReport: ChromePolicyReport? = null
    private var lastChromeSyncElapsedMs = 0L

    /** Suppresses repeated identical diagnostics so one bad clock cannot flood the log. */
    private val recentDiagnostics = HashMap<String, Long>()

    private val _status = MutableStateFlow(LimiterStatus.initial(defaultSettings()))
    val status: StateFlow<LimiterStatus> = _status.asStateFlow()

    /** Set by the foreground service so health reporting reflects reality, not intent. */
    @Volatile
    var serviceRunning: Boolean = false

    // -- main loop -----------------------------------------------------------------------

    suspend fun tick(trigger: Trigger): LimiterStatus = mutex.withLock { tickLocked(trigger) }

    private suspend fun tickLocked(trigger: Trigger): LimiterStatus {
        val nowElapsedMs = clock.elapsedRealtimeMs()
        val nowWallMs = clock.wallTimeMs()
        val bootId = clock.bootId()

        val settingsEntity = dao.settings() ?: seedDefaultSettings(nowWallMs)
        val settings = settingsEntity.toSettings()
        val boundary = boundaryFor(settings)

        val previous = dao.checkpoint()?.toCheckpoint()
            ?: Checkpoint.initial(bootId, nowElapsedMs, nowWallMs).also {
                dao.upsertCheckpoint(CheckpointEntity.from(it))
            }

        // A reboot invalidates every in-memory lifecycle assumption; rebuild from events.
        if (previous.bootId != bootId) tracker.reset(nowWallMs)

        val read = reader.read(previous.usageCursorWallMs, nowWallMs)
        val transitions = ArrayList<VisibilityTransition>()
        transitions += tracker.apply(read.events, nowWallMs)
        // The live screen state is authoritative and does not wait for an event to arrive.
        transitions += tracker.observeScreenState(
            interactive = AppPermissions.isScreenInteractive(context),
            keyguardLocked = AppPermissions.isKeyguardLocked(context),
            atWallMs = nowWallMs,
        )

        val accounting = BudgetEngine.account(
            previous = previous,
            tick = TickInput(
                bootId = bootId,
                elapsedMs = nowElapsedMs,
                wallMs = nowWallMs,
                visibleNow = tracker.isVisible,
                usageCursorWallMs = read.newCursorWallMs,
                eventsAvailable = read.available,
            ),
            transitions = transitions,
            boundary = boundary,
        )

        // Persist accounting BEFORE deciding whether targets stay available.
        dao.applyAccounting(
            slices = accounting.slices,
            baseAllowanceMs = settings.dailyAllowanceMs,
            checkpoint = CheckpointEntity.from(accounting.checkpoint),
        )
        accounting.anomalies.forEach { recordAnomaly(it, nowWallMs) }
        if (read.error != null && !read.available) {
            recordDiagnostic("monitor_blind", read.error, nowWallMs)
        }

        val today = dao.dayOrCreate(boundary.dayIdAt(nowWallMs), settings.dailyAllowanceMs).toDayBudget()
        val health = currentHealth(usageAvailable = read.available)
        val decision = if (settingsEntity.setupCompleted) {
            BudgetEngine.decide(today, health, accounting.checkpoint.state)
        } else {
            // Before setup completes nothing is enforced; metering still runs so the first
            // real day starts from an honest, already-working monitor.
            EnforcementDecision(suspendTargets = false, reason = EnforcementReason.ALLOWANCE_AVAILABLE)
        }

        if (settingsEntity.setupCompleted) {
            syncEnforcement(decision.suspendTargets, nowElapsedMs, nowWallMs)
            syncChromePolicy(nowElapsedMs, nowWallMs)
        }

        bootMarker.record(
            protectionEnabled = settingsEntity.setupCompleted,
            targetsSuspended = decision.suspendTargets,
            nowWallMs = nowWallMs,
        )

        deadlines.schedule(
            BudgetEngine.nextDeadlineWallMs(today, nowWallMs, tracker.isVisible, boundary),
            nowWallMs,
        )

        return publish(settingsEntity, settings, boundary, today, decision, accounting.checkpoint.state, health, nowWallMs)
    }

    // -- policy synchronisation ------------------------------------------------------------

    /**
     * Push enforcement to the platform when it changes, and re-assert it periodically.
     *
     * The periodic re-assert is not paranoia: a package can be reinstalled, and reinstalling
     * a target must immediately pick up its current suspension state rather than waiting for
     * the allowance to change.
     */
    private suspend fun syncEnforcement(suspendTargets: Boolean, nowElapsedMs: Long, nowWallMs: Long) {
        val changed = lastAppliedSuspendTargets != suspendTargets
        val due = nowElapsedMs - lastEnforcementSyncElapsedMs >= ENFORCEMENT_RESYNC_MS
        if (!changed && !due) return

        val report = policy.applyEnforcement(suspendTargets)
        lastAppliedSuspendTargets = suspendTargets
        lastSuspensionReport = report
        lastEnforcementSyncElapsedMs = nowElapsedMs

        recordFirstSuspensionLedger(report, nowWallMs)
        if (!report.allApplied) {
            recordDiagnostic(
                type = "suspension_failed",
                detail = report.failures.joinToString { "${it.packageName}:${it.error ?: "state mismatch"}" },
                nowWallMs = nowWallMs,
            )
        }
    }

    /** Chrome's blocklist is permanent, so this only ever re-asserts it; it never clears it. */
    private suspend fun syncChromePolicy(nowElapsedMs: Long, nowWallMs: Long) {
        val satisfied = lastChromeReport?.satisfied == true
        val due = nowElapsedMs - lastChromeSyncElapsedMs >= CHROME_RESYNC_MS
        if (satisfied && !due) return

        val report = policy.applyChromeBlocklist()
        lastChromeSyncElapsedMs = nowElapsedMs
        if (lastChromeReport == null && report.chromeInstalled) {
            // Record what Chrome's blocklist looked like before this app existed, once, so
            // recovery can put it back instead of wiping unrelated restrictions.
            if (dao.ledgerEntry(LEDGER_CHROME_BLOCKLIST) == null) {
                dao.upsertLedgerEntry(
                    PolicyLedgerEntity(
                        key = LEDGER_CHROME_BLOCKLIST,
                        previousValue = report.previousValue,
                        appliedValue = report.appliedValue,
                        appliedAtWallMs = nowWallMs,
                    )
                )
            }
        }
        lastChromeReport = report
        if (!report.satisfied) {
            recordDiagnostic("chrome_policy_unverified", report.error ?: "read-back did not contain every host", nowWallMs)
        }
    }

    /** Remember whether each package was already suspended before this app first touched it. */
    private suspend fun recordFirstSuspensionLedger(report: SuspensionReport, nowWallMs: Long) {
        for (outcome in report.installed) {
            val key = "$LEDGER_SUSPENDED_PREFIX${outcome.packageName}"
            if (dao.ledgerEntry(key) != null) continue
            dao.upsertLedgerEntry(
                PolicyLedgerEntity(
                    key = key,
                    previousValue = null, // unknown before first contact; recorded as such
                    appliedValue = outcome.actualSuspended?.toString(),
                    appliedAtWallMs = nowWallMs,
                )
            )
        }
    }

    // -- authorized actions ------------------------------------------------------------------

    /**
     * Redeem one authorization ticket for exactly one extension.
     *
     * Idempotent by construction: the ticket is the primary key of the grant row, so a
     * double tap, a retry, or a replay after process death inserts nothing and adds no time.
     * Websites stay blocked -- this only touches the native-app allowance.
     */
    suspend fun grantExtension(ticket: AuthorizationTicket): Boolean = mutex.withLock {
        val settingsEntity = dao.settings() ?: return@withLock false
        val settings = settingsEntity.toSettings()
        val nowWallMs = clock.wallTimeMs()
        val dayId = boundaryFor(settings).dayIdAt(nowWallMs)

        val granted = dao.redeemExtension(
            token = ticket.token,
            dayId = dayId,
            baseAllowanceMs = settings.dailyAllowanceMs,
            extensionMs = settings.extensionMs,
            nowWallMs = nowWallMs,
        )
        if (granted) {
            recordDiagnostic("extension_granted", "${settings.extensionMs / 60_000} min", nowWallMs)
        }
        granted
    }.also { if (it) tick(Trigger.ADMIN_ACTION) }

    /**
     * Change limits. Today's base allowance is updated in place so that time already
     * charged is preserved; if the new total is below what has been used, the next tick
     * simply finds the day exhausted and suspends.
     */
    suspend fun updateSettings(
        dailyAllowanceMs: Long,
        extensionMs: Long,
        zoneId: String,
    ): LimiterStatus {
        mutex.withLock {
            val existing = dao.settings() ?: seedDefaultSettings(clock.wallTimeMs())
            val updated = existing.copy(
                dailyAllowanceMs = dailyAllowanceMs.coerceAtLeast(0L),
                extensionMs = extensionMs.coerceAtLeast(0L),
                zoneId = zoneId,
            )
            dao.upsertSettings(updated)
            val dayId = boundaryFor(updated.toSettings()).dayIdAt(clock.wallTimeMs())
            dao.dayOrCreate(dayId, updated.dailyAllowanceMs)
            dao.setBaseAllowance(dayId, updated.dailyAllowanceMs)
        }
        return tick(Trigger.ADMIN_ACTION)
    }

    /** Preview the effect of an allowance change before it is applied. */
    suspend fun previewAllowanceChange(newDailyAllowanceMs: Long): Pair<Long, Long> = mutex.withLock {
        val settings = (dao.settings() ?: seedDefaultSettings(clock.wallTimeMs())).toSettings()
        val today = dao.dayOrCreate(
            boundaryFor(settings).dayIdAt(clock.wallTimeMs()),
            settings.dailyAllowanceMs,
        ).toDayBudget()
        val updated = DayBudget(today.dayId, newDailyAllowanceMs, today.chargedMs, today.extraGrantedMs)
        today.remainingMs to updated.remainingMs
    }

    suspend fun completeSetup(): LimiterStatus {
        mutex.withLock {
            val existing = dao.settings() ?: seedDefaultSettings(clock.wallTimeMs())
            dao.upsertSettings(existing.copy(setupCompleted = true))
            policy.protectSelf(enabled = true)
        }
        return tick(Trigger.ADMIN_ACTION)
    }

    /**
     * Clear a recovery state after the PIN holder has acknowledged it.
     *
     * The hole in the history is NOT filled in with a guess: the checkpoint is re-anchored
     * to now and marked clean, and the day's charged total is left exactly as it stands.
     */
    suspend fun acknowledgeRecovery(): LimiterStatus {
        mutex.withLock {
            val nowWallMs = clock.wallTimeMs()
            val current = dao.checkpoint()?.toCheckpoint()
            val reanchored = (current ?: Checkpoint.initial(clock.bootId(), clock.elapsedRealtimeMs(), nowWallMs))
                .copy(
                    bootId = clock.bootId(),
                    lastElapsedMs = clock.elapsedRealtimeMs(),
                    lastWallMs = nowWallMs,
                    usageCursorWallMs = nowWallMs,
                    targetVisible = false,
                    state = CheckpointState.CLEAN,
                )
            dao.upsertCheckpoint(CheckpointEntity.from(reanchored))
            tracker = VisibleTargetTracker(TargetPackages.ALL)
            recordDiagnostic("recovery_acknowledged", "checkpoint re-anchored; charged time preserved", nowWallMs)
        }
        return tick(Trigger.ADMIN_ACTION)
    }

    suspend fun applyHardening(options: HardeningOptions): Set<String> = mutex.withLock {
        val active = policy.applyHardening(options)
        recordDiagnostic("hardening_applied", active.joinToString().ifEmpty { "none" }, clock.wallTimeMs())
        active
    }

    /**
     * PIN-authorized maintenance: put the device back the way it was.
     *
     * Restores only what this app changed -- unsuspends exactly its own two lists, puts
     * Chrome's blocklist back to the recorded previous value, clears the user restrictions
     * it set, and drops its own self-protection. Device ownership is relinquished last,
     * because nothing above can be undone once it is gone.
     */
    suspend fun restoreDevice(relinquishOwnership: Boolean): RestoreReport = mutex.withLock {
        val nowWallMs = clock.wallTimeMs()
        val released = policy.releaseAll()
        val ledger = dao.ledgerEntry(LEDGER_CHROME_BLOCKLIST)
        val chromeRestored = policy.restoreChromeBlocklist(ledger?.previousValue)
        policy.applyHardening(HardeningOptions())
        policy.protectSelf(enabled = false)

        val ownershipResult = if (relinquishOwnership) policy.relinquishDeviceOwnership() else null

        dao.upsertSettings(
            (dao.settings() ?: seedDefaultSettings(nowWallMs)).copy(setupCompleted = false)
        )
        bootMarker.record(protectionEnabled = false, targetsSuspended = false, nowWallMs = nowWallMs)
        lastAppliedSuspendTargets = null
        lastChromeReport = null
        recordDiagnostic("device_restored", "ownership relinquished=$relinquishOwnership", nowWallMs)

        RestoreReport(
            unsuspended = released.allApplied,
            chromeRestored = chromeRestored,
            ownershipRelinquished = ownershipResult?.isSuccess,
            error = ownershipResult?.exceptionOrNull()?.message,
        )
    }

    // -- helpers ------------------------------------------------------------------------------

    private fun boundaryFor(settings: LimiterSettings): DayBoundary =
        DayBoundary(runCatching { ZoneId.of(settings.zoneId) }.getOrElse { ZoneId.systemDefault() })

    private fun defaultSettings() = LimiterSettings(
        dailyAllowanceMs = LimiterSettings.DEFAULT_DAILY_ALLOWANCE_MS,
        extensionMs = LimiterSettings.DEFAULT_EXTENSION_MS,
        zoneId = ZoneId.systemDefault().id,
    )

    private suspend fun seedDefaultSettings(nowWallMs: Long): SettingsEntity {
        val seeded = SettingsEntity(
            dailyAllowanceMs = LimiterSettings.DEFAULT_DAILY_ALLOWANCE_MS,
            extensionMs = LimiterSettings.DEFAULT_EXTENSION_MS,
            // The accounting timezone is captured once, here, and then stays put.
            zoneId = ZoneId.systemDefault().id,
            setupCompleted = false,
        )
        dao.upsertSettings(seeded)
        recordDiagnostic("settings_seeded", "proposed defaults, not confirmed choices", nowWallMs)
        return seeded
    }

    private fun currentHealth(usageAvailable: Boolean) = MonitorHealth(
        deviceOwner = policy.isDeviceOwner,
        usageAccessGranted = usageAvailable && reader.hasUsageAccess(),
        serviceRunning = serviceRunning,
        notificationsEnabled = AppPermissions.notificationsEnabled(context),
    )

    private suspend fun recordAnomaly(anomaly: Anomaly, nowWallMs: Long) = when (anomaly) {
        is Anomaly.BootChanged ->
            recordDiagnostic("boot_changed", "${anomaly.previousBootId} -> ${anomaly.currentBootId}", nowWallMs)

        is Anomaly.ClockJump ->
            recordDiagnostic("clock_jump", "${anomaly.deltaMs} ms between wall and monotonic clocks", nowWallMs)

        is Anomaly.MonotonicRegression ->
            recordDiagnostic("monotonic_regression", "${anomaly.deltaMs} ms", nowWallMs)

        is Anomaly.UnreconciledGap ->
            recordDiagnostic("unreconciled_gap", "${anomaly.gapMs} ms: ${anomaly.reason}", nowWallMs)
    }

    /** Diagnostics carry no PIN and no window content -- only protection events. */
    private suspend fun recordDiagnostic(type: String, detail: String, nowWallMs: Long) {
        val key = "$type|$detail"
        val last = recentDiagnostics[key]
        if (last != null && nowWallMs - last < DIAGNOSTIC_DEDUP_MS) return
        recentDiagnostics[key] = nowWallMs
        if (recentDiagnostics.size > MAX_DEDUP_KEYS) recentDiagnostics.clear()
        runCatching { dao.insertDiagnostic(DiagnosticEventEntity(atWallMs = nowWallMs, type = type, detail = detail)) }
            .onFailure { Log.w(TAG, "could not record diagnostic $type", it) }
    }

    private suspend fun publish(
        settingsEntity: SettingsEntity,
        settings: LimiterSettings,
        boundary: DayBoundary,
        today: DayBudget,
        decision: EnforcementDecision,
        checkpointState: CheckpointState,
        health: MonitorHealth,
        nowWallMs: Long,
    ): LimiterStatus {
        val status = LimiterStatus(
            setupCompleted = settingsEntity.setupCompleted,
            settings = settings,
            dayId = today.dayId,
            remainingMs = today.remainingMs,
            totalAllowanceMs = today.totalAllowanceMs,
            baseAllowanceMs = today.baseAllowanceMs,
            chargedMs = today.chargedMs,
            extraGrantedMs = today.extraGrantedMs,
            nextResetWallMs = boundary.nextBoundaryAfter(nowWallMs),
            targetVisible = tracker.isVisible,
            enforcement = decision,
            checkpointState = checkpointState,
            health = health,
            installedTargets = policy.installedTargets(),
            installedBlockedBrowsers = policy.installedBlockedBrowsers(),
            suspension = lastSuspensionReport,
            chrome = lastChromeReport,
            tracker = tracker.snapshot(),
            pinSet = dao.pinVerifier() != null,
            exactAlarmsAllowed = AppPermissions.canScheduleExactAlarms(context),
            lastTickWallMs = nowWallMs,
            recentAnomalies = recentDiagnostics.keys.toList().takeLast(MAX_SURFACED_ANOMALIES),
        )
        _status.value = status
        return status
    }

    companion object {
        private const val TAG = "DoomStopCoordinator"

        /** Re-assert suspension this often even when nothing changed (reinstall pickup). */
        private const val ENFORCEMENT_RESYNC_MS = 30_000L

        /** Re-assert and re-verify the permanent Chrome policy this often. */
        private const val CHROME_RESYNC_MS = 5L * 60_000L

        private const val DIAGNOSTIC_DEDUP_MS = 60_000L
        private const val MAX_DEDUP_KEYS = 64
        private const val MAX_SURFACED_ANOMALIES = 8

        const val LEDGER_CHROME_BLOCKLIST = "chrome.${BlockedSites.KEY_URL_BLOCKLIST}"
        const val LEDGER_SUSPENDED_PREFIX = "suspended."
    }
}

/** Outcome of PIN-authorized maintenance. */
data class RestoreReport(
    val unsuspended: Boolean,
    val chromeRestored: Boolean,
    val ownershipRelinquished: Boolean?,
    val error: String?,
)
