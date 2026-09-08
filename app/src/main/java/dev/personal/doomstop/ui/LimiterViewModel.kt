package dev.personal.doomstop.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.personal.doomstop.DoomStopApp
import dev.personal.doomstop.admin.HardeningOptions
import dev.personal.doomstop.core.LimiterStatus
import dev.personal.doomstop.core.RestoreReport
import dev.personal.doomstop.core.Trigger
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

    /**
     * Authentication is held only while the admin screen is open, and only briefly.
     * There is deliberately no "remember me": leaving the screen or going idle drops it.
     */
    private var adminSession: AuthorizationTicket? = null
    private var adminIdleJob: Job? = null

    init {
        viewModelScope.launch { app.coordinator.tick(Trigger.UI) }
        viewModelScope.launch { refreshThrottleLoop() }
    }

    /** The PIN screen shows a live countdown, so the cooldown has to tick down on its own. */
    private suspend fun refreshThrottleLoop() {
        while (true) {
            _throttle.value = app.pinManager.throttleState(System.currentTimeMillis())
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

    // -- PIN ------------------------------------------------------------------------------

    fun submitPin(pin: String, purpose: PinPurpose) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                when (val result = app.pinManager.verify(pin, System.currentTimeMillis())) {
                    is PinResult.Success -> onPinAccepted(result.ticket, purpose)
                    is PinResult.Wrong -> _pinError.value = wrongPinMessage(result.consecutiveFailures)
                    is PinResult.Throttled ->
                        _pinError.value = "Locked for ${formatDuration(result.remainingMs)}"

                    PinResult.NotSet -> _pinError.value = "No PIN has been set yet"
                    is PinResult.Malformed -> _pinError.value = result.reason
                }
                _throttle.value = app.pinManager.throttleState(System.currentTimeMillis())
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
        adminIdleJob?.cancel()
        adminIdleJob = viewModelScope.launch {
            delay(ADMIN_IDLE_TIMEOUT_MS)
            clearAdminSession()
            _screen.value = Screen.Status
            _toast.value = Toast("Signed out of settings after inactivity")
        }
    }

    fun noteAdminInteraction() {
        if (adminSession != null) startAdminIdleTimer()
    }

    private fun clearAdminSession() {
        adminSession = null
        adminIdleJob?.cancel()
        adminIdleJob = null
    }

    override fun onCleared() {
        clearAdminSession()
    }

    // -- setup ----------------------------------------------------------------------------

    fun setInitialPin(pin: String, confirmation: String) {
        if (pin != confirmation) {
            _pinError.value = "The two entries do not match"
            return
        }
        _busy.value = true
        viewModelScope.launch {
            try {
                app.pinManager.setPin(pin)
                    .onSuccess {
                        _pinError.value = null
                        _toast.value = Toast("PIN saved")
                        _screen.value = if (status.value.setupCompleted) Screen.Admin else Screen.Setup
                        app.coordinator.tick(Trigger.ADMIN_ACTION)
                    }
                    .onFailure { _pinError.value = it.message }
            } finally {
                _busy.value = false
            }
        }
    }

    fun completeSetup() = launchBusy {
        val result = app.coordinator.completeSetup()
        _toast.value = if (result.protectionActive) {
            Toast("Protection is active")
        } else {
            Toast("Setup saved, but protection is incomplete — see the checklist", isError = true)
        }
        _screen.value = Screen.Status
    }

    fun refresh() = launchBusy { app.coordinator.tick(Trigger.UI) }

    fun startMonitor() {
        dev.personal.doomstop.monitor.UsageMonitorService.start(getApplication())
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

    fun restoreDevice(relinquishOwnership: Boolean) = requireAdmin {
        val report: RestoreReport = app.coordinator.restoreDevice(relinquishOwnership)
        _toast.value = when {
            report.error != null -> Toast("Restore incomplete: ${report.error}", isError = true)
            relinquishOwnership && report.ownershipRelinquished == true ->
                Toast("Policies restored and device ownership released")

            else -> Toast("Policies restored; DoomStop is still the device owner")
        }
        _screen.value = Screen.Status
    }

    // -- helpers -----------------------------------------------------------------------------

    private fun requireAdmin(block: suspend () -> Unit) {
        if (adminSession == null) {
            _toast.value = Toast("Enter the PIN again to make changes", isError = true)
            _screen.value = Screen.Pin(PinPurpose.OPEN_ADMIN)
            return
        }
        noteAdminInteraction()
        launchBusy(block)
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
