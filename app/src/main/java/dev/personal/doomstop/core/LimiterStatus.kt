package dev.personal.doomstop.core

import dev.personal.doomstop.admin.ChromePolicyReport
import dev.personal.doomstop.admin.SuspensionReport
import dev.personal.doomstop.domain.CheckpointState
import dev.personal.doomstop.domain.EnforcementDecision
import dev.personal.doomstop.domain.EnforcementReason
import dev.personal.doomstop.domain.LimiterSettings
import dev.personal.doomstop.domain.MonitorHealth
import dev.personal.doomstop.monitor.TrackerSnapshot

/** Why the app is not fully protected, in terms a person can act on. */
data class ReadinessItem(
    val label: String,
    val satisfied: Boolean,
    val remedy: String?,
)

/**
 * Everything the UI and the notification need, computed once per tick by the coordinator.
 *
 * Deliberately explicit about capability: [protectionActive] is only true when every
 * required capability is genuinely present. A missing capability must never render as
 * "Protected".
 */
data class LimiterStatus(
    val setupCompleted: Boolean,
    val settings: LimiterSettings,
    val dayId: String,
    val remainingMs: Long,
    val totalAllowanceMs: Long,
    val baseAllowanceMs: Long,
    val chargedMs: Long,
    val extraGrantedMs: Long,
    val nextResetWallMs: Long,
    val targetVisible: Boolean,
    val enforcement: EnforcementDecision,
    val checkpointState: CheckpointState,
    val health: MonitorHealth,
    val installedTargets: List<String>,
    val installedBlockedBrowsers: List<String>,
    val suspension: SuspensionReport?,
    val chrome: ChromePolicyReport?,
    val tracker: TrackerSnapshot?,
    val pinSet: Boolean,
    val exactAlarmsAllowed: Boolean,
    val lastTickWallMs: Long,
    val recentAnomalies: List<String>,
) {
    /**
     * True only when everything needed to actually enforce the limit is in place. Anything
     * less is reported as a specific missing capability rather than a hedge.
     */
    val protectionActive: Boolean
        get() = setupCompleted &&
            health.deviceOwner &&
            health.usageAccessGranted &&
            health.serviceRunning &&
            pinSet &&
            chrome?.satisfied == true &&
            suspension?.allApplied == true &&
            checkpointState == CheckpointState.CLEAN

    val readiness: List<ReadinessItem>
        get() = listOf(
            ReadinessItem(
                "Device owner",
                health.deviceOwner,
                "Provisioning requires a factory-reset device with no accounts; see docs/setup-and-recovery.md",
            ),
            ReadinessItem(
                "Usage access",
                health.usageAccessGranted,
                "Grant it in Settings > Apps > Special app access > Usage access",
            ),
            ReadinessItem("Monitor running", health.serviceRunning, "Open DoomStop to restart the monitor"),
            ReadinessItem("Notification shown", health.notificationsEnabled, "Allow notifications for DoomStop"),
            ReadinessItem("PIN set", pinSet, "The trusted person sets a six-digit PIN during setup"),
            ReadinessItem(
                "Chrome site policy verified",
                chrome?.satisfied == true,
                chrome?.error ?: "Restart Chrome, then re-check; verify at chrome://policy",
            ),
            ReadinessItem(
                "Target suspension applied",
                suspension?.allApplied == true,
                suspension?.failures?.joinToString { it.packageName } ?: "Re-run the suspension test",
            ),
            ReadinessItem(
                "Accounting history complete",
                checkpointState == CheckpointState.CLEAN,
                "A gap could not be reconstructed; resolve it from the admin screen",
            ),
        )

    companion object {
        fun initial(settings: LimiterSettings) = LimiterStatus(
            setupCompleted = false,
            settings = settings,
            dayId = "",
            remainingMs = 0,
            totalAllowanceMs = settings.dailyAllowanceMs,
            baseAllowanceMs = settings.dailyAllowanceMs,
            chargedMs = 0,
            extraGrantedMs = 0,
            nextResetWallMs = 0,
            targetVisible = false,
            enforcement = EnforcementDecision(false, EnforcementReason.ALLOWANCE_AVAILABLE),
            checkpointState = CheckpointState.CLEAN,
            health = MonitorHealth(
                deviceOwner = false,
                usageAccessGranted = false,
                serviceRunning = false,
                notificationsEnabled = false,
            ),
            installedTargets = emptyList(),
            installedBlockedBrowsers = emptyList(),
            suspension = null,
            chrome = null,
            tracker = null,
            pinSet = false,
            exactAlarmsAllowed = false,
            lastTickWallMs = 0,
            recentAnomalies = emptyList(),
        )
    }
}
