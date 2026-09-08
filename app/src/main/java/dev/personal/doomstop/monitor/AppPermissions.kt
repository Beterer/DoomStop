package dev.personal.doomstop.monitor

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

/**
 * The capabilities this app needs, each checked against what the system actually granted
 * rather than what was declared in the manifest.
 *
 * Device ownership does NOT imply Usage Access, and holding a permission in the manifest
 * does not imply the appop is allowed, so every check here goes to the source of truth.
 */
object AppPermissions {

    /**
     * Usage Access, as the platform sees it.
     *
     * MODE_DEFAULT means "fall back to the permission check", so it is resolved rather
     * than treated as a refusal.
     */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = try {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } catch (e: SecurityException) {
            return false
        }
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT -> context.checkCallingOrSelfPermission(
                android.Manifest.permission.PACKAGE_USAGE_STATS
            ) == PackageManager.PERMISSION_GRANTED

            else -> false
        }
    }

    /** The Settings screen where Usage Access is granted by hand. */
    fun usageAccessSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun notificationsEnabled(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true

    /**
     * Exact alarms are a backup deadline, never the primary mechanism, so this is checked
     * and degraded gracefully rather than assumed. The platform grants it by user action;
     * being device owner does not confer it.
     */
    fun canScheduleExactAlarms(context: Context): Boolean =
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    fun exactAlarmSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun isScreenInteractive(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == true

    fun isKeyguardLocked(context: Context): Boolean =
        context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
}
