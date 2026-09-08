package dev.personal.doomstop.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "1 h 05 min", "12 min 30 s", "45 s" -- precise enough to trust, short enough to glance at. */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "$hours h ${minutes.toString().padStart(2, '0')} min"
        minutes > 0 -> "$minutes min ${seconds.toString().padStart(2, '0')} s"
        else -> "$seconds s"
    }
}

/** Minutes only, for editing fields where seconds would be noise. */
fun formatMinutes(ms: Long): String = (ms / 60_000L).toString()

fun minutesToMs(minutes: String): Long? =
    minutes.trim().toLongOrNull()?.takeIf { it >= 0 }?.times(60_000L)

fun formatWallTime(wallMs: Long, zoneId: String): String = runCatching {
    DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())
        .withZone(ZoneId.of(zoneId))
        .format(Instant.ofEpochMilli(wallMs))
}.getOrElse { "—" }

fun formatWallDateTime(wallMs: Long): String = runCatching {
    DateTimeFormatter.ofPattern("dd MMM HH:mm:ss", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(wallMs))
}.getOrElse { "—" }
