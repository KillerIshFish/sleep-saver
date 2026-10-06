package com.sleepsaver.core.ui.format

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** En dash with spaces, for ranges. */
internal const val RANGE_SEPARATOR = " – "

/** "11:47 PM" or "23:47". */
fun formatClockTime(time: LocalTime, use24h: Boolean): String =
    if (use24h) "${time.hour.pad2()}:${time.minute.pad2()}" else "${time.hour12()}:${time.minute.pad2()} ${time.meridiem()}"

/** "11:47 PM" or "23:47", in [zone] (pass the recorded `ZoneOffset` for sleep data). */
fun formatClockTime(instant: Instant, zone: ZoneId, use24h: Boolean): String =
    formatClockTime(instant.localTime(zone), use24h)

/** Compact axis label: "11p", "12a", "6:30a" or "23", "00", "06:30". */
fun formatClockAxis(time: LocalTime, use24h: Boolean): String {
    if (use24h) return if (time.minute == 0) time.hour.pad2() else "${time.hour.pad2()}:${time.minute.pad2()}"
    val suffix = if (time.hour < 12) "a" else "p"
    return if (time.minute == 0) "${time.hour12()}$suffix" else "${time.hour12()}:${time.minute.pad2()}$suffix"
}

/** Compact axis label for [instant] in [zone]: "11p" or "23". */
fun formatClockAxis(instant: Instant, zone: ZoneId, use24h: Boolean): String =
    formatClockAxis(instant.localTime(zone), use24h)

/**
 * "11:47 PM – 7:12 AM", or "9:15 – 11:30 AM" when both ends share AM/PM; "23:47 – 07:12" in 24-hour mode.
 */
fun formatTimeRange(start: LocalTime, end: LocalTime, use24h: Boolean): String {
    if (use24h) return formatClockTime(start, use24h) + RANGE_SEPARATOR + formatClockTime(end, use24h)
    val from = if (start.meridiem() == end.meridiem()) "${start.hour12()}:${start.minute.pad2()}" else formatClockTime(start, false)
    return from + RANGE_SEPARATOR + formatClockTime(end, false)
}

/** [formatTimeRange] for two instants shown in [zone]. */
fun formatTimeRange(start: Instant, end: Instant, zone: ZoneId, use24h: Boolean): String =
    formatTimeRange(start.localTime(zone), end.localTime(zone), use24h)

/**
 * A phase's start shift vs yesterday (negative = earlier): "↑13m earlier", "↓1h 5m later", or
 * "same as yesterday" under one minute. null (nothing to compare with) stays null.
 */
fun formatShift(shift: Duration?): String? {
    if (shift == null) return null
    val minutes = shift.roundToMinutes()
    if (shift.abs() < Duration.ofMinutes(1) || minutes == 0L) return "same as yesterday"
    val amount = formatDuration(Duration.ofMinutes(kotlin.math.abs(minutes)))
    return if (minutes < 0) "↑$amount earlier" else "↓$amount later"
}

private fun Instant.localTime(zone: ZoneId): LocalTime = atZone(zone).toLocalTime()

private fun LocalTime.hour12(): Int = (hour % 12).let { if (it == 0) 12 else it }

private fun LocalTime.meridiem(): String = if (hour < 12) "AM" else "PM"

private fun Int.pad2(): String = toString().padStart(2, '0')
