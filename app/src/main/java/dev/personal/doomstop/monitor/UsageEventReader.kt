package dev.personal.doomstop.monitor

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

/**
 * Result of one read. [available] distinguishes "nothing happened" from "the app cannot
 * see anything", which the accounting core must treat completely differently: the first is
 * ordinary, the second means enforcement has gone blind.
 */
data class UsageReadResult(
    val events: List<TrackedEvent>,
    val available: Boolean,
    val newCursorWallMs: Long,
    val error: String? = null,
)

/**
 * Reads activity lifecycle and screen events from [UsageStatsManager].
 *
 * Uses queryEvents (individual lifecycle events), never daily aggregate usage totals:
 * aggregates cannot tell you when something started or stopped being visible.
 *
 * Each read covers a window that deliberately OVERLAPS the previous one, because usage
 * events are not guaranteed to be visible to a query the instant they occur. Duplicates
 * are removed by a stable event key, and the tracker is idempotent anyway, so an event
 * seen twice cannot be charged twice.
 */
class UsageEventReader(private val context: Context) {

    private val usageStatsManager: UsageStatsManager? =
        context.getSystemService(UsageStatsManager::class.java)

    /** Recently seen event keys, oldest first, for cross-window de-duplication. */
    private val seen = LinkedHashSet<String>()

    fun hasUsageAccess(): Boolean = AppPermissions.hasUsageAccess(context)

    /**
     * Read every relevant event in `(sinceWallMs - overlap, nowWallMs]`.
     *
     * An empty list with `available = true` means the phone was simply idle. `available =
     * false` means Usage Access is missing or the query failed, and the caller must not
     * mistake that for idleness.
     */
    fun read(sinceWallMs: Long, nowWallMs: Long): UsageReadResult {
        if (!hasUsageAccess()) {
            return UsageReadResult(emptyList(), available = false, newCursorWallMs = sinceWallMs, error = "usage access not granted")
        }
        val manager = usageStatsManager
            ?: return UsageReadResult(emptyList(), false, sinceWallMs, "UsageStatsManager unavailable")

        val from = (sinceWallMs - OVERLAP_MS).coerceAtLeast(0L)
        val to = nowWallMs + 1

        val collected = ArrayList<TrackedEvent>()
        try {
            val events: UsageEvents = manager.queryEvents(from, to)
                ?: return UsageReadResult(emptyList(), false, sinceWallMs, "queryEvents returned null")

            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val type = mapType(event.eventType) ?: continue
                val key = "${event.timeStamp}|${event.packageName}|${event.className}|${event.eventType}"
                if (!markSeen(key)) continue
                collected += TrackedEvent(
                    timestampWallMs = event.timeStamp,
                    packageName = event.packageName ?: continue,
                    className = event.className,
                    type = type,
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "queryEvents refused", e)
            return UsageReadResult(emptyList(), false, sinceWallMs, e.message ?: "SecurityException")
        } catch (e: RuntimeException) {
            Log.e(TAG, "queryEvents failed", e)
            return UsageReadResult(emptyList(), false, sinceWallMs, e.message ?: e.javaClass.simpleName)
        }

        collected.sortBy { it.timestampWallMs }
        return UsageReadResult(collected, available = true, newCursorWallMs = nowWallMs)
    }

    /** Returns true the first time a key is seen; evicts the oldest keys once full. */
    private fun markSeen(key: String): Boolean {
        if (!seen.add(key)) return false
        while (seen.size > MAX_SEEN_KEYS) {
            val oldest = seen.first()
            seen.remove(oldest)
        }
        return true
    }

    private fun mapType(eventType: Int): TrackedEventType? = when (eventType) {
        UsageEvents.Event.ACTIVITY_RESUMED -> TrackedEventType.ACTIVITY_RESUMED
        UsageEvents.Event.ACTIVITY_PAUSED -> TrackedEventType.ACTIVITY_PAUSED
        UsageEvents.Event.ACTIVITY_STOPPED -> TrackedEventType.ACTIVITY_STOPPED
        UsageEvents.Event.SCREEN_INTERACTIVE -> TrackedEventType.SCREEN_INTERACTIVE
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> TrackedEventType.SCREEN_NON_INTERACTIVE
        UsageEvents.Event.KEYGUARD_SHOWN -> TrackedEventType.KEYGUARD_SHOWN
        UsageEvents.Event.KEYGUARD_HIDDEN -> TrackedEventType.KEYGUARD_HIDDEN
        UsageEvents.Event.DEVICE_SHUTDOWN -> TrackedEventType.DEVICE_SHUTDOWN
        UsageEvents.Event.DEVICE_STARTUP -> TrackedEventType.DEVICE_STARTUP
        else -> null
    }

    private companion object {
        const val TAG = "DoomStopUsage"

        /** Usage events can become queryable slightly after they occur; re-read that tail. */
        const val OVERLAP_MS = 10_000L

        /** Bounded memory for de-duplication; far more than one overlap window can hold. */
        const val MAX_SEEN_KEYS = 2048
    }
}
