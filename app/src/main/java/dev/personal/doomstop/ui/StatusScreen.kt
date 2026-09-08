package dev.personal.doomstop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.personal.doomstop.config.TargetPackages
import dev.personal.doomstop.core.LimiterStatus
import dev.personal.doomstop.domain.CheckpointState
import dev.personal.doomstop.domain.EnforcementReason

/**
 * The main screen.
 *
 * There is no Start button anywhere here, on purpose: opening a target app is what starts
 * counting. Nothing on this screen begins or ends a session.
 */
@Composable
fun StatusScreen(
    status: LimiterStatus,
    onRequestMoreTime: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("DoomStop", style = MaterialTheme.typography.headlineSmall)

        RemainingHeadline(status)

        SectionCard("Today") {
            StatRow("Remaining", formatDuration(status.remainingMs), emphasise = true)
            StatRow("Used", formatDuration(status.chargedMs))
            StatRow("Base allowance", formatDuration(status.baseAllowanceMs))
            if (status.extraGrantedMs > 0) {
                StatRow("Extra granted today", formatDuration(status.extraGrantedMs))
            }
            StatRow("Resets", formatWallTime(status.nextResetWallMs, status.settings.zoneId))
            StatRow("Accounting timezone", status.settings.zoneId)
            if (status.targetVisible) {
                Text(
                    "Counting now.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        SectionCard("Metered apps") {
            for ((packageName, label) in TargetPackages.DISPLAY) {
                val installed = packageName in status.installedTargets
                val suspended = status.suspension?.outcomes
                    ?.firstOrNull { it.packageName == packageName }
                    ?.actualSuspended
                StatRow(
                    label = label,
                    value = when {
                        !installed -> "not installed"
                        suspended == true -> "paused"
                        suspended == false -> "available"
                        else -> "state unknown"
                    },
                )
            }
            Text(
                "Their websites stay blocked in Chrome at all times, including while time remains.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ProtectionCard(status, onOpenSetup)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onRequestMoreTime,
                modifier = Modifier.weight(1f),
                enabled = status.pinSet,
            ) {
                Text("Request more time")
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                Text("Settings")
            }
        }
        Text(
            "Requesting more time adds ${formatDuration(status.settings.extensionMs)} and needs the " +
                "trusted person's PIN each time. Websites are not unblocked.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RemainingHeadline(status: LimiterStatus) {
    val headline = when {
        status.maintenanceMode -> "Maintenance — not enforcing"
        status.checkpointState == CheckpointState.UNCERTAIN -> "Needs attention"
        status.enforcement.reason == EnforcementReason.MONITOR_UNHEALTHY -> "Monitoring unavailable"
        status.enforcement.suspendTargets -> "Used up for today"
        else -> formatDuration(status.remainingMs)
    }
    Text(
        headline,
        style = MaterialTheme.typography.displaySmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
        color = if (status.enforcement.suspendTargets) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    )
}

/**
 * Protection status. "Protected" appears only when every required capability is genuinely
 * present; anything else names what is missing instead of hedging.
 */
@Composable
private fun ProtectionCard(status: LimiterStatus, onOpenSetup: () -> Unit) {
    val missing = status.readiness.filterNot { it.satisfied }
    SectionCard(if (status.protectionActive) "Protected" else "Not fully protected") {
        if (status.protectionActive) {
            Text(
                "Device owner, monitoring, PIN, uninstall protection, the stored site policy and " +
                    "app suspension were all read back from the platform.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            if (status.maintenanceMode) {
                Text(
                    "Maintenance mode is on, so nothing is being enforced. End it from Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            status.recovery?.let {
                Text(
                    "Waiting on the PIN holder: ${it.reason}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            for (item in missing) CheckRow(item.label, false, item.remedy)
            TextButton(onClick = onOpenSetup) { Text("Open setup and diagnostics") }
        }
    }
}
