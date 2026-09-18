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
import dev.personal.doomstop.config.InstagramGuard
import dev.personal.doomstop.core.Trigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** What the coordinator needs to know about the Instagram messaging guard. */
interface InstagramGuardProbe {
    /** Bound by the system and running in this process right now. */
    val connected: Boolean

    /** Switched on under Settings > Accessibility, whether or not it is bound yet. */
    fun enabledInSettings(): Boolean

    /**
     * Whether a Direct Messages screen is on top of Instagram right now. Used to exempt DM
     * time from metering. Always false when the guard is not connected, because then this
     * cannot be observed and DM time is charged conservatively.
     */
    fun inDirectMessages(): Boolean
}

class AndroidInstagramGuardProbe(private val context: Context) : InstagramGuardProbe {
    override val connected: Boolean get() = InstagramGuardService.connected
    override fun enabledInSettings(): Boolean = InstagramGuardService.isEnabledInSettings(context)
    override fun inDirectMessages(): Boolean =
        InstagramGuardService.connected && InstagramGuardService.inDirectMessages
}

/**
 * Instagram messaging-only mode. Two jobs on one accessibility service, both scoped to the
 * Instagram package; see [InstagramGuard] for the honest limitations.
 *
 *  1. It reports, on every inspection, whether a DM screen is on top. The coordinator uses
 *     that to keep Direct Messages free of charge whether or not the limit has been reached.
 *  2. Once the limit is reached ([dev.personal.doomstop.core.InstagramGuardStatus.messaging
 *     ModeActive]), it runs the launch timer: a short grace to reach the inbox, then Back and
 *     Home for anything that is not a DM screen. Under the limit it does nothing but report.
 *
 * Privacy: the configuration limits events to Instagram, and the only thing ever inspected is
 * whether one of a handful of DM view IDs is on screen. No messages, names or anything typed
 * is read, stored or logged.
 */
class InstagramGuardService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var checkPending = false
    private var lastCheckUptimeMs = 0L
    private var lastActionUptimeMs = 0L

    /** Last time a DM screen was on top, or Instagram (re)entered the foreground. */
    private var lastAllowedUptimeMs = 0L

    override fun onServiceConnected() {
        connected = true
        Log.i(TAG, "connected")
        // Being bound can be what started this process. Make sure the monitor runs, and tick
        // at once so an Instagram suspended for a disconnected guard is released now.
        UsageMonitorService.start(this)
        nudgeCoordinator()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != InstagramGuard.INSTAGRAM_PACKAGE) return
        scheduleCheck()
    }

    private fun scheduleCheck() {
        if (checkPending) return
        checkPending = true
        val wait = (lastCheckUptimeMs + CHECK_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        handler.postDelayed({
            checkPending = false
            checkNow()
        }, wait)
    }

    private fun checkNow() {
        val now = SystemClock.uptimeMillis()
        // A large gap since the last inspection means Instagram was in the background and has
        // just come back: start the launch grace afresh, the same as a cold open.
        val reentered = now - lastCheckUptimeMs > REENTRY_RESET_MS
        lastCheckUptimeMs = now

        val onInstagram = rootIsInstagram()
        val directMessages = onInstagram && directMessagesOnScreen()
        inDirectMessages = directMessages

        if (directMessages || reentered || lastAllowedUptimeMs == 0L) {
            lastAllowedUptimeMs = now
        }

        if (!messagingModeActive()) return
        if (!onInstagram) return

        val msSinceAllowed = now - lastAllowedUptimeMs
        if (!InstagramGuard.shouldBackOut(true, directMessages, msSinceAllowed)) return

        if (now - lastActionUptimeMs < ACTION_COOLDOWN_MS) return
        lastActionUptimeMs = now

        Log.i(TAG, "non-DM Instagram screen past the grace; pressing Back")
        performGlobalAction(GLOBAL_ACTION_BACK)
        Toast.makeText(this, R.string.instagram_scroll_blocked, Toast.LENGTH_SHORT).show()
        handler.postDelayed(::escalateIfStillScrolling, ESCALATE_AFTER_MS)
    }

    /** Back does not always leave a non-DM screen, so escalate to Home like the Shorts guard. */
    private fun escalateIfStillScrolling() {
        if (!messagingModeActive() || !rootIsInstagram()) return
        if (directMessagesOnScreen()) return
        Log.i(TAG, "still on a non-DM Instagram screen after Back; going Home")
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun rootIsInstagram(): Boolean {
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return false
        return root.packageName?.toString() == InstagramGuard.INSTAGRAM_PACKAGE
    }

    private fun directMessagesOnScreen(): Boolean {
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != InstagramGuard.INSTAGRAM_PACKAGE) return false
        return InstagramGuard.DM_SCREEN_VIEW_IDS.any { id ->
            root.findAccessibilityNodeInfosByViewId(InstagramGuard.qualified(id)).any { it.isVisibleToUser }
        }
    }

    private fun messagingModeActive(): Boolean {
        val status = (application as DoomStopApp).coordinator.status.value
        return status.setupCompleted && !status.maintenanceMode && status.instagramGuard.messagingModeActive
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
        connected = false
        inDirectMessages = false
        Log.i(TAG, "unbound")
        nudgeCoordinator()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        inDirectMessages = false
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DoomStopInstagram"

        private const val CHECK_INTERVAL_MS = 300L
        private const val ACTION_COOLDOWN_MS = 1_500L
        private const val ESCALATE_AFTER_MS = 800L

        /** A quiet gap longer than this means Instagram was backgrounded and is re-entered. */
        private const val REENTRY_RESET_MS = 2_000L

        /** Written only by the service instance, which lives in this process. */
        @Volatile
        var connected: Boolean = false
            private set

        /** Whether a DM screen is on top of Instagram right now; read for the metering mask. */
        @Volatile
        var inDirectMessages: Boolean = false
            private set

        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val self = ComponentName(context, InstagramGuardService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == self }
        }
    }
}
