package dev.personal.doomstop.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.personal.doomstop.monitor.AppPermissions
import dev.personal.doomstop.monitor.DeadlineReceiver
import kotlin.math.abs

/**
 * Arms a backup alarm for the moment enforcement must next act: when the remaining
 * allowance runs out while a target is on screen, or otherwise the next day boundary.
 *
 * This is DEFENCE IN DEPTH, not the mechanism. Cut-off accuracy comes from the one-second
 * poll; the alarm exists so that a stalled or delayed poll loop is not the only thing
 * between an exhausted allowance and continued scrolling.
 *
 * Exact alarms are used only when the platform says they are permitted. When they are not,
 * the alarm degrades to an inexact one and that fact is surfaced in the status rather than
 * quietly assumed away.
 */
class DeadlineScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private var scheduledForWallMs: Long? = null

    fun schedule(deadlineWallMs: Long?, nowWallMs: Long) {
        val manager = alarmManager ?: return
        if (deadlineWallMs == null) {
            cancel()
            return
        }
        val target = deadlineWallMs.coerceAtLeast(nowWallMs + MIN_LEAD_MS)

        // Rescheduling an essentially identical alarm every second would be pure churn.
        scheduledForWallMs?.let { if (abs(it - target) < RESCHEDULE_TOLERANCE_MS) return }

        val intent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, DeadlineReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            if (AppPermissions.canScheduleExactAlarms(context)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, intent)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, intent)
            }
            scheduledForWallMs = target
        } catch (e: SecurityException) {
            // Permission can be revoked between the check and the call.
            Log.w(TAG, "alarm refused; polling remains the only deadline source", e)
            scheduledForWallMs = null
        }
    }

    fun cancel() {
        val manager = alarmManager ?: return
        val intent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, DeadlineReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (intent != null) manager.cancel(intent)
        scheduledForWallMs = null
    }

    private companion object {
        const val TAG = "DoomStopDeadline"
        const val REQUEST_CODE = 1001
        const val MIN_LEAD_MS = 1_000L
        const val RESCHEDULE_TOLERANCE_MS = 2_000L
    }
}
