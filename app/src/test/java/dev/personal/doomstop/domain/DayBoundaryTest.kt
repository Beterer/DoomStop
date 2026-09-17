package dev.personal.doomstop.domain

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DayBoundaryTest {

    private val bucharest = DayBoundary(ZoneId.of("Europe/Bucharest"))

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun `day id uses the fixed accounting zone, not UTC`() {
        // 22:30 UTC is already the next day in Bucharest (UTC+3 in September).
        assertEquals("2026-09-09", bucharest.dayIdAt(at("2026-09-08T22:30:00Z")))
        assertEquals("2026-09-08", bucharest.dayIdAt(at("2026-09-08T20:30:00Z")))
    }

    @Test
    fun `an interval inside one day is a single slice`() {
        val slices = bucharest.split(at("2026-09-08T09:00:00Z"), 90 * 60_000L)
        assertEquals(listOf(DaySlice("2026-09-08", 90 * 60_000L)), slices)
    }

    @Test
    fun `an interval crossing midnight is split between the correct days`() {
        // 20:50 UTC = 23:50 local; run for 20 minutes, so 10 before and 10 after midnight.
        val slices = bucharest.split(at("2026-09-08T20:50:00Z"), 20 * 60_000L)
        assertEquals(2, slices.size)
        assertEquals(DaySlice("2026-09-08", 10 * 60_000L), slices[0])
        assertEquals(DaySlice("2026-09-09", 10 * 60_000L), slices[1])
        assertEquals(20 * 60_000L, slices.sumOf { it.durationMs })
    }

    @Test
    fun `splitting never creates or destroys time`() {
        val start = at("2026-09-08T21:00:00Z")
        for (durationMs in listOf(0L, 1L, 59_000L, 3 * 3600_000L, 50 * 3600_000L)) {
            val total = bucharest.split(start, durationMs).sumOf { it.durationMs }
            assertEquals("duration $durationMs", durationMs, total)
        }
    }

    @Test
    fun `zero duration produces no slices`() {
        assertTrue(bucharest.split(at("2026-09-08T09:00:00Z"), 0).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative duration is refused rather than silently charged`() {
        bucharest.split(at("2026-09-08T09:00:00Z"), -1)
    }

    @Test
    fun `next boundary is the following local midnight`() {
        val next = bucharest.nextBoundaryAfter(at("2026-09-08T09:00:00Z"))
        assertEquals("2026-09-09", bucharest.dayIdAt(next))
        assertEquals(next, bucharest.startOfDayMs("2026-09-09"))
    }

    @Test
    fun `the previous day crosses month, year and leap-day boundaries`() {
        assertEquals("2026-09-07", bucharest.previousDayId("2026-09-08"))
        assertEquals("2026-08-31", bucharest.previousDayId("2026-09-01"))
        assertEquals("2026-12-31", bucharest.previousDayId("2027-01-01"))
        assertEquals("2028-02-29", bucharest.previousDayId("2028-03-01"))
    }

    @Test
    fun `a spring-forward day that skips local midnight still resolves`() {
        // Santiago's DST transition moves the clock forward at local midnight, so
        // LocalDate.atStartOfDay must be used rather than assuming 00:00 exists.
        val santiago = DayBoundary(ZoneId.of("America/Santiago"))
        val dayId = "2026-09-06"
        val start = santiago.startOfDayMs(dayId)
        assertEquals(dayId, santiago.dayIdAt(start))
        val slices = santiago.split(start - 30 * 60_000L, 60 * 60_000L)
        assertEquals(60 * 60_000L, slices.sumOf { it.durationMs })
    }
}
