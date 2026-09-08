package dev.personal.doomstop.core

import android.content.Context
import android.util.Log
import dev.personal.doomstop.admin.ChromePolicyReport
import dev.personal.doomstop.admin.DevicePolicyGateway
import dev.personal.doomstop.admin.HardeningOptions
import dev.personal.doomstop.admin.SelfProtectionReport
import dev.personal.doomstop.admin.SuspensionReport
import dev.personal.doomstop.config.BlockedSites
import dev.personal.doomstop.config.TargetPackages
import dev.personal.doomstop.data.BootMarkerStore
import dev.personal.doomstop.data.CheckpointEntity
import dev.personal.doomstop.data.DiagnosticEventEntity
import dev.personal.doomstop.data.LimiterDao
import dev.personal.doomstop.data.PendingEventEntity
import dev.personal.doomstop.data.PolicyLedgerEntity
import dev.personal.doomstop.data.PreviousPolicyState
import dev.personal.doomstop.data.SettingsEntity
import dev.personal.doomstop.domain.Anomaly
import dev.personal.doomstop.domain.BudgetEngine
import dev.personal.doomstop.domain.Checkpoint
import dev.personal.doomstop.domain.ClockSource
import dev.personal.doomstop.domain.DayBoundary
import dev.personal.doomstop.domain.DayBudget
import dev.personal.doomstop.domain.DaySlice
import dev.personal.doomstop.domain.EnforcementDecision
import dev.personal.doomstop.domain.EnforcementReason
import dev.personal.doomstop.domain.LimiterSettings
import dev.personal.doomstop.domain.MonitorHealth
import dev.personal.doomstop.domain.RecoveryRequest
import dev.personal.doomstop.domain.SettledWindow
import dev.personal.doomstop.domain.TickInput
import dev.personal.doomstop.domain.VisibilityTransition
import dev.personal.doomstop.monitor.AppPermissions
import dev.personal.doomstop.monitor.TrackedEvent
import dev.personal.doomstop.monitor.TrackedEventType
import dev.personal.doomstop.monitor.TrackerState
import dev.personal.doomstop.monitor.UsageSource
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

/** One step of PIN-authorized maintenance, with the outcome actually read back. */
data class RestoreStage(val name: String, val succeeded: Boolean, val detail: String? = null)

/**
 * Outcome of PIN-authorized maintenance.
 *
 * Every step reports separately and nothing downstream of a failure runs. Device ownership
 * in particular is released only when every preceding stage verified, because releasing it
 * is the one action that makes the rest impossible to retry.
 */
data class RestoreReport(
    val stages: List<RestoreStage>,
    val ownershipRelinquished: Boolean?,
    /** True while the device is left in a recoverable maintenance state awaiting a retry. */
    val maintenanceRetained: Boolean,
    /** Packages whose pre-DoomStop suspension state could not be established. */
    val ambiguousPackages: List<String> = emptyList(),
) {
    val completed: Boolean get() = stages.isNotEmpty() && stages.all { it.succeeded }
    val failures: List<RestoreStage> get() = stages.filterNot { it.succeeded }
}

/** Whether setup could actually be completed, and what was missing if not. */
data class SetupOutcome(
    val protectionActive: Boolean,
    val missing: List<String>,
    val selfProtection: SelfProtectionReport?,
)

/**
 * The single serialized owner of accounting and policy.
 *
 * Everything -- the one-second poll, broadcast receivers, alarms and the UI -- funnels
 * through [tick] and the other suspend functions, all guarded by one mutex. Receivers
 * never mutate balances concurrently, so there is no interleaving to reason about.
 *
 * Ordering inside a pass is deliberate: reconcile, PERSIST, then enforce. A crash between
 * persisting and enforcing can only leave time already charged, never time silently given
 * back. Policy values are recorded in the ledger BEFORE they are changed, for the same
 * reason in the opposite direction: a crash must not lose the original.
 */
