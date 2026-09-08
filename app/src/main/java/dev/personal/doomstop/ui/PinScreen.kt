package dev.personal.doomstop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.personal.doomstop.security.PinManager
import dev.personal.doomstop.security.ThrottleState

/**
 * Masked six-digit entry.
 *
 * The keypad is drawn in-app rather than using the system IME. That keeps the PIN out of
 * keyboard learning, clipboard suggestions and autofill entirely, instead of relying on an
 * IME flag being honoured. Combined with FLAG_SECURE on the hosting window, the PIN is
 * never displayed and never leaves this screen.
 */
@Composable
fun PinScreen(
    title: String,
    subtitle: String,
    error: String?,
    throttle: ThrottleState,
    busy: Boolean,
    confirmLabel: String = "Confirm",
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var entry by remember { mutableStateOf("") }
    val locked = throttle.isLocked

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))
        PinDots(entry.length)
        Spacer(Modifier.height(16.dp))

        val message = when {
            locked -> "Too many attempts. Try again in ${formatDuration(throttle.lockedForMs)}."
            error != null -> error
            else -> " "
        }
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (locked || error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))
        Keypad(
            enabled = !locked && !busy,
            onDigit = { digit ->
                if (entry.length < PinManager.PIN_LENGTH) entry += digit
                if (entry.length == PinManager.PIN_LENGTH) {
                    val submitted = entry
                    entry = ""
                    onSubmit(submitted)
                }
            },
            onBackspace = { entry = entry.dropLast(1) },
        )

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = { entry = ""; onCancel() }) { Text("Cancel") }
            TextButton(
                enabled = entry.length == PinManager.PIN_LENGTH && !locked && !busy,
                onClick = {
                    val submitted = entry
                    entry = ""
                    onSubmit(submitted)
                },
            ) { Text(confirmLabel) }
        }
    }
}

@Composable
private fun PinDots(filled: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(PinManager.PIN_LENGTH) { index ->
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(
                        if (index < filled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            )
        }
    }
}

@Composable
private fun Keypad(enabled: Boolean, onDigit: (Char) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf("123", "456", "789")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (digit in row) KeypadKey(digit.toString(), enabled) { onDigit(digit) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.size(width = 84.dp, height = 56.dp))
            KeypadKey("0", enabled) { onDigit('0') }
            KeypadKey("⌫", enabled, onBackspace)
        }
    }
}

@Composable
private fun KeypadKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(width = 84.dp, height = 56.dp),
    ) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
    }
}
