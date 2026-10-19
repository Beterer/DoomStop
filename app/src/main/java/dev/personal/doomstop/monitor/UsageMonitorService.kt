package dev.personal.doomstop.monitor

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.room.withTransaction
import dev.personal.doomstop.DoomStopApp
import dev.personal.doomstop.core.LimiterCoordinator
import dev.personal.doomstop.core.LimiterStatus
import dev.personal.doomstop.core.Trigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.FileDescriptor
import java.io.PrintWriter
import java.time.Instant
import java.time.ZoneId
import org.json.JSONObject

/**
 * The foreground service that drives metering.
 *
 * Polling cadence is adaptive rather than a flat one second: one second while a target is
 * actually visible (that is what the cut-off deadline depends on), slower while the screen
 * is merely on, and slow while it is off, where nothing can be consumed anyway. Screen and
 * package broadcasts wake it immediately, so the slow cadences never delay a real event.
 *
 * Foreground-service type: a device-owner application qualifies for `systemExempted`, which
 * is what this uses once ownership exists. Before provisioning it falls back to
 * `specialUse` -- declared honestly, with a subtype describing what it does -- so that
 * usage metering can be measured on real hardware before the device is ever reset.
 * `dataSync` is not misused, and no media session is faked.
 *
 * START_STICKY is requested, but the plan is right that it is not a guarantee: restart
 * behaviour, task-manager stops and battery saver are all measured rather than assumed,
 * and any gap is reconciled from usage events on the next start rather than forgiven.
 */
class UsageMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    private lateinit var coordinator: LimiterCoordinator
    private lateinit var notifications: MonitorNotifications

    /**
     * Context-registered receivers. Manifest receivers do not get most implicit broadcasts
     * on modern Android, so the immediate wake-ups are registered here, where they do
     * arrive, while the manifest receivers cover the cold-start cases.
     */
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val trigger = when (intent.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT ->
                    Trigger.SCREEN_STATE

                Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED, Intent.ACTION_PACKAGE_REMOVED ->
                    Trigger.PACKAGE_CHANGE

                else -> Trigger.POLL
            }
            Log.i(TAG, "wake: ${intent.action} -> $trigger")
            scope.launch { runCatching { coordinator.tick(trigger) } }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as DoomStopApp
        coordinator = app.coordinator
        notifications = app.notifications
        notifications.ensureChannel()

        registerReceiver(
            wakeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        registerReceiver(
            wakeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startForegroundSafely()) {
            stopSelf()
            return START_NOT_STICKY
        }
        coordinator.serviceRunning = true
        if (loop == null) loop = scope.launch { runLoop() }
        return START_STICKY
    }

    private suspend fun runLoop() {
        while (scope.isActive) {
            val status = runCatching { coordinator.tick(Trigger.POLL) }
                .onFailure { Log.e(TAG, "tick failed", it) }
                .getOrNull()
            if (status != null) notifications.update(status)
            delay(intervalMsFor(status))
        }
    }

    private fun intervalMsFor(status: LimiterStatus?): Long = when {
        status == null -> IDLE_INTERVAL_MS
        status.targetVisible -> ACTIVE_INTERVAL_MS
        AppPermissions.isScreenInteractive(this) -> SCREEN_ON_INTERVAL_MS
        else -> IDLE_INTERVAL_MS
    }

    /**
     * Enter the foreground with the strongest type this app is actually entitled to, and
     * fall back rather than crash if the platform disagrees.
     */
    private fun startForegroundSafely(): Boolean {
        val notification = notifications.build(coordinator.status.value)
        val deviceOwner = coordinator.status.value.health.deviceOwner ||
            (application as DoomStopApp).policy.isDeviceOwner
        val preferred = if (deviceOwner) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        return try {
            startForeground(MonitorNotifications.NOTIFICATION_ID, notification, preferred)
            true
        } catch (e: Exception) {
            Log.w(TAG, "foreground type $preferred refused; retrying as specialUse", e)
            try {
                startForeground(
                    MonitorNotifications.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
                true
            } catch (fatal: Exception) {
                Log.e(TAG, "could not enter the foreground at all", fatal)
                false
            }
        }
    }

    override fun onDestroy() {
        coordinator.serviceRunning = false
        runCatching { unregisterReceiver(wakeReceiver) }
        loop = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android's shell-only dumpsys path also works with a non-debuggable signed release. */
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        if ("doomstop-diagnostics" !in args) {
            super.dump(fd, writer, args)
            return
        }
        val app = application as DoomStopApp
        val afterId = args.firstOrNull { it.startsWith("after=") }?.substringAfter('=')?.toLongOrNull() ?: 0L
        val requestedThroughId = args.firstOrNull { it.startsWith("through=") }?.substringAfter('=')?.toLongOrNull()
        runBlocking(Dispatchers.IO) {
            val (header, records) = app.database.withTransaction {
                // A small page fits Android's service-dump deadline and bounds main-looper
                // blocking. The export's first page fixes an upper ID for all later pages.
                val throughId = requestedThroughId ?: (app.dao.latestDiagnosticId() ?: 0L)
                val page = app.dao.diagnosticPage(afterId, throughId, DIAGNOSTIC_PAGE_SIZE)
                val nextId = page.lastOrNull()?.id ?: afterId
                val settings = app.dao.settings()
                val checkpoint = app.dao.checkpoint()
                val now = checkpoint?.lastWallMs ?: System.currentTimeMillis()
                val dayId = Instant.ofEpochMilli(now).atZone(ZoneId.of(settings?.zoneId ?: "UTC")).toLocalDate().toString()
                val day = app.dao.day(dayId)
                val info = packageManager.getPackageInfo(packageName, 0)
                JSONObject()
                    .put("type", "snapshot")
                    .put("schema", 1)
                    .put("version", info.versionName)
                    .put("versionCode", info.longVersionCode)
                    .put("exportedAt", System.currentTimeMillis())
                    .put("throughId", throughId)
                    .put("expectedRecordCount", app.dao.diagnosticCountThrough(throughId))
                    .put("nextId", nextId)
                    .put("complete", page.size < DIAGNOSTIC_PAGE_SIZE || nextId >= throughId)
                    .put("day", dayId)
                    .put("zone", settings?.zoneId ?: JSONObject.NULL)
                    .put("dailyAllowanceMs", settings?.dailyAllowanceMs ?: JSONObject.NULL)
                    .put("chargedMs", day?.chargedMs ?: JSONObject.NULL)
                    .put("carriedInMs", day?.carriedInMs ?: JSONObject.NULL)
                    .put("extraGrantedMs", day?.extraGrantedMs ?: JSONObject.NULL)
                    .put("setupCompleted", settings?.setupCompleted ?: false)
                    .put("maintenanceMode", settings?.maintenanceMode ?: false)
                    .put("settledThrough", checkpoint?.settledWallMs ?: JSONObject.NULL)
                    .put("recovery", checkpoint?.recoveryReason ?: JSONObject.NULL)
                    .put("interactiveNow", AppPermissions.isScreenInteractive(this@UsageMonitorService))
                    .put("lockedNow", AppPermissions.isKeyguardLocked(this@UsageMonitorService)) to page
            }
            writer.println("DOOMSTOP_DIAGNOSTICS_BEGIN")
            writer.println(header.toString())
            records.forEach {
                val detail: Any = if (it.type == "accounting_trace") JSONObject(it.detail) else it.detail
                writer.println(JSONObject().put("id", it.id).put("at", it.atWallMs).put("type", it.type).put("detail", detail).toString())
            }
            writer.println("DOOMSTOP_DIAGNOSTICS_END")
        }
    }

    companion object {
        private const val TAG = "DoomStopMonitor"
        private const val DIAGNOSTIC_PAGE_SIZE = 250

        /** While a target is on screen the poll IS the cut-off mechanism. */
        const val ACTIVE_INTERVAL_MS = 1_000L
        const val SCREEN_ON_INTERVAL_MS = 2_000L

        /** Nothing can be consumed with the screen off; broadcasts wake this immediately. */
        const val IDLE_INTERVAL_MS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, UsageMonitorService::class.java)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "could not start the monitor service", it) }
        }
    }
}
