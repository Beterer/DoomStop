package dev.personal.doomstop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun StatRow(label: String, value: String, emphasise: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = if (emphasise) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * A single readiness line. Deliberately shows a plain cross and the remedy rather than a
 * vague warning: the plan requires diagnostics to identify the failed capability and a
 * practical recovery action.
 */
@Composable
fun CheckRow(label: String, satisfied: Boolean, remedy: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            if (satisfied) "✓" else "✕",
            color = if (satisfied) okColor() else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (!satisfied && !remedy.isNullOrBlank()) {
                Text(
                    remedy,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun MonospaceText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Success green that still reads correctly against both light and dark surfaces. */
@Composable
fun okColor(): Color = if (MaterialTheme.colorScheme.surface.luminanceIsDark()) {
    Color(0xFF7BD88F)
} else {
    Color(0xFF1B7A32)
}

private fun Color.luminanceIsDark(): Boolean = (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f
