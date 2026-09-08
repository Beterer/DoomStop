package dev.personal.doomstop.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.personal.doomstop.DoomStopApp
import dev.personal.doomstop.core.Trigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires at the moment the allowance was projected to run out, or at the next day boundary.
 *
 * It exists so a stalled poll loop is not the only thing standing between an exhausted
 * allowance and continued use. It recomputes from persisted state rather than trusting the
 * projection that scheduled it, so an alarm that fires early, late, or twice is harmless.
 */
class DeadlineReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? DoomStopApp ?: return
        UsageMonitorService.start(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                app.coordinator.tick(Trigger.DEADLINE_ALARM)
            } catch (e: Exception) {
                Log.e(TAG, "deadline tick failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DoomStopDeadline"
    }
}
