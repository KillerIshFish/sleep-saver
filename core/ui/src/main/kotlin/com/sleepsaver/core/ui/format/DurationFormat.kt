package com.sleepsaver.core.ui.format

import java.time.Duration
import kotlin.math.abs
import kotlin.math.roundToLong

/** U+2212: as wide as "+", so signed numbers line up. Use it instead of a hyphen for negative values. */
const val MINUS = "−"

/**
 * "8h", "6h 55m", "45m", "0m". Negative durations get a [MINUS] ("−1h 5m").
 * Rounded to the nearest minute.
 */
fun formatDuration(duration: Duration): String {
    val minutes = duration.roundToMinutes()
    val m = abs(minutes)
    val h = m / 60
    val rest = m % 60
    val body = when {
        h == 0L -> "${rest}m"
        rest == 0L -> "${h}h"
        else -> "${h}h ${rest}m"
    }
    return if (minutes < 0) MINUS + body else body
}

/** Hours with one decimal: "3.7", "0.0", "−0.4". Rounded half away from zero. */
fun formatHours1(duration: Duration): String = formatTenths(duration.roundToTenthsOfHour())

/** Hours with one decimal: "3.7", "0.0", "−0.4". Rounded half away from zero. */
fun formatHours1(hours: Double): String {
    val tenths = (abs(hours) * 10).roundToLong()
    return formatTenths(if (hours < 0) -tenths else tenths)
}

/**
 * Debt change since yesterday: "+1.1 hrs", "−0.2 hrs" (real minus sign), "0.0 hrs" when it rounds to zero.
 */
fun formatDebtChange(change: Duration): String {
    val tenths = change.roundToTenthsOfHour()
    val sign = if (tenths > 0) "+" else ""
    return "$sign${formatTenths(tenths)} hrs"
}

/** Whole minutes, rounded half away from zero. */
internal fun Duration.roundToMinutes(): Long {
    val ms = toMillis()
    val minutes = (abs(ms) + 30_000) / 60_000
    return if (ms < 0) -minutes else minutes
}

private fun Duration.roundToTenthsOfHour(): Long {
    val ms = toMillis()
    val tenths = (abs(ms) + 180_000) / 360_000
    return if (ms < 0) -tenths else tenths
}

private fun formatTenths(tenths: Long): String {
    val t = abs(tenths)
    val body = "${t / 10}.${t % 10}"
    return if (tenths < 0) MINUS + body else body
}
