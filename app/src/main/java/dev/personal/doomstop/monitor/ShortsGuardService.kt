package dev.personal.doomstop.monitor

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import dev.personal.doomstop.DoomStopApp
import dev.personal.doomstop.R
import dev.personal.doomstop.config.ShortsGuard
import dev.personal.doomstop.core.Trigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** What the coordinator needs to know about the Shorts guard, separated so tests can inject it. */
interface ShortsGuardProbe {
    /** Bound by the system and running in this process right now. */
    val connected: Boolean

    /** Switched on under Settings > Accessibility, whether or not it is bound yet. */
    fun enabledInSettings(): Boolean
}

class AndroidShortsGuardProbe(private val context: Context) : ShortsGuardProbe {
    override val connected: Boolean get() = ShortsGuardService.connected
    override fun enabledInSettings(): Boolean = ShortsGuardService.isEnabledInSettings(context)
}

/**
 * Backs out of the YouTube Shorts player whenever it appears, leaving the rest of YouTube
 * alone. Best-effort by nature; see [ShortsGuard] for what it can and cannot do.
 *
 * Privacy: the service configuration limits events to the YouTube package, and the only
 * thing ever inspected is whether one of a handful of view IDs is on screen. No text,
 * titles, comments or account details are read, stored or logged.
 *
 * Enforcement is gated on the coordinator's status, so nothing happens before setup is
 * finished or while the PIN holder has protection paused for maintenance.
 */
class ShortsGuardService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var checkPending = false
    private var lastCheckUptimeMs = 0L
    private var lastActionUptimeMs = 0L

    override fun onServiceConnected() {
        connected = true
        Log.i(TAG, "connected")
        // Being bound can be what started this process. Make sure the monitor runs, and
        // tick at once so a YouTube suspended for a disconnected guard is released now.
        UsageMonitorService.start(this)
        nudgeCoordinator()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != ShortsGuard.YOUTUBE_PACKAGE) return
        scheduleCheck()
    }

    /**
     * Content-changed events arrive many times a second while a video plays. Checks are
     * coalesced so the tree is inspected at most once per [CHECK_INTERVAL_MS].
     */
    private fun scheduleCheck() {
        if (checkPending) return
        checkPending = true
        val wait = (lastCheckUptimeMs + CHECK_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        handler.postDelayed({
            checkPending = false
            lastCheckUptimeMs = SystemClock.uptimeMillis()
            checkNow()
        }, wait)
    }

    private fun checkNow() {
        if (!enforcing()) return
        if (!shortsOnScreen()) return

        val now = SystemClock.uptimeMillis()
        if (now - lastActionUptimeMs < ACTION_COOLDOWN_MS) return
        lastActionUptimeMs = now

        Log.i(TAG, "Shorts player on screen; pressing Back")
        performGlobalAction(GLOBAL_ACTION_BACK)
        Toast.makeText(this, R.string.shorts_blocked, Toast.LENGTH_SHORT).show()
        handler.postDelayed(::escalateIfStillShowing, ESCALATE_AFTER_MS)
    }

    /** Back does not always leave Shorts, for example when a Short was the first screen opened. */
    private fun escalateIfStillShowing() {
        if (!enforcing() || !shortsOnScreen()) return
        Log.i(TAG, "still on Shorts after Back; going Home")
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun shortsOnScreen(): Boolean {
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != ShortsGuard.YOUTUBE_PACKAGE) return false
        return ShortsGuard.SHORTS_PLAYER_VIEW_IDS.any { id ->
            root.findAccessibilityNodeInfosByViewId(ShortsGuard.qualified(id)).any { it.isVisibleToUser }
        }
    }

    private fun enforcing(): Boolean {
        val status = (application as DoomStopApp).coordinator.status.value
        return status.setupCompleted && !status.maintenanceMode
    }

    private fun nudgeCoordinator() {
        val app = application as? DoomStopApp ?: return
        scope.launch {
            runCatching { app.coordinator.tick(Trigger.GUARD_CHANGE) }
                .onFailure { Log.e(TAG, "guard-change reconciliation failed", it) }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        // Switched off, or the app is being stopped. Tick now so YouTube is suspended at
        // once rather than at the next poll.
        connected = false
        Log.i(TAG, "unbound")
        nudgeCoordinator()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DoomStopShorts"

        private const val CHECK_INTERVAL_MS = 300L
        private const val ACTION_COOLDOWN_MS = 1_500L
        private const val ESCALATE_AFTER_MS = 800L

        /** Written only by the service instance, which lives in this process. */
        @Volatile
        var connected: Boolean = false
            private set

        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val self = ComponentName(context, ShortsGuardService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == self }
        }
    }
}
