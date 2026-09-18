package dev.personal.doomstop.core

import dev.personal.doomstop.admin.ChromePolicyReport
import dev.personal.doomstop.admin.SelfProtectionReport
import dev.personal.doomstop.admin.SuspensionReport
import dev.personal.doomstop.domain.CheckpointState
import dev.personal.doomstop.domain.EnforcementDecision
import dev.personal.doomstop.domain.EnforcementReason
import dev.personal.doomstop.domain.LimiterSettings
import dev.personal.doomstop.domain.MonitorHealth
import dev.personal.doomstop.domain.RecoveryRequest
import dev.personal.doomstop.monitor.TrackerSnapshot

/**
 * Why the app is not fully protected, in terms a person can act on.
 *
 * [prerequisite] marks the lines that must already be satisfied before enforcement can be
 * switched on at all -- they are things a person does. The remaining lines are OUTCOMES of
 * switching it on, so they cannot be satisfied beforehand; they are still required for
 * [LimiterStatus.protectionActive] and are reported honestly either way.
 */
data class ReadinessItem(
    val label: String,
    val satisfied: Boolean,
    val remedy: String?,
    val prerequisite: Boolean = false,
)

/**
 * The Shorts guard as the platform reports it, and the verdict of the rule built on it.
 *
 * [youtubeSuspended] is what the rule asked for this pass, not a read-back; whether the
 * platform honoured it is in [LimiterStatus.suspension] like every other package.
 */
data class ShortsGuardStatus(
    val youtubeInstalled: Boolean,
    val enabledInSettings: Boolean,
    val connected: Boolean,
    val youtubeSuspended: Boolean,
) {
    companion object {
        val UNKNOWN = ShortsGuardStatus(
            youtubeInstalled = false,
            enabledInSettings = false,
            connected = false,
            youtubeSuspended = false,
        )
    }
}

/**
 * The Instagram messaging guard as the platform reports it, and the verdict of the rules
 * built on it.
 *
 * [messagingModeActive] is true when the allowance is exhausted and the guard is available,
 * so Instagram is left runnable in DM-only mode instead of suspended -- this is what the
 * accessibility service reads to decide whether to enforce the launch timer. [instagram
 * Suspended] is what the suspension rule asked for this pass, not a read-back; whether the
 * platform honoured it is in [LimiterStatus.suspension] like every other package.
 */
data class InstagramGuardStatus(
    val instagramInstalled: Boolean,
    val enabledInSettings: Boolean,
    val connected: Boolean,
    val messagingModeActive: Boolean,
    val instagramSuspended: Boolean,
) {
    companion object {
        val UNKNOWN = InstagramGuardStatus(
            instagramInstalled = false,
            enabledInSettings = false,
            connected = false,
            messagingModeActive = false,
            instagramSuspended = false,
        )
    }
}

/**
 * Everything the UI and the notification need, computed once per tick by the coordinator.
 *
 * Deliberately explicit about capability: [protectionActive] is only true when every
 * required capability is genuinely present. A missing capability must never render as
 * "Protected".
 */
