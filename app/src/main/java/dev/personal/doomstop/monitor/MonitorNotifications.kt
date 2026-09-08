package dev.personal.doomstop.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.personal.doomstop.MainActivity
import dev.personal.doomstop.R
import dev.personal.doomstop.core.LimiterStatus
import dev.personal.doomstop.domain.CheckpointState
import dev.personal.doomstop.domain.EnforcementReason

/**
 * The persistent, quiet notification the foreground service runs under.
 *
 * It doubles as the reliable way back into the limiter: when a target is suspended the
 * system's own "app paused" dialog is what the user sees, so the limiter has to be
 * reachable from its launcher icon and from here -- never by drawing an activity over the
 * blocked app.
 */
class MonitorNotifications(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            // LOW: no sound, no vibration. A permanent notification should be unobtrusive.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager?.createNotificationChannel(channel)
    }

    fun build(status: LimiterStatus?): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title(status))
            .setContentText(text(status))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.notification_action_open),
                    open,
                ).build()
            )
            .build()
    }

    fun update(status: LimiterStatus) {
        manager?.notify(NOTIFICATION_ID, build(status))
    }

    private fun title(status: LimiterStatus?): String = when {
        status == null -> context.getString(R.string.notification_starting)
        status.checkpointState == CheckpointState.UNCERTAIN -> context.getString(R.string.notification_recovery)
        status.enforcement.reason == EnforcementReason.MONITOR_UNHEALTHY ->
            context.getString(R.string.notification_unhealthy)

        status.enforcement.suspendTargets -> context.getString(R.string.notification_used_up)
        else -> context.getString(R.string.notification_remaining, formatDuration(status.remainingMs))
    }

    private fun text(status: LimiterStatus?): String = when {
        status == null -> ""
        !status.setupCompleted -> context.getString(R.string.notification_setup_incomplete)
        status.targetVisible -> context.getString(R.string.notification_counting)
        else -> context.getString(R.string.notification_idle, formatDuration(status.totalAllowanceMs))
    }

    companion object {
        const val CHANNEL_ID = "doomstop_monitor"
        const val NOTIFICATION_ID = 1

        /** "7 min 05 s" style, chosen so a glance answers "how much is left". */
        fun formatDuration(ms: Long): String {
            val totalSeconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return if (minutes > 0) "$minutes min ${seconds.toString().padStart(2, '0')} s" else "$seconds s"
        }
    }
}