class LimiterCoordinator(
    private val context: Context,
    private val dao: LimiterDao,
    private val policy: DevicePolicyGateway,
    private val reader: UsageSource,
    private val bootMarker: BootMarkerStore,
    private val clock: ClockSource,
    private val deadlines: DeadlineScheduler,
) {

    private val mutex = Mutex()

    private var tracker = VisibleTargetTracker(TargetPackages.ALL)

    /** False until the durable observer state has been read back in this process. */
    private var observerRestored = false

    /** Last enforcement state actually pushed to the platform, to avoid a DPM call per second. */
    private var lastAppliedSuspendTargets: Boolean? = null
    private var lastSuspensionReport: SuspensionReport? = null
    private var lastEnforcementSyncElapsedMs = 0L

    private var lastChromeReport: ChromePolicyReport? = null
    private var lastChromeSyncElapsedMs = 0L

    private var lastSelfProtection: SelfProtectionReport? = null

    /** Ledger keys already written, so the write-ahead check is not a query per package per tick. */
    private val ledgeredKeys = HashSet<String>()
    private var ledgerLoaded = false

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
        val reportedWallMs = clock.wallTimeMs()
        val bootId = clock.bootId()

        val settingsEntity = dao.settings() ?: seedDefaultSettings(reportedWallMs)
        val settings = settingsEntity.toSettings()
        val boundary = boundaryFor(settings)

        var previous = dao.checkpoint()?.toCheckpoint()
            ?: Checkpoint.initial(bootId, nowElapsedMs, reportedWallMs).also {
                dao.upsertCheckpoint(CheckpointEntity.from(it))
            }

        val read = reader.read(previous.usageCursorWallMs, reportedWallMs)
        val tickInput = TickInput(
            bootId = bootId,
            elapsedMs = nowElapsedMs,
            reportedWallMs = reportedWallMs,
            eventsAvailable = read.available,
        )
        val resolution = BudgetEngine.resolveClock(previous, tickInput)
        val acceptedNowMs = resolution.acceptedNowWallMs

        // The observer is rebuilt from the SETTLED snapshot on every pass, not just at
        // process start. The unsettled tail is recomputed from the retained events each
        // time, so carrying a live observer forward would replay the tail on top of itself.
        previous = restoreObserver(previous, reportedWallMs)

        val bootChanged = previous.bootId != bootId
        if (bootChanged || resolution.refusal != null || !read.available) {
            // This pass cannot reconstruct its own window. Everything the PREVIOUS pass
            // already knew can still be settled, though, and must be: otherwise a reboot, a
            // clock change or a lost usage source would each hand back the last half-minute
            // of use, repeatedly and on demand.
            previous = settleOpenTail(previous, settings, boundary, discardObserver = bootChanged)
        }
        if (bootChanged) {
            // A reboot invalidates every lifecycle assumption, and events from the old boot
            // must not be replayed into the new one.
            val approximateBootWallMs = reportedWallMs - nowElapsedMs
            tracker.reset(maxOf(previous.settledWallMs, approximateBootWallMs))
            dao.clearPendingEvents()
        }

        // -- assemble the event stream for this pass -------------------------------------

        val settledStartMs = previous.settledWallMs
        val settledEndMs = maxOf(settledStartMs, acceptedNowMs - BudgetEngine.SETTLE_LAG_MS)

        val stored = dao.pendingEvents().mapNotNull { it.toTrackedEvent() }
        val storedKeys = stored.mapTo(HashSet()) { it.key }
        val freshAll = read.events.filterNot { it.key in storedKeys }
        val (fresh, tooLate) = freshAll.partition { it.timestampWallMs >= settledStartMs }
        if (tooLate.isNotEmpty()) {
            // Conservative policy for an event delivered later than the accounting window
            // stays open: it is dropped rather than applied to an interval that has already
            // been charged, and the fact is recorded so a systematic problem is visible.
            recordDiagnostic(
                type = "late_event_dropped",
                detail = "${tooLate.size} event(s) arrived after their interval had settled",
                nowWallMs = acceptedNowMs,
            )
        }

        val usable = (stored + fresh).distinctBy { it.key }.sortedBy { it.timestampWallMs }

        // -- replay: settled first, then the tail that is still open to correction --------

        val settledTransitions = tracker.apply(usable.filter { it.timestampWallMs <= settledEndMs }, settledEndMs)
        val visibleAtSettledEnd = tracker.isVisible
        val settledObserverState = tracker.exportState().encode()

        val tailTransitions = ArrayList<VisibilityTransition>()
        tailTransitions += tracker.apply(usable.filter { it.timestampWallMs > settledEndMs }, acceptedNowMs)

        // The live system is authoritative about the screen and does not wait for an event
        // to be delivered. Any correction is applied to the observer AND written into the
        // event stream, because an observation that lived only in memory would be lost the
        // next time this window is recomputed.
        val correction = liveScreenCorrection(acceptedNowMs)
        tailTransitions += correction.transitions
        val newEvents = fresh + correction.events

        val accounting = BudgetEngine.account(
            previous = previous,
            tick = tickInput,
            resolution = resolution,
            window = SettledWindow(
                endWallMs = settledEndMs,
                visibleAtEnd = visibleAtSettledEnd,
                trackerState = settledObserverState,
                usageCursorWallMs = reportedWallMs,
            ),
            transitions = settledTransitions,
            boundary = boundary,
        )

        var checkpoint = accounting.checkpoint
        if (tracker.hasUnresolved && checkpoint.recovery == null) {
            checkpoint = checkpoint.copy(
                recovery = RecoveryRequest(
                    reason = "a target stayed paused without stopping for longer than the observer can interpret",
                    fromWallMs = settledStartMs,
                    toWallMs = acceptedNowMs,
                    detectedAtWallMs = acceptedNowMs,
                )
            )
        }

        // Persist accounting BEFORE deciding whether targets stay available.
        dao.applyAccounting(
            slices = accounting.slices,
            baseAllowanceMs = settings.dailyAllowanceMs,
            checkpoint = CheckpointEntity.from(checkpoint),
            retainedEvents = newEvents.map { PendingEventEntity.from(it) },
            settledThroughWallMs = settledEndMs,
        )
        accounting.anomalies.forEach { recordAnomaly(it, acceptedNowMs) }
        checkpoint.recovery?.let {
            if (previous.recovery == null) {
                recordDiagnostic("recovery_latched", it.reason, acceptedNowMs)
            }
        }
        if (read.error != null && !read.available) {
            recordDiagnostic("monitor_blind", read.error, acceptedNowMs)
        }

        // -- the tail nobody has been charged for yet ------------------------------------

        val provisional: List<DaySlice> = if (resolution.refusal != null || !read.available) {
            emptyList()
        } else {
            BudgetEngine.provisionalSlices(
                fromWallMs = settledEndMs,
                toWallMs = acceptedNowMs,
                visibleAtStart = visibleAtSettledEnd,
                transitions = tailTransitions,
                boundary = boundary,
            )
        }

        val todayId = boundary.dayIdAt(acceptedNowMs)
        val settledToday = dao.dayOrCreate(todayId, settings.dailyAllowanceMs).toDayBudget()
        val provisionalTodayMs = provisional.firstOrNull { it.dayId == todayId }?.durationMs ?: 0L
        val today = settledToday.copy(chargedMs = settledToday.chargedMs + provisionalTodayMs)

        val health = currentHealth(usageAvailable = read.available)
        val decision = if (settingsEntity.setupCompleted) {
            BudgetEngine.decide(today, health, checkpoint.state, settingsEntity.maintenanceMode)
        } else {
            // Before setup completes nothing is enforced; metering still runs so the first
            // real day starts from an honest, already-working monitor.
            EnforcementDecision(suspendTargets = false, reason = EnforcementReason.ALLOWANCE_AVAILABLE)
        }

        if (settingsEntity.setupCompleted && !settingsEntity.maintenanceMode) {
            // A package change must re-apply policy immediately rather than wait for the
            // periodic resync: for a freshly installed browser the whole point is how
            // narrow that window is. Measured at 4.6 s when this was left to the timer.
            val force = trigger == Trigger.PACKAGE_CHANGE ||
                trigger == Trigger.BOOT ||
                trigger == Trigger.ADMIN_ACTION
            syncEnforcement(decision.suspendTargets, nowElapsedMs, acceptedNowMs, force)
            syncChromePolicy(nowElapsedMs, acceptedNowMs, force)
        }

        bootMarker.record(
            protectionEnabled = settingsEntity.setupCompleted && !settingsEntity.maintenanceMode,
            targetsSuspended = decision.suspendTargets,
            nowWallMs = acceptedNowMs,
        )

        deadlines.schedule(
            BudgetEngine.nextDeadlineWallMs(today, reportedWallMs, tracker.isVisible, boundary),
            reportedWallMs,
        )

        return publish(settingsEntity, settings, boundary, today, decision, checkpoint, health, acceptedNowMs)
    }

    /**
     * Bring the visibility observer back to the state the checkpoint was written with.
     *
     * The failure this exists to prevent: constructing a fresh, empty observer while the
     * durable checkpoint says a target was on screen. The engine would charge the inherited
     * interval once and then record "not visible" from the empty observer, after which the
     * rest of that same open session was free.
     *
     * If the observer state cannot be reconstructed but the checkpoint claimed visibility,
     * the honest state is not "assume nothing was on screen" and not "assume it still is" --
     * it is the latched recovery path.
     */
    private fun restoreObserver(previous: Checkpoint, nowWallMs: Long): Checkpoint {
        observerRestored = true
        tracker = VisibleTargetTracker(TargetPackages.ALL)
        val state = TrackerState.decode(previous.trackerState)
        if (state != null) {
            tracker.restore(state)
            return previous
        }
        tracker.startAt(previous.settledWallMs)
        if (!previous.targetVisible || previous.recovery != null) return previous
        return previous.copy(
            recovery = RecoveryRequest(
                reason = "a target was on screen when the monitor stopped and its state could not be recovered",
                fromWallMs = previous.settledWallMs,
                toWallMs = nowWallMs,
                detectedAtWallMs = nowWallMs,
            )
        )
    }

    /**
     * Charge the interval that the previous pass left open, from its own retained events,
     * and commit it as a settled interval of its own.
     *
     * [discardObserver] is set when the observer state cannot survive what just happened --
     * a reboot -- so the next pass starts from nothing on screen rather than from a belief
     * that belongs to a boot which no longer exists.
     */
    private suspend fun settleOpenTail(
        previous: Checkpoint,
        settings: LimiterSettings,
        boundary: DayBoundary,
        discardObserver: Boolean,
    ): Checkpoint {
        if (previous.lastWallMs <= previous.settledWallMs) return previous
        val retained = dao.pendingEvents().mapNotNull { it.toTrackedEvent() }
        val transitions = tracker.apply(
            retained.filter { it.timestampWallMs <= previous.lastWallMs },
            previous.lastWallMs,
        )
        val flushed = BudgetEngine.flushOpenTail(
            previous = previous,
            transitions = transitions,
            visibleAtEnd = !discardObserver && tracker.isVisible,
            trackerState = if (discardObserver) null else tracker.exportState().encode(),
            boundary = boundary,
        )
        dao.applyAccounting(
            slices = flushed.slices,
            baseAllowanceMs = settings.dailyAllowanceMs,
            checkpoint = CheckpointEntity.from(flushed.checkpoint),
            retainedEvents = emptyList(),
            settledThroughWallMs = flushed.checkpoint.settledWallMs,
        )
        if (flushed.totalChargedMs > 0) {
            recordDiagnostic(
                type = "open_tail_settled",
                detail = "${flushed.totalChargedMs} ms charged for the window that was still open",
                nowWallMs = previous.lastWallMs,
            )
        }
        return flushed.checkpoint
    }

    /** A live screen observation, as both an immediate correction and a replayable event. */
    private data class ScreenCorrection(
        val transitions: List<VisibilityTransition>,
        val events: List<TrackedEvent>,
    )

    /**
     * Correct the observer from the live system, and put that correction on the same event
     * stream as everything else so a later replay sees it too.
     *
     * The comparison is against what the OBSERVER currently believes, not against the last
     * observation this method made: an event stream that reported the wrong screen state
     * would otherwise never be contradicted. Nothing is written while the two already agree,
     * so this is a handful of rows a day rather than one per poll.
     */
    private fun liveScreenCorrection(atWallMs: Long): ScreenCorrection {
        val interactive = AppPermissions.isScreenInteractive(context)
        val locked = AppPermissions.isKeyguardLocked(context)
        if (tracker.screenIsInteractive == interactive && tracker.keyguardIsShown == locked) {
            return ScreenCorrection(emptyList(), emptyList())
        }
        val transitions = tracker.observeScreenState(interactive, locked, atWallMs)
        val events = listOf(
            TrackedEvent(
                atWallMs,
                LIVE_OBSERVATION_PACKAGE,
                LIVE_OBSERVATION_CLASS,
                if (interactive) TrackedEventType.SCREEN_INTERACTIVE else TrackedEventType.SCREEN_NON_INTERACTIVE,
            ),
            TrackedEvent(
                atWallMs,
                LIVE_OBSERVATION_PACKAGE,
                LIVE_OBSERVATION_CLASS,
                if (locked) TrackedEventType.KEYGUARD_SHOWN else TrackedEventType.KEYGUARD_HIDDEN,
            ),
        )
        return ScreenCorrection(transitions, events)
    }

    // -- policy synchronisation ------------------------------------------------------------

    /**
     * Push enforcement to the platform when it changes, and re-assert it periodically.
     *
     * The periodic re-assert is not paranoia: a package can be reinstalled, and reinstalling
     * a target must immediately pick up its current suspension state rather than waiting for
     * the allowance to change.
     */
    private suspend fun syncEnforcement(
        suspendTargets: Boolean,
        nowElapsedMs: Long,
        nowWallMs: Long,
        force: Boolean,
    ) {
        val changed = lastAppliedSuspendTargets != suspendTargets
        val due = nowElapsedMs - lastEnforcementSyncElapsedMs >= ENFORCEMENT_RESYNC_MS
        if (!force && !changed && !due) return

        recordSuspensionLedger(nowWallMs)
        val report = policy.applyEnforcement(suspendTargets)
        lastAppliedSuspendTargets = suspendTargets
        lastSuspensionReport = report
        lastEnforcementSyncElapsedMs = nowElapsedMs

        if (!report.allApplied) {
            recordDiagnostic(
                type = "suspension_failed",
                detail = report.failures.joinToString { "${it.packageName}:${it.error ?: "state mismatch"}" },
                nowWallMs = nowWallMs,
            )
        }
    }

    /** Chrome's blocklist is permanent, so this only ever re-asserts it; it never clears it. */
    private suspend fun syncChromePolicy(nowElapsedMs: Long, nowWallMs: Long, force: Boolean) {
        val verified = lastChromeReport?.storedPolicyVerified == true
        val due = nowElapsedMs - lastChromeSyncElapsedMs >= CHROME_RESYNC_MS
        if (!force && verified && !due) return

        recordChromeLedger(nowWallMs)
        val report = policy.applyChromeBlocklist()
        lastChromeSyncElapsedMs = nowElapsedMs
        lastChromeReport = report
        if (!report.storedPolicyVerified) {
            recordDiagnostic(
                type = "chrome_policy_unverified",
                detail = report.error ?: "read-back did not contain every host",
                nowWallMs = nowWallMs,
            )
        }
    }

    // -- write-ahead policy ledger -----------------------------------------------------------

    private suspend fun loadLedger() {
        if (ledgerLoaded) return
        dao.ledger().mapTo(ledgeredKeys) { it.key }
        ledgerLoaded = true
    }

    private suspend fun writeLedgerOnce(
        key: String,
        state: PreviousPolicyState,
        value: String?,
        nowWallMs: Long,
    ) {
        loadLedger()
        if (key in ledgeredKeys) return
        dao.upsertLedgerEntry(
            PolicyLedgerEntity(
                key = key,
                previousState = state.name,
                previousValue = value,
                appliedValue = null,
                appliedAtWallMs = nowWallMs,
            )
        )
        ledgeredKeys += key
    }

    /**
     * Record what each package's suspension state was BEFORE this app first changed it.
     *
     * Written ahead of the policy call so that a crash in between can only leave a ledger
     * row for a change that never happened, which restore treats as a no-op. Recording
     * afterwards -- as the first version did, with previousValue always null -- meant
     * restore had no idea which packages had already been suspended by someone else.
     */
    private suspend fun recordSuspensionLedger(nowWallMs: Long) {
        loadLedger()
        for (packageName in policy.manageablePackages()) {
            val key = LEDGER_SUSPENDED_PREFIX + packageName
            if (key in ledgeredKeys) continue
            if (!policy.isInstalled(packageName)) continue
            val before = policy.readSuspended(packageName)
            writeLedgerOnce(
                key = key,
                state = if (before == null) PreviousPolicyState.UNKNOWN else PreviousPolicyState.VALUE,
                value = before?.toString(),
                nowWallMs = nowWallMs,
            )
        }
    }

    private suspend fun recordChromeLedger(nowWallMs: Long) {
        loadLedger()
        if (LEDGER_CHROME_BLOCKLIST in ledgeredKeys) return
        if (!policy.isInstalled(BlockedSites.CHROME_PACKAGE)) return
        val read = policy.readChromeBlocklist()
        writeLedgerOnce(
            key = LEDGER_CHROME_BLOCKLIST,
            state = read.fold(
                onSuccess = { if (it == null) PreviousPolicyState.ABSENT else PreviousPolicyState.VALUE },
                onFailure = { PreviousPolicyState.UNKNOWN },
            ),
            value = read.getOrNull(),
            nowWallMs = nowWallMs,
        )
    }

    private suspend fun recordProtectionLedger(nowWallMs: Long) {
        val controlled = policy.readUserControlDisabledPackages()
        writeLedgerOnce(
            key = LEDGER_USER_CONTROL,
            state = if (controlled == null) PreviousPolicyState.UNKNOWN else PreviousPolicyState.VALUE,
            value = controlled?.joinToString(LIST_SEPARATOR),
            nowWallMs = nowWallMs,
        )
        val uninstallBlocked = policy.readUninstallBlocked()
        writeLedgerOnce(
            key = LEDGER_UNINSTALL_BLOCKED,
            state = if (uninstallBlocked == null) PreviousPolicyState.UNKNOWN else PreviousPolicyState.VALUE,
            value = uninstallBlocked?.toString(),
            nowWallMs = nowWallMs,
        )
    }

    private suspend fun recordHardeningLedger(nowWallMs: Long) {
        writeLedgerOnce(
            key = LEDGER_RESTRICTIONS,
            state = PreviousPolicyState.VALUE,
            value = policy.activeRestrictions().joinToString(LIST_SEPARATOR),
            nowWallMs = nowWallMs,
        )
        val autoTime = policy.readAutoTime()
        writeLedgerOnce(
            key = LEDGER_AUTO_TIME,
            state = if (autoTime == null) PreviousPolicyState.UNKNOWN else PreviousPolicyState.VALUE,
            value = autoTime?.toString(),
            nowWallMs = nowWallMs,
        )
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
        val nowWallMs = acceptedNowLocked()
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
            val dayId = boundaryFor(updated.toSettings()).dayIdAt(acceptedNowLocked())
            dao.dayOrCreate(dayId, updated.dailyAllowanceMs)
            dao.setBaseAllowance(dayId, updated.dailyAllowanceMs)
        }
        return tick(Trigger.ADMIN_ACTION)
    }

    /** Preview the effect of an allowance change before it is applied. */
    suspend fun previewAllowanceChange(newDailyAllowanceMs: Long): Pair<Long, Long> = mutex.withLock {
        val settings = (dao.settings() ?: seedDefaultSettings(clock.wallTimeMs())).toSettings()
        val today = dao.dayOrCreate(
            boundaryFor(settings).dayIdAt(acceptedNowLocked()),
            settings.dailyAllowanceMs,
        ).toDayBudget()
        val updated = DayBudget(today.dayId, newDailyAllowanceMs, today.chargedMs, today.extraGrantedMs)
        today.remainingMs to updated.remainingMs
    }

    /**
     * Turn enforcement on, but only if the capabilities it depends on are genuinely present.
     *
     * The prerequisites are checked here, at the operation boundary, rather than being left
     * to whether the UI happened to hide a button. Self-protection is applied and its result
     * kept, so a failed anti-removal control shows up as an unsatisfied readiness line
     * instead of being hidden behind a green "Protected".
     */
    suspend fun completeSetup(): SetupOutcome {
        val before = tick(Trigger.ADMIN_ACTION)
        val missing = before.unmetPrerequisites.map { it.label }
        if (missing.isNotEmpty()) return SetupOutcome(false, missing, lastSelfProtection)

        mutex.withLock {
            val nowWallMs = acceptedNowLocked()
            recordProtectionLedger(nowWallMs)
            val previouslyControlled = dao.ledgerEntry(LEDGER_USER_CONTROL)
                ?.takeIf { it.previous == PreviousPolicyState.VALUE }
                ?.previousValue
                ?.split(LIST_SEPARATOR)
                ?.filter { it.isNotBlank() }
                .orEmpty()
            val report = policy.protectSelf(enabled = true, otherPackages = previouslyControlled)
            lastSelfProtection = report
            if (!report.matchesRequest) {
                recordDiagnostic(
                    type = "self_protection_incomplete",
                    detail = report.error ?: "uninstallBlocked=${report.uninstallBlocked} userControl=${report.userControlDisabled}",
                    nowWallMs = nowWallMs,
                )
            }
            val existing = dao.settings() ?: seedDefaultSettings(nowWallMs)
            dao.upsertSettings(existing.copy(setupCompleted = true, maintenanceMode = false))
        }
        val after = tick(Trigger.ADMIN_ACTION)
        return SetupOutcome(after.protectionActive, emptyList(), lastSelfProtection)
    }

    /**
     * Clear a latched recovery state after the PIN holder has acknowledged it.
     *
     * The hole in the history is NOT filled in with a guess: the checkpoint is re-anchored
     * to now and the request cleared, and the day's charged total is left exactly as it
     * stands. Re-anchoring also adopts the current system clock, which is what allows a
     * genuinely corrected clock to be accepted -- deliberately an authorized act, since the
     * new anchor decides which accounting day is current.
     */
    suspend fun acknowledgeRecovery(): LimiterStatus {
        mutex.withLock {
            val nowWallMs = clock.wallTimeMs()
            val current = dao.checkpoint()?.toCheckpoint()
                ?: Checkpoint.initial(clock.bootId(), clock.elapsedRealtimeMs(), nowWallMs)
            val acknowledged = current.recovery
            tracker = VisibleTargetTracker(TargetPackages.ALL)
            tracker.startAt(nowWallMs)
            observerRestored = true
            dao.clearPendingEvents()
            dao.upsertCheckpoint(
                CheckpointEntity.from(
                    current.copy(
                        bootId = clock.bootId(),
                        lastElapsedMs = clock.elapsedRealtimeMs(),
                        lastWallMs = nowWallMs,
                        settledWallMs = nowWallMs,
                        targetVisible = false,
                        usageCursorWallMs = nowWallMs,
                        acceptedDriftMs = 0L,
                        recovery = null,
                        trackerState = tracker.exportState().encode(),
                    )
                )
            )
            recordDiagnostic(
                type = "recovery_acknowledged",
                detail = acknowledged?.reason ?: "no outstanding request",
                nowWallMs = nowWallMs,
            )
        }
        return tick(Trigger.ADMIN_ACTION)
    }

    suspend fun applyHardening(options: HardeningOptions): Set<String> = mutex.withLock {
        val nowWallMs = acceptedNowLocked()
        recordHardeningLedger(nowWallMs)
        val active = policy.applyHardening(options)
        recordDiagnostic("hardening_applied", active.joinToString().ifEmpty { "none" }, nowWallMs)
        active
    }

    /**
     * PIN-authorized maintenance: put the device back the way it was, one verified stage at
     * a time.
     *
     * The order is forced by what can still be undone. Maintenance mode is persisted first,
     * so the poll loop stops asserting policy and cannot race the restore. Device ownership
     * is released last and only when every earlier stage verified, because once it is gone
     * nothing above it can be retried. A partial failure therefore leaves ownership intact
     * and the device in a recoverable maintenance state naming the step that failed.
     *
     * [releaseUnknownPackages] is the operator's explicit decision about packages whose
     * pre-DoomStop suspension state the platform would not report. Without it those are left
     * exactly as they are and the ambiguity is reported rather than guessed away.
     */
    suspend fun restoreDevice(
        relinquishOwnership: Boolean,
        releaseUnknownPackages: Boolean = false,
    ): RestoreReport = mutex.withLock {
        val nowWallMs = acceptedNowLocked()
        val settings = dao.settings() ?: seedDefaultSettings(nowWallMs)
        dao.upsertSettings(settings.copy(maintenanceMode = true))
        bootMarker.record(protectionEnabled = false, targetsSuspended = false, nowWallMs = nowWallMs)
        lastAppliedSuspendTargets = null
        lastChromeReport = null

        val stages = mutableListOf<RestoreStage>()
        val (suspensionStage, ambiguous) = restoreSuspension(releaseUnknownPackages)
        stages += suspensionStage
        if (suspensionStage.succeeded) stages += restoreChrome()
        if (stages.all { it.succeeded }) stages += restoreHardening()
        if (stages.all { it.succeeded }) stages += restoreSelfProtection()

        var ownership: Boolean? = null
        if (stages.all { it.succeeded }) {
            if (relinquishOwnership) {
                val result = policy.relinquishDeviceOwnership()
                ownership = result.isSuccess
                stages += RestoreStage(
                    name = "Release device ownership",
                    succeeded = result.isSuccess,
                    detail = result.exceptionOrNull()?.message,
                )
            }
        }

        val completed = stages.all { it.succeeded }
        if (completed) {
            dao.upsertSettings(
                (dao.settings() ?: settings).copy(setupCompleted = false, maintenanceMode = false)
            )
            lastSelfProtection = null
        }
        recordDiagnostic(
            type = "device_restore",
            detail = "completed=$completed relinquish=$relinquishOwnership failures=${stages.count { !it.succeeded }}",
            nowWallMs = nowWallMs,
        )

        RestoreReport(
            stages = stages,
            ownershipRelinquished = ownership,
            maintenanceRetained = !completed,
            ambiguousPackages = ambiguous,
        )
    }

    /** Abandon a maintenance attempt and start enforcing again with the current settings. */
    suspend fun cancelMaintenance(): LimiterStatus {
        mutex.withLock {
            val settings = dao.settings() ?: seedDefaultSettings(clock.wallTimeMs())
            dao.upsertSettings(settings.copy(maintenanceMode = false))
            lastAppliedSuspendTargets = null
            lastChromeReport = null
            recordDiagnostic("maintenance_cancelled", "enforcement resumed", clock.wallTimeMs())
        }
        return tick(Trigger.ADMIN_ACTION)
    }

    private suspend fun restoreSuspension(releaseUnknown: Boolean): Pair<RestoreStage, List<String>> {
        val entries = dao.ledger().filter { it.key.startsWith(LEDGER_SUSPENDED_PREFIX) }
        val ambiguous = entries
            .filter { it.previous != PreviousPolicyState.VALUE }
            .map { it.key.removePrefix(LEDGER_SUSPENDED_PREFIX) }
            .sorted()

        // Only packages this app actually made suspended are released. One that was already
        // suspended before DoomStop existed stays suspended.
        val ours = entries
            .filter { it.previous == PreviousPolicyState.VALUE && it.previousValue == "false" }
            .map { it.key.removePrefix(LEDGER_SUSPENDED_PREFIX) }
        val release = (if (releaseUnknown) ours + ambiguous else ours).toSet()

        if (release.isEmpty() && ambiguous.isEmpty()) {
            return RestoreStage("Release suspended apps", true, "nothing to release") to ambiguous
        }
        val report = policy.release(release)
        val blockedByAmbiguity = ambiguous.isNotEmpty() && !releaseUnknown
        val detail = when {
            !report.allApplied -> "failed: " + report.failures.joinToString { it.packageName }
            blockedByAmbiguity -> "prior state unknown for ${ambiguous.size} package(s): ${ambiguous.joinToString()}"
            else -> "released ${release.size} package(s)"
        }
        return RestoreStage(
            name = "Release suspended apps",
            succeeded = report.allApplied && !blockedByAmbiguity,
            detail = detail,
        ) to ambiguous
    }

    private suspend fun restoreChrome(): RestoreStage {
        val entry = dao.ledgerEntry(LEDGER_CHROME_BLOCKLIST)
            ?: return RestoreStage("Restore Chrome site policy", true, "this app never wrote it")
        if (entry.previous == PreviousPolicyState.UNKNOWN) {
            return RestoreStage(
                name = "Restore Chrome site policy",
                succeeded = false,
                detail = "the value before DoomStop could not be read, so it will not be guessed",
            )
        }
        val result = policy.restoreChromeBlocklist(entry.previousValue)
        return RestoreStage(
            name = "Restore Chrome site policy",
            succeeded = result.isSuccess,
            detail = result.exceptionOrNull()?.message,
        )
    }

    private suspend fun restoreHardening(): RestoreStage {
        val restrictions = dao.ledgerEntry(LEDGER_RESTRICTIONS)
        val autoTime = dao.ledgerEntry(LEDGER_AUTO_TIME)
        if (restrictions == null) {
            return RestoreStage("Restore device restrictions", true, "this app never set any")
        }
        val previous = restrictions.previousValue?.split(LIST_SEPARATOR)?.filter { it.isNotBlank() }?.toSet()
        val previousAutoTime = autoTime
            ?.takeIf { it.previous == PreviousPolicyState.VALUE }
            ?.previousValue
            ?.toBooleanStrictOrNull()
        val result = policy.restoreHardening(previous, previousAutoTime)
        return RestoreStage(
            name = "Restore device restrictions",
            succeeded = result.isSuccess,
            detail = result.exceptionOrNull()?.message
                ?: if (previousAutoTime == null && autoTime != null) "automatic time left as it is (previous value unknown)" else null,
        )
    }

    private suspend fun restoreSelfProtection(): RestoreStage {
        val previouslyControlled = dao.ledgerEntry(LEDGER_USER_CONTROL)
            ?.takeIf { it.previous == PreviousPolicyState.VALUE }
            ?.previousValue
            ?.split(LIST_SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val report = policy.protectSelf(enabled = false, otherPackages = previouslyControlled)
        lastSelfProtection = report
        val succeeded = report.matchesRequest
        return RestoreStage(
            name = "Drop this app's own protection",
            succeeded = succeeded,
            detail = report.error
                ?: if (!succeeded) "uninstall block or task-manager control did not clear" else null,
        )
    }

    // -- helpers ------------------------------------------------------------------------------

    /**
     * The instant accounting is willing to call "now", from inside the lock.
     *
     * Authorized actions use this rather than the raw system clock so that a jumped clock
     * cannot pick the accounting day for an extension or a settings change either.
     */
    private suspend fun acceptedNowLocked(): Long {
        val previous = dao.checkpoint()?.toCheckpoint() ?: return clock.wallTimeMs()
        return BudgetEngine.resolveClock(
            previous = previous,
            tick = TickInput(
                bootId = clock.bootId(),
                elapsedMs = clock.elapsedRealtimeMs(),
                reportedWallMs = clock.wallTimeMs(),
                eventsAvailable = true,
            ),
        ).acceptedNowWallMs
    }

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

    /**
     * This app's own anti-removal controls, read back from the platform every pass.
     *
     * Deliberately not the cached result of the last time they were applied: that lives in
     * memory, so after a reboot the app would have reported its own protection as missing
     * until setup was run again. Any error from the last write is carried through, because
     * the state alone would not say why it is wrong.
     */
    private fun currentSelfProtection(requested: Boolean): SelfProtectionReport? {
        if (!policy.isDeviceOwner) return lastSelfProtection
        val uninstallBlocked = policy.readUninstallBlocked() ?: return lastSelfProtection
        val controlled = policy.readUserControlDisabledPackages() ?: return lastSelfProtection
        return SelfProtectionReport(
            requested = requested,
            uninstallBlocked = uninstallBlocked,
            userControlDisabled = context.packageName in controlled,
            error = lastSelfProtection?.error,
        )
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
        checkpoint: Checkpoint,
        health: MonitorHealth,
        nowWallMs: Long,
    ): LimiterStatus {
        val status = LimiterStatus(
            setupCompleted = settingsEntity.setupCompleted,
            maintenanceMode = settingsEntity.maintenanceMode,
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
            checkpointState = checkpoint.state,
            recovery = checkpoint.recovery,
            health = health,
            installedTargets = policy.installedTargets(),
            installedBlockedBrowsers = policy.installedBlockedBrowsers(),
            suspension = lastSuspensionReport,
            chrome = lastChromeReport,
            selfProtection = currentSelfProtection(
                requested = settingsEntity.setupCompleted && !settingsEntity.maintenanceMode,
            ),
            activeRestrictions = policy.activeRestrictions(),
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

        private const val LIST_SEPARATOR = ","

        /** Synthetic events carrying a live screen observation into the replayable stream. */
        private const val LIVE_OBSERVATION_PACKAGE = "android"
        private const val LIVE_OBSERVATION_CLASS = "doomstop.live"

        const val LEDGER_CHROME_BLOCKLIST = "chrome.${BlockedSites.KEY_URL_BLOCKLIST}"
        const val LEDGER_SUSPENDED_PREFIX = "suspended."
        const val LEDGER_USER_CONTROL = "self.userControlDisabledPackages"
        const val LEDGER_UNINSTALL_BLOCKED = "self.uninstallBlocked"
        const val LEDGER_RESTRICTIONS = "hardening.userRestrictions"
        const val LEDGER_AUTO_TIME = "hardening.autoTimeEnabled"
    }
}
