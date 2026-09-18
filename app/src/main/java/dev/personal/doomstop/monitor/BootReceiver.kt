package dev.personal.doomstop.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import dev.personal.doomstop.DoomStopApp
import dev.personal.doomstop.admin.PolicyController
import dev.personal.doomstop.core.Trigger
import dev.personal.doomstop.data.BootMarkerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Restores enforcement after a reboot, in two stages.
 *
 * Stage one is LOCKED_BOOT_COMPLETED, which arrives before the user has unlocked the
 * device. The Room database is in credential-protected storage and is unreadable at that
 * point, so this stage reads nothing but the tiny device-protected marker and re-applies
 * the suspension state that was in force when the device went down. That closes the window
 * between boot and first unlock without moving any sensitive state into device-protected
 * storage.
 *
 * Stage two is BOOT_COMPLETED, after unlock, where the database becomes readable, the gap
 * is reconciled from usage events, and normal metering resumes.
 *
 * The receiver is marked directBootAware in the manifest; the application object is not, so
 * everything reachable from stage one is limited to what works before unlock.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> onLockedBoot(context)
            Intent.ACTION_BOOT_COMPLETED -> onUnlockedBoot(context)
            Intent.ACTION_MY_PACKAGE_REPLACED -> onSelfUpdated(context)
            else -> Unit
        }
    }

    /** Before first unlock: marker only, no database, no PIN, no balance. */
    private fun onLockedBoot(context: Context) {
        val marker = BootMarkerStore(context)
        if (!marker.protectionEnabled) return

        val policy = PolicyController(context)
        if (!policy.isDeviceOwner) {
            Log.w(TAG, "locked boot: not device owner, nothing can be enforced")
            return
        }
        // Re-assert exactly what was in force at shutdown, erring toward suspended. Instagram
        // is hard-suspended whenever the other targets are: its DM-only mode depends on the
        // accessibility guard, which cannot run before the first unlock, so leaving it open
        // here would be an unguarded hole. The post-unlock tick relaxes it once the guard is up.
        val report = policy.applyEnforcement(
            suspendTargets = marker.targetsSuspended,
            suspendYouTube = marker.youtubeSuspended,
            suspendInstagram = marker.targetsSuspended,
        )
        Log.i(TAG, "locked boot: suspension re-applied, allApplied=${report.allApplied}")
    }

    private fun onUnlockedBoot(context: Context) {
        val user = context.getSystemService(UserManager::class.java)
        if (user?.isUserUnlocked == false) {
            // BOOT_COMPLETED before unlock should not happen, but if it does, do not touch
            // credential-protected storage.
            onLockedBoot(context)
            return
        }
        UsageMonitorService.start(context)
        tickInBackground(context, Trigger.BOOT)
    }

    /**
     * After an in-place update the process was killed; the service has to be started again.
     * Owner status, PIN, balance and applied policies all survive the update because they
     * live in the database and in platform policy, not in the process.
     */
    private fun onSelfUpdated(context: Context) {
        UsageMonitorService.start(context)
        tickInBackground(context, Trigger.BOOT)
    }

    private fun tickInBackground(context: Context, trigger: Trigger) {
        val app = context.applicationContext as? DoomStopApp ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                app.coordinator.tick(trigger)
            } catch (e: Exception) {
                Log.e(TAG, "boot reconciliation failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DoomStopBoot"
    }
}
