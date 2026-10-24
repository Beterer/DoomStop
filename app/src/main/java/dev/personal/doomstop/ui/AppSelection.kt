package dev.personal.doomstop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.personal.doomstop.config.AppChoice
import dev.personal.doomstop.domain.LimiterSettings

data class SetupPreferences(
    val targetPackages: Set<String> = LimiterSettings.DEFAULT_TARGET_PACKAGES,
    val dailyMinutes: String = "30",
    val customize: Boolean = false,
)

@Composable
fun AppSelection(
    apps: List<AppChoice>,
    selected: Set<String>,
    enabled: Boolean = true,
    onChange: (Set<String>) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    val chosen = apps.filter { app -> app.packages.any { it in selected } }
    Text(chosen.joinToString { it.label }.ifEmpty { "Choose at least one app" },
        style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = { search = ""; open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text("Choose apps")
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Apps to count") },
        text = {
            Column {
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    label = { Text("Search apps") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(apps.filter { it.label.contains(search, ignoreCase = true) }, key = { it.packageName }) { app ->
                        val checked = app.packages.any { it in selected }
                        Row(
                            Modifier.fillMaxWidth().toggleable(
                                value = checked, enabled = enabled, role = Role.Checkbox,
                                onValueChange = { onChange(if (it) selected + app.packages else selected - app.packages) },
                            ).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(app.label, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } },
    )
}
