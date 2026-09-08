package dev.personal.doomstop.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A single accounting day, identified by its ISO-8601 local date ("2026-09-08") in the
 * fixed accounting timezone. Day IDs are the primary key of the budget table, which is
 * what makes daily rollover idempotent: revisiting a day never creates a fresh allowance.
 */
typealias DayId = String

/** A duration attributed to one accounting day. */
data class DaySlice(val dayId: DayId, val durationMs: Long)

/**
 * Converts wall-clock instants to accounting days in a FIXED timezone.
 *
 * The timezone is captured once during setup and never follows the phone around. That is
 * deliberate: letting the device's current timezone drive day boundaries would make
 * travelling (or a manual timezone change) a way to reach a new day early.
 *
 * Day starts are resolved with [LocalDate.atStartOfDay], which returns the first valid
 * instant of the day -- so a DST transition that skips local midnight is handled instead
 * of producing a phantom hour.
 */
class DayBoundary(val zone: ZoneId) {

    fun dayIdAt(wallMs: Long): DayId =
        LocalDate.ofInstant(Instant.ofEpochMilli(wallMs), zone).format(DateTimeFormatter.ISO_LOCAL_DATE)

    fun startOfDayMs(dayId: DayId): Long =
        LocalDate.parse(dayId).atStartOfDay(zone).toInstant().toEpochMilli()

    /** First instant of the day following the one containing [wallMs]. */
    fun nextBoundaryAfter(wallMs: Long): Long {
        val day = LocalDate.ofInstant(Instant.ofEpochMilli(wallMs), zone)
        return day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * Splits the half-open wall-clock interval `[startWallMs, startWallMs + durationMs)`
     * into per-day slices, so an interval that crosses midnight is charged to both days in
     * the correct proportion.
     *
     * Zero-length slices are omitted. The iteration is bounded so that a wildly wrong
     * clock cannot spin here; anything beyond [MAX_DAYS] is attributed to the final day
     * and the caller is expected to have already flagged the anomaly.
     */
    fun split(startWallMs: Long, durationMs: Long): List<DaySlice> {
        require(durationMs >= 0) { "Negative duration cannot be charged: $durationMs" }
        if (durationMs == 0L) return emptyList()

        val slices = ArrayList<DaySlice>(2)
        var cursor = startWallMs
        val end = startWallMs + durationMs
        var days = 0
        while (cursor < end) {
            val dayId = dayIdAt(cursor)
            val boundary = nextBoundaryAfter(cursor)
            val sliceEnd = if (++days >= MAX_DAYS) end else minOf(boundary, end)
            slices += DaySlice(dayId, sliceEnd - cursor)
            cursor = sliceEnd
        }
        return slices
    }

    private companion object {
        /** An accounted interval should never span more than a few days; this is a stop. */
        const val MAX_DAYS = 400
    }
}
