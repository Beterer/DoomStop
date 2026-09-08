package dev.personal.doomstop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    onGrantUsageAccess: () -> Unit,
    onAllowExactAlarms: () -> Unit,
    onStartMonitor: () -> Unit,
    onSetPin: () -> Unit,
    onRefresh: () -> Unit,
    onFinishSetup: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Setup & diagnostics", style = MaterialTheme.typography.headlineSmall)

        SectionCard("Readiness") {
            for (item in status.readiness) CheckRow(item.label, item.satisfied, item.remedy)
        }

        SectionCard("Fix what is missing") {
            if (!status.health.usageAccessGranted) {
                Button(onClick = onGrantUsageAccess, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant Usage access")
                }
                Text(
                    "Device ownership does not grant this. It has to be switched on by hand.",
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

        DeviceOwnerCard(status)
        ChromePolicyCard(status)
        SuspensionCard(status)
        MonitorCard(status)

        if (!status.setupCompleted) {
            Button(onClick = onFinishSetup, modifier = Modifier.fillMaxWidth()) {
                Text("Finish setup and start enforcing")
            }
            Text(
                "Enforcement stays off until this is pressed. Metering already runs, so the " +
                    "first enforced day starts from a monitor that is known to work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        StatRow("Blocklist verified", if (chrome?.satisfied == true) "yes" else "no")
        if (chrome?.error != null) {
            Text(chrome.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(
            "Blocked hosts (subdomains and all schemes included): " + BlockedSites.HOSTS.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Verify independently at chrome://policy. A tab opened before the policy was applied " +
                "may need Chrome restarted; the policy does not erase a page that is already open.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (chrome?.verifiedValue != null) MonospaceText(chrome.verifiedValue)
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
        tracker?.activities?.take(6)?.forEach {
            MonospaceText("${it.packageName} ${it.state} ${it.ageMs} ms")
        }
        if (status.recentAnomalies.isNotEmpty()) {
            Text("Recent events", style = MaterialTheme.typography.titleSmall)
            status.recentAnomalies.forEach { MonospaceText(it) }
        }
    }
}
