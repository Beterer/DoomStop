package dev.personal.doomstop.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.personal.doomstop.DoomStopApp
import dev.personal.doomstop.admin.HardeningOptions
import dev.personal.doomstop.core.LimiterStatus
import dev.personal.doomstop.core.RestoreReport
import dev.personal.doomstop.core.Trigger
import dev.personal.doomstop.monitor.UsageMonitorService
import dev.personal.doomstop.security.AuthorizationTicket
import dev.personal.doomstop.security.PinResult
import dev.personal.doomstop.security.ThrottleState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the user is being asked for a PIN in order to do. */
enum class PinPurpose { REQUEST_EXTENSION, OPEN_ADMIN }

sealed interface Screen {
    data object Status : Screen
    data object Setup : Screen
    data class Pin(val purpose: PinPurpose) : Screen
    data object Admin : Screen
    data object ChangePin : Screen
}

/** Transient message shown once and dismissed; never contains a PIN. */
data class Toast(val text: String, val isError: Boolean = false)

class LimiterViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as DoomStopApp

    val status: StateFlow<LimiterStatus> = app.coordinator.status

    private val _screen = MutableStateFlow<Screen>(Screen.Status)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _pinError = MutableStateFlow<String?>(null)
    val pinError: StateFlow<String?> = _pinError.asStateFlow()

    private val _throttle = MutableStateFlow(ThrottleState(0, 0))
    val throttle: StateFlow<ThrottleState> = _throttle.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _toast = MutableStateFlow<Toast?>(null)
    val toast: StateFlow<Toast?> = _toast.asStateFlow()

    private val _restoreReport = MutableStateFlow<RestoreReport?>(null)
    val restoreReport: StateFlow<RestoreReport?> = _restoreReport.asStateFlow()

    /**
     * Authentication is held only while the admin screen is open, only briefly, and only
     * while the app is actually in front of the person who authenticated.
     *
     * The expiry is monotonic and is checked at every protected operation rather than being
     * left to a timer coroutine, and [onMovedToBackground] revokes it outright. Without
     * that, a trusted person could enter the PIN, hand the phone back, and the phone's own
     * user could return to a still-open admin session.
     */
    private var adminSession: AuthorizationTicket? = null
    private var adminExpiresAtElapsedMs = 0L
    private var adminIdleJob: Job? = null

    /**
     * Bumped whenever authorization is dropped. A PIN verification that was already running
     * carries the generation it started with, so a verification that completes after the app
     * went to the background cannot open a session.
     */
    private var authorizationGeneration = 0

    init {
        viewModelScope.launch { app.coordinator.tick(Trigger.UI) }
        viewModelScope.launch { refreshThrottleLoop() }
    }

    /** The PIN screen shows a live countdown, so the cooldown has to tick down on its own. */
    private suspend fun refreshThrottleLoop() {
        while (true) {
            _throttle.value = app.pinManager.throttleState()
            delay(1_000)
        }
    }

    fun show(screen: Screen) {
        if (screen !is Screen.Admin && screen !is Screen.ChangePin) clearAdminSession()
        _pinError.value = null
        _screen.value = screen
    }

    fun dismissToast() {
        _toast.value = null
    }

    fun dismissRestoreReport() {
        _restoreReport.value = null
    }

    /**
     * Called when the app stops being visible: Home, app switch, screen off, or lock.
     *
     * The activity distinguishes a configuration change from a real departure, so rotating
     * the phone does not sign the trusted person out mid-sentence while genuinely leaving
     * does.
     */
    fun onMovedToBackground() {
        val hadSession = adminSession != null
        clearAdminSession()
        if (_screen.value is Screen.Admin || _screen.value is Screen.ChangePin || _screen.value is Screen.Pin) {
            _screen.value = Screen.Status
            _pinError.value = null
        }
        if (hadSession) _toast.value = Toast("Settings were locked when DoomStop left the screen")
    }

    // -- PIN ------------------------------------------------------------------------------

    fun submitPin(pin: String, purpose: PinPurpose) {
        if (_busy.value) return
        _busy.value = true
        val generation = authorizationGeneration
        viewModelScope.launch {
            try {
                when (val result = app.pinManager.verify(pin)) {
                    is PinResult.Success -> {
                        if (generation != authorizationGeneration) {
                            // The app left the foreground while the key was being derived.
                            _pinError.value = "Enter the PIN again"
                        } else {
                            onPinAccepted(result.ticket, purpose)
                        }
                    }

                    is PinResult.Wrong -> _pinError.value = wrongPinMessage(result.consecutiveFailures)
                    is PinResult.Throttled ->
                        _pinError.value = "Locked for ${formatDuration(result.remainingMs)}"

                    PinResult.NotSet -> _pinError.value = "No PIN has been set yet"
                    is PinResult.Malformed -> _pinError.value = result.reason
                }
                _throttle.value = app.pinManager.throttleState()
            } finally {
                _busy.value = false
            }
        }
    }

    private fun wrongPinMessage(failures: Int): String {
        val remaining = 5 - failures
        return if (remaining > 0) "Incorrect PIN · $remaining attempts before a delay"
        else "Incorrect PIN"
    }

    private suspend fun onPinAccepted(ticket: AuthorizationTicket, purpose: PinPurpose) {
        _pinError.value = null
        when (purpose) {
            PinPurpose.REQUEST_EXTENSION -> {
                // The ticket is redeemed exactly once; a repeated tap cannot duplicate it.
                val granted = app.coordinator.grantExtension(ticket)
                _toast.value = if (granted) {
                    Toast("Added ${formatDuration(status.value.settings.extensionMs)}. Websites stay blocked.")
                } else {
                    Toast("That authorization was already used", isError = true)
                }
                _screen.value = Screen.Status
            }

            PinPurpose.OPEN_ADMIN -> {
                adminSession = ticket
                startAdminIdleTimer()
                _screen.value = Screen.Admin
            }
        }
    }

    private fun startAdminIdleTimer() {
        adminExpiresAtElapsedMs = SystemClock.elapsedRealtime() + ADMIN_IDLE_TIMEOUT_MS
        adminIdleJob?.cancel()
        adminIdleJob = viewModelScope.launch {
            delay(ADMIN_IDLE_TIMEOUT_MS)
            if (!adminSessionValid()) return@launch
            clearAdminSession()
            _screen.value = Screen.Status
            _toast.value = Toast("Signed out of settings after inactivity")
        }
    }

    fun noteAdminInteraction() {
        if (adminSessionValid()) startAdminIdleTimer()
    }

    private fun adminSessionValid(): Boolean =
        adminSession != null && SystemClock.elapsedRealtime() < adminExpiresAtElapsedMs

    private fun clearAdminSession() {
        adminSession = null
        adminExpiresAtElapsedMs = 0L
        adminIdleJob?.cancel()
        adminIdleJob = null
        authorizationGeneration++
    }

    override fun onCleared() {
        clearAdminSession()
    }

    // -- setup ----------------------------------------------------------------------------

    /** First-time PIN creation. Refused by [dev.personal.doomstop.security.PinManager] once one exists. */
    fun setInitialPin(pin: String, confirmation: String) {
        if (pin != confirmation) {
            _pinError.value = "The two entries do not match"
            return
        }
        _busy.value = true
        viewModelScope.launch {
            try {
                app.pinManager.setInitialPin(pin)
                    .onSuccess {
                        _pinError.value = null
                        _toast.value = Toast("PIN saved")
                        _screen.value = Screen.Setup
                        app.coordinator.tick(Trigger.ADMIN_ACTION)
                    }
                    .onFailure { _pinError.value = it.message }
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Replace an existing PIN. Authorization is checked when the request is made AND again
     * at the moment the new verifier is written, because deriving it is slow enough for the
     * session to end in between.
     */
    fun changePin(pin: String, confirmation: String) {
        if (pin != confirmation) {
            _pinError.value = "The two entries do not match"
            return
        }
        if (!adminSessionValid()) {
            clearAdminSession()
            _toast.value = Toast("Enter the PIN again to change it", isError = true)
            _screen.value = Screen.Pin(PinPurpose.OPEN_ADMIN)
            return
        }
        _busy.value = true
        val generation = authorizationGeneration
        viewModelScope.launch {
            try {
                app.pinManager
                    .replacePin(pin) { generation == authorizationGeneration && adminSessionValid() }
                    .onSuccess {
                        _pinError.value = null
                        _toast.value = Toast("PIN changed")
                        // The old ticket authorised the old secret; require a fresh entry.
                        clearAdminSession()
                        _screen.value = Screen.Status
                        app.coordinator.tick(Trigger.ADMIN_ACTION)
                    }
                    .onFailure { _pinError.value = it.message }
            } finally {
                _busy.value = false
            }
        }
    }

    fun completeSetup() = launchBusy {
        val outcome = app.coordinator.completeSetup()
        _toast.value = when {
            outcome.missing.isNotEmpty() ->
                Toast("Not started: ${outcome.missing.joinToString()}", isError = true)

            outcome.protectionActive -> Toast("Protection is active")
            else -> Toast("Setup saved, but protection is incomplete — see the checklist", isError = true)
        }
        if (outcome.missing.isEmpty()) _screen.value = Screen.Status
    }

    fun refresh() = launchBusy { app.coordinator.tick(Trigger.UI) }

    fun startMonitor() {
        UsageMonitorService.start(getApplication())
        refresh()
    }

    // -- admin actions ----------------------------------------------------------------------

    fun updateSettings(dailyAllowanceMs: Long, extensionMs: Long, zoneId: String) = requireAdmin {
        app.coordinator.updateSettings(dailyAllowanceMs, extensionMs, zoneId)
        _toast.value = Toast("Limits updated")
    }

    suspend fun previewAllowance(newDailyMs: Long): Pair<Long, Long> =
        app.coordinator.previewAllowanceChange(newDailyMs)

    fun applyHardening(options: HardeningOptions) = requireAdmin {
        val active = app.coordinator.applyHardening(options)
        _toast.value = Toast(if (active.isEmpty()) "No extra restrictions active" else "Applied: ${active.size}")
    }

    fun acknowledgeRecovery() = requireAdmin {
        app.coordinator.acknowledgeRecovery()
        _toast.value = Toast("Recovery acknowledged; charged time was preserved")
    }

    fun restoreDevice(relinquishOwnership: Boolean, releaseUnknownPackages: Boolean = false) = requireAdmin {
        val report: RestoreReport = app.coordinator.restoreDevice(relinquishOwnership, releaseUnknownPackages)
        _restoreReport.value = report
        _toast.value = when {
            !report.completed ->
                Toast("Restore stopped: ${report.failures.first().name}", isError = true)

            report.ownershipRelinquished == true ->
                Toast("Policies restored and device ownership released")

            else -> Toast("Policies restored; DoomStop is still the device owner")
        }
        if (report.completed) _screen.value = Screen.Status
    }

    fun cancelMaintenance() = requireAdmin {
        app.coordinator.cancelMaintenance()
        _restoreReport.value = null
        _toast.value = Toast("Maintenance cancelled; enforcement resumed")
    }

    // -- helpers -----------------------------------------------------------------------------

    /**
     * Every protected action re-checks authorization here, at the boundary, and again after
     * the previous one may have been invalidated -- rather than trusting that the screen it
     * was invoked from could only have been reached with a valid session.
     */
    private fun requireAdmin(block: suspend () -> Unit) {
        if (!adminSessionValid()) {
            clearAdminSession()
            _toast.value = Toast("Enter the PIN again to make changes", isError = true)
            _screen.value = Screen.Pin(PinPurpose.OPEN_ADMIN)
            return
        }
        noteAdminInteraction()
        val generation = authorizationGeneration
        launchBusy {
            if (generation != authorizationGeneration || !adminSessionValid()) {
                _toast.value = Toast("Authorization expired before that could run", isError = true)
                _screen.value = Screen.Status
                return@launchBusy
            }
            block()
        }
    }

    private fun launchBusy(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _toast.value = Toast(e.message ?: "Something went wrong", isError = true)
            } finally {
                _busy.value = false
            }
        }
    }

    private companion object {
        /** Short by design: there is no permanent remembered unlock anywhere in this app. */
        const val ADMIN_IDLE_TIMEOUT_MS = 2L * 60L * 1000L
    }
}
