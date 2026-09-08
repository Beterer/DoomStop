package dev.personal.doomstop.domain

/**
 * The three clocks the accounting core needs, behind an interface so tests can advance
 * time by days without sleeping and can simulate reboots and clock changes.
 *
 * They are genuinely different clocks and are never interchanged:
 *  - [elapsedRealtimeMs] measures DURATION and is meaningful only within one boot;
 *  - [wallTimeMs] places things on the calendar and can jump in either direction;
 *  - [bootId] says which boot the monotonic reading belongs to.
 */
interface ClockSource {
    fun elapsedRealtimeMs(): Long
    fun wallTimeMs(): Long
    fun bootId(): String
}
