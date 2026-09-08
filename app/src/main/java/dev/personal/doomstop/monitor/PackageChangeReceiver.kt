package dev.personal.doomstop.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.personal.doomstop.DoomStopApp
import dev.personal.doomstop.admin.PolicyController
import dev.personal.doomstop.config.BlockedBrowsers
import dev.personal.doomstop.config.TargetPackages
import dev.personal.doomstop.core.Trigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reacts to a target or hardcoded browser being installed, updated or removed.
 *
 * The honest limitation, stated in the plan and not engineered around: standard
 * device-owner APIs expose no per-package pre-install denylist, so this blocks USE after
 * installation rather than the installation itself. Package broadcasts are asynchronous, so
 * a newly installed browser may be launchable for a short window; that window is MEASURED
 * and reported rather than papered over with a blanket installation restriction, which
 * requirement 8 forbids.
 *
 * Reinstalling a target must not reset anything: the balance lives in the database keyed by
 * day, so the reinstalled app immediately inherits the current suspension state.
 */
class PackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val changed = intent.data?.schemeSpecificPart ?: return
        if (changed !in TargetPackages.ALL && changed !in BlockedBrowsers.ALL) return

        Log.i(TAG, "package change for $changed (${intent.action})")

        // Act immediately with the policy layer, before waiting on the coordinator: for a
        // freshly installed browser every millisecond of this window is measurable.
        val policy = PolicyController(context)
        if (policy.isDeviceOwner && changed in BlockedBrowsers.ALL) {
            policy.applyEnforcement(suspendTargets = lastKnownSuspendState(context))
        }

        val app = context.applicationContext as? DoomStopApp ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                app.coordinator.tick(Trigger.PACKAGE_CHANGE)
            } catch (e: Exception) {
                Log.e(TAG, "package-change reconciliation failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * The device-protected marker, which is readable even in direct boot and does not
     * require the coordinator to have started yet.
     */
    private fun lastKnownSuspendState(context: Context): Boolean =
        dev.personal.doomstop.data.BootMarkerStore(context).targetsSuspended

    private companion object {
        const val TAG = "DoomStopPackages"
    }
}
