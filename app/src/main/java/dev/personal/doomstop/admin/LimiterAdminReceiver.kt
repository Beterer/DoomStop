package dev.personal.doomstop.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The device administrator component. Its existence is what makes device-owner
 * provisioning possible; ordinary device-administrator permission alone would not be
 * enough for package suspension or application restrictions.
 *
 * It deliberately carries no policy logic. Everything that touches DevicePolicyManager
 * lives in [PolicyController], so there is exactly one place where policy is applied.
 */
class LimiterAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        // Reaching here means protection is gone. Nothing to enforce with, so the only
        // useful thing is to leave a trace for the diagnostics screen to surface.
        Log.w(TAG, "Device admin disabled -- enforcement is no longer possible")
    }

    private companion object {
        const val TAG = "DoomStopAdmin"
    }
}
