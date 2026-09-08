package dev.personal.doomstop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import dev.personal.doomstop.admin.HardeningOptions
import dev.personal.doomstop.core.LimiterStatus
import dev.personal.doomstop.core.RestoreReport
import dev.personal.doomstop.domain.CheckpointState
import java.time.ZoneId

/**
 * Settings, behind the PIN.
 *
 * Maintenance and removal are visually and structurally separated from ordinary limit
 * changes, and the destructive ones require an explicit confirmation naming what they do.
 */
@Composable
fun AdminScreen(
    status: LimiterStatus,
    busy: Boolean,
    restoreReport: RestoreReport?,
    onInteraction: () -> Unit,
    previewAllowance: suspend (Long) -> Pair<Long, Long>,
    onSave: (dailyMs: Long, extensionMs: Long, zoneId: String) -> Unit,
    onChangePin: () -> Unit,
    onApplyHardening: (HardeningOptions) -> Unit,
    onAcknowledgeRecovery: () -> Unit,
    onRestoreDevice: (relinquishOwnership: Boolean, releaseUnknownPackages: Boolean) -> Unit,
    onCancelMaintenance: () -> Unit,
    onBack: () -> Unit,
) {
    var dailyMinutes by remember(status.settings) { mutableStateOf(formatMinutes(status.settings.dailyAllowanceMs)) }
    var extensionMinutes by remember(status.settings) { mutableStateOf(formatMinutes(status.settings.extensionMs)) }
    var zoneId by remember(status.settings) { mutableStateOf(status.settings.zoneId) }
    var preview by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    var hardening by remember(status.activeRestrictions) {
        mutableStateOf(HardeningOptions.fromRestrictions(status.activeRestrictions))
    }
    var confirmRestore by remember { mutableStateOf<Boolean?>(null) }
    var releaseUnknown by remember { mutableStateOf(false) }

    // Show the effect of an allowance change BEFORE it is applied.
    LaunchedEffect(dailyMinutes) {
        val newMs = minutesToMs(dailyMinutes)
        preview = if (newMs != null) runCatching { previewAllowance(newMs) }.getOrNull() else null
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Signed in with the PIN. This ends when you leave this screen, when DoomStop leaves the " +
                "foreground, or after two minutes of inactivity — there is no permanent unlock, and " +
                "every action below re-checks the session before it runs.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionCard("Limits") {
            OutlinedTextField(
                value = dailyMinutes,
                onValueChange = { dailyMinutes = it; onInteraction() },
                label = { Text("Daily allowance (minutes)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = extensionMinutes,
                onValueChange = { extensionMinutes = it; onInteraction() },
                label = { Text("Each extension (minutes)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = zoneId,
                onValueChange = { zoneId = it; onInteraction() },
                label = { Text("Accounting timezone") },
                singleLine = true,
                isError = runCatching { ZoneId.of(zoneId) }.isFailure,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "30 minutes a day and 10 minutes per extension are PROPOSED defaults, not " +
                    "choices you have already confirmed. Unused time does not roll over, and " +
                    "extra time expires at the next reset.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            preview?.let { (before, after) ->
                StatRow("Remaining today, now", formatDuration(before))
                StatRow("Remaining today, if saved", formatDuration(after))
                if (after == 0L && before > 0L) {
                    Text(
                        "Saving this exhausts today immediately and pauses the apps. Time already " +
                            "used is never rewritten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Button(
                enabled = !busy && minutesToMs(dailyMinutes) != null && minutesToMs(extensionMinutes) != null &&
                    runCatching { ZoneId.of(zoneId) }.isSuccess,
                onClick = {
                    onSave(minutesToMs(dailyMinutes)!!, minutesToMs(extensionMinutes)!!, zoneId)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save limits") }
        }

        SectionCard("PIN") {
            Text(
                "Losing the PIN cannot be undone from inside the app: there is no recovery " +
                    "password anywhere in this design. Recovery means deliberate administrator " +
                    "action below, or ultimately a factory reset.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onChangePin, modifier = Modifier.fillMaxWidth()) {
                Text("Change the PIN")
            }
        }

        if (status.maintenanceMode) {
            SectionCard("Maintenance in progress") {
                Text(
                    "DoomStop is deliberately not enforcing anything right now, so a restore is " +
                        "not fought by the monitor. The phone is unprotected until this ends.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                restoreReport?.failures?.forEach {
                    Text(
                        "Failed: ${it.name}${it.detail?.let { d -> " — $d" }.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(onClick = onCancelMaintenance, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel maintenance and resume enforcing")
                }
            }
        }

        if (status.checkpointState == CheckpointState.UNCERTAIN) {
            SectionCard("Recovery needed") {
                Text(
                    status.recovery?.let {
                        "The apps are paused because ${it.reason}. The affected period is " +
                            "${formatDuration(it.gapMs)} from ${formatWallDateTime(it.fromWallMs)} to " +
                            "${formatWallDateTime(it.toWallMs)}."
                    } ?: "A period could not be reconstructed, so the apps are paused.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Acknowledging this re-anchors the accounting from now, and adopts the phone's " +
                        "current clock. Time already charged today is kept; no missing time is " +
                        "invented. If the clock is wrong, correct it BEFORE acknowledging — the " +
                        "anchor decides which accounting day is current.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onAcknowledgeRecovery, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Acknowledge and resume")
                }
            }
        }

        SectionCard("Extra restrictions (optional)") {
            Text(
                "Each of these closes a specific bypass at a cost. None is on by default, and " +
                    "app installation is never restricted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HardeningToggle("Block adding users and profiles", hardening.disallowAddUser) {
                hardening = hardening.copy(disallowAddUser = it); onInteraction()
            }
            HardeningToggle("Block safe boot", hardening.disallowSafeBoot) {
                hardening = hardening.copy(disallowSafeBoot = it); onInteraction()
            }
            HardeningToggle("Block manual date and time changes", hardening.disallowManualDateTime) {
                hardening = hardening.copy(disallowManualDateTime = it); onInteraction()
            }
            HardeningToggle(
                "Block USB debugging — do not enable until recovery is proven",
                hardening.disallowDebuggingFeatures,
            ) {
                hardening = hardening.copy(disallowDebuggingFeatures = it); onInteraction()
            }
            Button(onClick = { onApplyHardening(hardening) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Apply restrictions")
            }
        }

        SectionCard("Maintenance and removal") {
            Text(
                "These undo what DoomStop changed, one verified step at a time. Only the values " +
                    "recorded in its ledger are put back; unrelated settings are left alone. If a " +
                    "step fails, device ownership is kept so the whole thing can be retried.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            restoreReport?.let { report ->
                for (stage in report.stages) {
                    CheckRow(stage.name, stage.succeeded, stage.detail)
                }
                if (report.ambiguousPackages.isNotEmpty()) {
                    HardeningToggle(
                        "Also release ${report.ambiguousPackages.size} package(s) whose state before " +
                            "DoomStop is unknown",
                        releaseUnknown,
                    ) { releaseUnknown = it; onInteraction() }
                }
            }
            OutlinedButton(
                onClick = { confirmRestore = false },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Restore access, keep device ownership") }
            OutlinedButton(
                onClick = { confirmRestore = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Restore access and release device ownership") }
            Text(
                "Releasing ownership is not reversible without another factory reset, because " +
                    "provisioning requires a device that has not finished setup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        TextButton(onClick = onBack) { Text("Back") }
    }

    confirmRestore?.let { relinquish ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text(if (relinquish) "Release device ownership?" else "Restore access?") },
            text = {
                Text(
                    if (relinquish) {
                        "Unsuspends the apps and browsers this app suspended, puts Chrome's " +
                            "blocklist back to its previous value, clears the restrictions this " +
                            "app set, and then gives up device ownership. Regaining ownership " +
                            "later requires a factory reset."
                    } else {
                        "Unsuspends the apps and browsers this app suspended and puts Chrome's " +
                            "blocklist back to its previous value. DoomStop stays the device owner, " +
                            "so protection can be turned back on without a reset."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRestore = null; onRestoreDevice(relinquish, releaseUnknown) }) {
                    Text("Confirm")
                }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HardeningToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