data class LimiterStatus(
    val setupCompleted: Boolean,
    val maintenanceMode: Boolean,
    val settings: LimiterSettings,
    val dayId: String,
    val remainingMs: Long,
    val totalAllowanceMs: Long,
    val baseAllowanceMs: Long,
    /** Unused time carried in from yesterday; an estimate until yesterday has settled. */
    val carriedInMs: Long,
    val chargedMs: Long,
    val extraGrantedMs: Long,
    val nextResetWallMs: Long,
    val targetVisible: Boolean,
    val enforcement: EnforcementDecision,
    val checkpointState: CheckpointState,
    /** The outstanding, latched demand for authorized recovery, with its reason and range. */
    val recovery: RecoveryRequest?,
    val health: MonitorHealth,
    val installedTargets: List<String>,
    val installedBlockedBrowsers: List<String>,
    val suspension: SuspensionReport?,
    val chrome: ChromePolicyReport?,
    val shortsGuard: ShortsGuardStatus,
    val instagramGuard: InstagramGuardStatus,
    val selfProtection: SelfProtectionReport?,
    val activeRestrictions: Set<String>,
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
            !maintenanceMode &&
            health.deviceOwner &&
            health.usageAccessGranted &&
            health.serviceRunning &&
            pinSet &&
            chrome?.storedPolicyVerified == true &&
            suspension?.allApplied == true &&
            selfProtection?.uninstallBlocked == true &&
            checkpointState == CheckpointState.CLEAN

    val readiness: List<ReadinessItem>
        get() = listOf(
            ReadinessItem(
                label = "Device owner",
                satisfied = health.deviceOwner,
                remedy = "Provisioning requires a factory-reset device with no accounts; see docs/setup-and-recovery.md",
                prerequisite = true,
            ),
            ReadinessItem(
                label = "Usage access",
                satisfied = health.usageAccessGranted,
                remedy = "Grant it in Settings > Apps > Special app access > Usage access",
                prerequisite = true,
            ),
            ReadinessItem(
                label = "Monitor running",
                satisfied = health.serviceRunning,
                remedy = "Open DoomStop to restart the monitor",
                prerequisite = true,
            ),
            ReadinessItem(
                label = "Notification shown",
                satisfied = health.notificationsEnabled,
                remedy = "Allow notifications for DoomStop",
            ),
            ReadinessItem(
                label = "PIN set",
                satisfied = pinSet,
                remedy = "The trusted person sets a six-digit PIN during setup",
                prerequisite = true,
            ),
            ReadinessItem(
                label = "Accounting history complete",
                satisfied = checkpointState == CheckpointState.CLEAN,
                remedy = recovery?.reason ?: "A gap could not be reconstructed; resolve it from the admin screen",
                prerequisite = true,
            ),
            ReadinessItem(
                label = "Chrome policy stored and read back",
                satisfied = chrome?.storedPolicyVerified == true,
                remedy = chrome?.error
                    ?: "The managed value is what is checked here; confirm Chrome honours it at chrome://policy",
            ),
            ReadinessItem(
                label = "Target suspension applied",
                satisfied = suspension?.allApplied == true,
                remedy = suspension?.failures?.joinToString { it.packageName }
                    ?: "Applied when enforcement starts; finish setup to apply it",
            ),
            ReadinessItem(
                label = "This app cannot be uninstalled",
                satisfied = selfProtection?.uninstallBlocked == true,
                remedy = selfProtection?.error
                    ?: "Applied when enforcement starts; without it the limiter can simply be removed",
            ),
            ReadinessItem(
                label = "Task-manager controls disabled for this app",
                satisfied = selfProtection?.userControlDisabled == true,
                remedy = "Not supported on every platform build; force-stop may remain available",
            ),
            // Not part of protectionActive: with the guard off YouTube is suspended, so
            // Shorts stays unavailable either way. This line says which way it is.
            ReadinessItem(
                label = "YouTube Shorts guard running",
                satisfied = !shortsGuard.youtubeInstalled || shortsGuard.connected,
                remedy = if (shortsGuard.youtubeSuspended) {
                    "Off, so the whole YouTube app is suspended. Switch on DoomStop Shorts guard " +
                        "in Settings > Accessibility to get ordinary YouTube back"
                } else {
                    "Switch on DoomStop Shorts guard in Settings > Accessibility"
                },
            ),
            // Not part of protectionActive either: with the guard off Instagram is suspended
            // once the limit is reached, so scrolling stays unavailable regardless. This line
            // says whether messaging survives the limit.
            ReadinessItem(
                label = "Instagram messaging guard running",
                satisfied = !instagramGuard.instagramInstalled || instagramGuard.connected,
                remedy = if (instagramGuard.instagramSuspended) {
                    "Off, so once the limit is reached the whole Instagram app is suspended. Switch " +
                        "on DoomStop messaging guard in Settings > Accessibility to keep DMs after the limit"
                } else {
                    "Switch on DoomStop messaging guard in Settings > Accessibility to keep DMs " +
                        "after the limit and to stop DM time counting"
                },
            ),
        )

    /** Lines that must be green before enforcement can be switched on. */
    val unmetPrerequisites: List<ReadinessItem>
        get() = readiness.filter { it.prerequisite && !it.satisfied }

    companion object {
        fun initial(settings: LimiterSettings) = LimiterStatus(
            setupCompleted = false,
            maintenanceMode = false,
            settings = settings,
            dayId = "",
            remainingMs = 0,
            totalAllowanceMs = settings.dailyAllowanceMs,
            baseAllowanceMs = settings.dailyAllowanceMs,
            carriedInMs = 0,
            chargedMs = 0,
            extraGrantedMs = 0,
            nextResetWallMs = 0,
            targetVisible = false,
            enforcement = EnforcementDecision(false, EnforcementReason.ALLOWANCE_AVAILABLE),
            checkpointState = CheckpointState.CLEAN,
            recovery = null,
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
            shortsGuard = ShortsGuardStatus.UNKNOWN,
            instagramGuard = InstagramGuardStatus.UNKNOWN,
            selfProtection = null,
            activeRestrictions = emptySet(),
            tracker = null,
            pinSet = false,
            exactAlarmsAllowed = false,
            lastTickWallMs = 0,
            recentAnomalies = emptyList(),
        )
    }
}
