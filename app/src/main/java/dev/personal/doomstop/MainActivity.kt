package dev.personal.doomstop

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.personal.doomstop.monitor.AppPermissions
import dev.personal.doomstop.monitor.UsageMonitorService
import dev.personal.doomstop.ui.AdminScreen
import dev.personal.doomstop.ui.DoomStopTheme
import dev.personal.doomstop.ui.LimiterViewModel
import dev.personal.doomstop.ui.PinPurpose
import dev.personal.doomstop.ui.PinScreen
import dev.personal.doomstop.ui.Screen
import dev.personal.doomstop.ui.SetupScreen
import dev.personal.doomstop.ui.StatusScreen

class MainActivity : ComponentActivity() {

    private val viewModel: LimiterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opening the limiter is also the reliable way to get the monitor back after it has
        // been stopped, so this is not merely a convenience.
        UsageMonitorService.start(this)

        setContent {
            DoomStopTheme {
                Surface(Modifier.fillMaxSize()) {
                    DoomStopApp(
                        viewModel = viewModel,
                        onSecureWindow = ::setSecureWindow,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        UsageMonitorService.start(this)
    }

    /**
     * Losing the foreground ends the admin session.
     *
     * Without this, a trusted person could enter the PIN, press Home, hand the phone back,
     * and the phone's own user could return to a still-authorised settings screen. A
     * configuration change is explicitly distinguished, so rotating the device does not sign
     * anyone out -- while genuinely leaving, locking or switching apps does.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) viewModel.onMovedToBackground()
    }

    /**
     * FLAG_SECURE on the PIN and settings screens: no screenshots, no screen recording, and
     * no thumbnail of those screens in recents.
     */
    private fun setSecureWindow(secure: Boolean) {
        if (secure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

@Composable
private fun DoomStopApp(viewModel: LimiterViewModel, onSecureWindow: (Boolean) -> Unit) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val pinError by viewModel.pinError.collectAsStateWithLifecycle()
    val throttle by viewModel.throttle.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val restoreReport by viewModel.restoreReport.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // The PIN and settings screens hold sensitive interaction; the status screen does not.
    LaunchedEffect(screen) {
        onSecureWindow(screen is Screen.Pin || screen is Screen.Admin || screen is Screen.ChangePin)
    }

    LaunchedEffect(toast) {
        toast?.let {
            snackbarHostState.showSnackbar(it.text)
            viewModel.dismissToast()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val current = screen) {
                Screen.Status -> StatusScreen(
                    status = status,
                    onRequestMoreTime = { viewModel.show(Screen.Pin(PinPurpose.REQUEST_EXTENSION)) },
                    onOpenSettings = { viewModel.show(Screen.Pin(PinPurpose.OPEN_ADMIN)) },
                    onOpenSetup = { viewModel.show(Screen.Setup) },
                )

                Screen.Setup -> SetupScreen(
                    status = status,
                    onGrantUsageAccess = { context.startActivity(AppPermissions.usageAccessSettingsIntent(context)) },
                    onAllowExactAlarms = { context.startActivity(AppPermissions.exactAlarmSettingsIntent(context)) },
                    onOpenAccessibilitySettings = { context.startActivity(AppPermissions.accessibilitySettingsIntent()) },
                    onStartMonitor = viewModel::startMonitor,
                    onSetPin = { viewModel.show(Screen.ChangePin) },
                    onRefresh = viewModel::refresh,
                    onFinishSetup = viewModel::completeSetup,
                    onBack = { viewModel.show(Screen.Status) },
                )

                is Screen.Pin -> PinScreen(
                    title = when (current.purpose) {
                        PinPurpose.REQUEST_EXTENSION -> "Authorize more time"
                        PinPurpose.OPEN_ADMIN -> "Open settings"
                    },
                    subtitle = when (current.purpose) {
                        PinPurpose.REQUEST_EXTENSION ->
                            "The trusted person enters the PIN to add one extension. Websites stay blocked."

                        PinPurpose.OPEN_ADMIN -> "The trusted person enters the PIN to change protected settings."
                    },
                    error = pinError,
                    throttle = throttle,
                    busy = busy,
                    onSubmit = { viewModel.submitPin(it, current.purpose) },
                    onCancel = { viewModel.show(Screen.Status) },
                )

                Screen.ChangePin -> ChangePinFlow(
                    replacing = status.pinSet,
                    error = pinError,
                    busy = busy,
                    onDone = { first, second ->
                        // Creating the first PIN and replacing an existing one are different
                        // guarded operations; the UI never decides which guard applies.
                        if (status.pinSet) viewModel.changePin(first, second)
                        else viewModel.setInitialPin(first, second)
                    },
                    onCancel = { viewModel.show(if (status.setupCompleted) Screen.Status else Screen.Setup) },
                )

                Screen.Admin -> AdminScreen(
                    status = status,
                    busy = busy,
                    restoreReport = restoreReport,
                    onInteraction = viewModel::noteAdminInteraction,
                    previewAllowance = viewModel::previewAllowance,
                    onSave = viewModel::updateSettings,
                    onChangePin = { viewModel.show(Screen.ChangePin) },
                    onApplyHardening = viewModel::applyHardening,
                    onAcknowledgeRecovery = viewModel::acknowledgeRecovery,
                    onRestoreDevice = viewModel::restoreDevice,
                    onCancelMaintenance = viewModel::cancelMaintenance,
                    onBack = { viewModel.show(Screen.Status) },
                )
            }
        }
    }
}

/** Enter the new PIN twice. The first entry is held only in this composable's state. */
@Composable
private fun ChangePinFlow(
    replacing: Boolean,
    error: String?,
    busy: Boolean,
    onDone: (String, String) -> Unit,
    onCancel: () -> Unit,
) {
    var firstEntry by remember { mutableStateOf<String?>(null) }
    val first = firstEntry
    PinScreen(
        title = when {
            first != null -> "Confirm the PIN"
            replacing -> "Choose a new PIN"
            else -> "Choose a PIN"
        },
        subtitle = if (first == null) {
            "Six digits, leading zeros allowed. The phone's user should not know it."
        } else {
            "Enter the same six digits again."
        },
        error = error,
        throttle = dev.personal.doomstop.security.ThrottleState(0, 0),
        busy = busy,
        confirmLabel = if (first == null) "Next" else "Save",
        onSubmit = { entered ->
            if (first == null) firstEntry = entered else onDone(first, entered)
        },
        onCancel = { firstEntry = null; onCancel() },
    )
}
