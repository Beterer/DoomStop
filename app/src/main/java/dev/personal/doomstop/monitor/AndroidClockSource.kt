package dev.personal.doomstop.monitor

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dev.personal.doomstop.domain.ClockSource

/**
 * The real clocks.
 *
 * [bootId] is derived from `Settings.Global.BOOT_COUNT`, which increments once per boot and
 * is independent of the wall clock. Deriving it from `currentTimeMillis - elapsedRealtime`
 * instead would have been tempting and wrong: that value shifts whenever the wall clock is
 * corrected, so an ordinary NTP adjustment would masquerade as a reboot and discard the
 * monotonic anchor.
 */
class AndroidClockSource(private val context: Context) : ClockSource {

    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    override fun wallTimeMs(): Long = System.currentTimeMillis()

    override fun bootId(): String {
        val bootCount = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrNull()
        return if (bootCount != null) {
            "boot:$bootCount"
        } else {
            // Fallback: bucket the derived boot instant to the nearest minute so small
            // clock corrections do not look like reboots. Recorded as a distinct scheme so
            // diagnostics can tell which source was used.
            val approximateBootWallMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            "approx:${approximateBootWallMs / 60_000L}"
        }
    }
}
