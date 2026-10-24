package dev.personal.doomstop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import dev.personal.doomstop.config.AppChoice
import dev.personal.doomstop.config.TargetPackages
import dev.personal.doomstop.config.BlockedSites
import dev.personal.doomstop.core.LimiterStatus

/**
 * Setup and diagnostics.
 *
 * Every line reports what the system actually says, not what the app intends. A capability
 * that is missing is named, together with the action that fixes it.
 */
@Composable
fun SetupScreen(
    status: LimiterStatus,
    apps: List<AppChoice>,
    preferences: SetupPreferences,
    busy: Boolean,
    onPreferencesChange: (SetupPreferences) -> Unit,
    onGrantUsageAccess: () -> Unit,
    onAllowExactAlarms: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onStartMonitor: () -> Unit,
    onSetPin: () -> Unit,
    onRefresh: () -> Unit,
    onFinishSetup: () -> Unit,
    onBack: () -> Unit,
) {
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    val dailyMs = minutesToMs(preferences.dailyMinutes)
    val validChoices = preferences.targetPackages.isNotEmpty() && dailyMs != null && dailyMs in 0L..86_400_000L
    val selectedPackages = if (status.setupCompleted) status.settings.targetPackages else preferences.targetPackages
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(if (status.setupCompleted) "Setup & diagnostics" else "Set up DoomStop", style = MaterialTheme.typography.headlineSmall)

        if (!status.setupCompleted) SectionCard("Daily limit") {
            if (!preferences.customize) {
                Text("Instagram, TikTok and Reddit · 30 minutes a day")
                Text("One shared allowance. Unused time carries over.", style = MaterialTheme.typography.bodySmall)
            }
            if (preferences.customize) {
                AppSelection(apps, preferences.targetPackages, enabled = !busy) {
                    onPreferencesChange(preferences.copy(targetPackages = it))
                }
                OutlinedTextField(
                    value = preferences.dailyMinutes,
                    onValueChange = { onPreferencesChange(preferences.copy(dailyMinutes = it)) },
                    label = { Text("Daily allowance (minutes)") },
                    supportingText = { Text("Shared across selected apps. Unused time carries over.") },
                    isError = dailyMs == null || dailyMs !in 0L..86_400_000L,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(onClick = { onPreferencesChange(SetupPreferences()) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Use defaults")
            }
            if (!preferences.customize) OutlinedButton(
                onClick = { onPreferencesChange(preferences.copy(customize = true)) },
                enabled = !busy, modifier = Modifier.fillMaxWidth(),
            ) { Text("Customize") }
        }

        SectionCard("Permissions") {
            if (!status.health.usageAccessGranted) {
                Button(onClick = onGrantUsageAccess, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant Usage access")
                }
                Text(
                    "Allow DoomStop to count app usage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!status.health.serviceRunning) {
                Button(onClick = onStartMonitor, modifier = Modifier.fillMaxWidth()) {
                    Text("Start the monitor")
                }
            }
            if (!status.pinSet) {
                Button(onClick = onSetPin, modifier = Modifier.fillMaxWidth()) {
                    Text("Set the six-digit PIN")
                }
                Text(
                    "The trusted person chooses and keeps this. The phone's user should not see it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status.shortsGuard.youtubeInstalled && !status.shortsGuard.connected) {
                Button(onClick = onOpenAccessibilitySettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Switch on the Shorts guard")
                }
                Text(
                    "Settings > Accessibility > DoomStop Shorts guard. Until it is on, the whole " +
                        "YouTube app stays suspended.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (TargetPackages.INSTAGRAM in selectedPackages && status.instagramGuard.instagramInstalled && !status.instagramGuard.connected) {
                Button(onClick = onOpenAccessibilitySettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Switch on the messaging guard")
                }
                Text(
                    "Keeps DMs free and available after the limit. Without it, Instagram is paused at the limit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!status.exactAlarmsAllowed) {
                OutlinedButton(onClick = onAllowExactAlarms, modifier = Modifier.fillMaxWidth()) {
                    Text("Allow exact alarms (optional)")
                }
                Text(
                    "Optional. The backup deadline falls back to an inexact alarm without it; " +
                        "cut-off accuracy comes from the one-second poll either way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text("Re-check everything")
            }
        }

        if (!status.setupCompleted) {
            val blocking = status.unmetPrerequisites
            Button(
                onClick = onFinishSetup,
                enabled = !busy && blocking.isEmpty() && validChoices,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Finish setup")
            }
            Text(
                if (blocking.isEmpty()) {
                    "Your limit starts when you finish setup."
                } else {
                    "Still missing: " + blocking.joinToString { it.label } +
                        ". These are checked again when the button is pressed, not just here."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (blocking.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
            Text(if (showDiagnostics) "Hide diagnostics" else "Show diagnostics")
        }
        if (showDiagnostics) {
            SectionCard("Readiness") {
                for (item in status.readiness) CheckRow(item.label, item.satisfied, item.remedy)
            }
            DeviceOwnerCard(status)
            ChromePolicyCard(status)
            ShortsGuardCard(status)
            InstagramGuardCard(status)
            SuspensionCard(status)
            SelfProtectionCard(status)
            MonitorCard(status)
        }

        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun DeviceOwnerCard(status: LimiterStatus) {
    SectionCard("Device owner") {
        StatRow("Provisioned", if (status.health.deviceOwner) "yes" else "no")
        if (!status.health.deviceOwner) {
            Text(
                "Without device ownership nothing can be enforced: app suspension and Chrome " +
                    "policy both require it, and ordinary device-administrator permission is not " +
                    "enough. Provisioning needs a device with no accounts that has not finished " +
                    "setup, which in practice means a factory reset. See " +
                    "docs/setup-and-recovery.md.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChromePolicyCard(status: LimiterStatus) {
    val chrome = status.chrome
    SectionCard("Chrome site policy") {
        StatRow("Chrome installed", if (chrome?.chromeInstalled == true) "yes" else "no")
        StatRow("Managed value stored and read back", if (chrome?.storedPolicyVerified == true) "yes" else "no")
        if (chrome?.error != null) {
            Text(chrome.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(
            "Blocked hosts (subdomains and all schemes included): " + BlockedSites.HOSTS.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Blocked paths (the rest of the site stays available): " +
                BlockedSites.PATH_FILTERS.joinToString(", ") +
                ". A Shorts link or address is blocked; a Short reached by tapping around inside " +
                "YouTube's own website is not, because the site changes pages without a new " +
                "page load and Chrome only checks its blocklist on a load.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "This line checks the value DevicePolicyManager holds. It is NOT evidence that Chrome " +
                "accepted it: verify independently at chrome://policy and by opening a blocked host. " +
                "A tab opened before the policy was applied may need Chrome restarted; the policy " +
                "does not erase a page that is already open.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (chrome?.verifiedValue != null) MonospaceText(chrome.verifiedValue)
    }
}

/**
 * Best-effort by nature, and labelled so: detection depends on YouTube's own view names.
 * What is firm is the fallback, which is why it is spelled out on the card.
 */
@Composable
private fun ShortsGuardCard(status: LimiterStatus) {
    val guard = status.shortsGuard
    SectionCard("YouTube Shorts guard") {
        StatRow("YouTube installed", if (guard.youtubeInstalled) "yes" else "no")
        StatRow("Switched on in Settings", if (guard.enabledInSettings) "yes" else "no")
        StatRow("Running", if (guard.connected) "yes" else "no")
        StatRow("YouTube suspended because the guard is off", if (guard.youtubeSuspended) "yes" else "no")
        Text(
            "Closes the Shorts player inside YouTube and leaves ordinary videos alone. This is " +
                "best-effort, not a hard limit: it recognises Shorts by the names of YouTube's " +
                "own screen elements, and a YouTube update can rename them without any error " +
                "showing here. The firm part is the fallback: while the guard is not running, " +
                "the whole YouTube app is suspended.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The inverse of the Shorts guard: it keeps Direct Messages usable and free of charge, and
 * closes everything else once the limit is reached. Best-effort and, being an allow-list,
 * more fragile — spelled out on the card.
 */
@Composable
private fun InstagramGuardCard(status: LimiterStatus) {
    val guard = status.instagramGuard
    SectionCard("Instagram messaging guard") {
        StatRow("Instagram installed", if (guard.instagramInstalled) "yes" else "no")
        StatRow("Switched on in Settings", if (guard.enabledInSettings) "yes" else "no")
        StatRow("Running", if (guard.connected) "yes" else "no")
        StatRow("Messaging-only mode active", if (guard.messagingModeActive) "yes" else "no")
        StatRow("Instagram suspended because the guard is off", if (guard.instagramSuspended) "yes" else "no")
        Text(
            "Keeps Direct Messages usable after the limit and stops DM time counting, while the " +
                "feed, reels and explore are closed. Best-effort, and more fragile than the Shorts " +
                "guard because it recognises the messages screens by Instagram's own view names: an " +
                "update can rename them, and it would then close DMs too rather than let scrolling " +
                "through. The firm part is the fallback: with the guard off, Instagram is suspended " +
                "outright once the limit is reached.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SuspensionCard(status: LimiterStatus) {
    val report = status.suspension
    SectionCard("App suspension") {
        StatRow("Targets installed", status.installedTargets.size.toString())
        StatRow("Blocked browsers installed", status.installedBlockedBrowsers.size.toString())
        StatRow("All policy calls verified", if (report?.allApplied == true) "yes" else "no")
        report?.failures?.forEach {
            Text(
                "${it.packageName}: ${it.error ?: "state did not change"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (status.installedBlockedBrowsers.isNotEmpty()) {
            Text(
                "Suspended browsers: " + status.installedBlockedBrowsers.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Browsers are blocked from USE after installation, not from installing. A browser " +
                "that is not on the hardcoded list is not blocked at all.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The controls that stop DoomStop itself from simply being removed. Reported separately
 * because a green "Protected" that hides a failed anti-removal control would be a lie.
 */
@Composable
private fun SelfProtectionCard(status: LimiterStatus) {
    val report = status.selfProtection
    SectionCard("This app's own protection") {
        StatRow("Uninstall blocked", if (report?.uninstallBlocked == true) "yes" else "no")
        StatRow("Force-stop and clear-data blocked", if (report?.userControlDisabled == true) "yes" else "no")
        if (report?.error != null) {
            Text(report.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (status.activeRestrictions.isNotEmpty()) {
            Text(
                "Extra restrictions in force: " + status.activeRestrictions.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Applied when enforcement starts, and read back from the platform rather than " +
                "assumed. Manual date and time changes within one boot cannot move the accounting " +
                "day on their own; blocking them under Settings closes the same trick across a " +
                "reboot as well.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MonitorCard(status: LimiterStatus) {
    val tracker = status.tracker
    SectionCard("Monitor") {
        StatRow("Service running", if (status.health.serviceRunning) "yes" else "no")
        StatRow("Usage access", if (status.health.usageAccessGranted) "granted" else "missing")
        StatRow("Screen interactive", if (tracker?.screenInteractive == true) "yes" else "no")
        StatRow("Keyguard shown", if (tracker?.keyguardShown == true) "yes" else "no")
        StatRow("Target visible", if (status.targetVisible) "yes" else "no")
        StatRow("Last check", formatWallDateTime(status.lastTickWallMs))
        StatRow("History", status.checkpointState.name.lowercase())
        status.recovery?.let {
            Text(
                "Outstanding: ${it.reason} (${formatDuration(it.gapMs)} from " +
                    "${formatWallDateTime(it.fromWallMs)})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        tracker?.activities?.take(6)?.forEach {
            MonospaceText("${it.packageName} ${it.state} ${it.ageMs} ms")
        }
        if (status.recentAnomalies.isNotEmpty()) {
            Text("Recent events", style = MaterialTheme.typography.titleSmall)
            status.recentAnomalies.forEach { MonospaceText(it) }
        }
    }
}
