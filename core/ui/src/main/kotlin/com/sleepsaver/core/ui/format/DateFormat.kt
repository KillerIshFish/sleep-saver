package com.sleepsaver.core.ui.format

import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

// Copy is English-only for now; keep day and month names in the same language as the words around them.
private val English = Locale.US

/**
 * Label for the night that ended on the morning of [wakeDate]:
 * "Last night" (woke today), "Saturday night" (started in the last 6 days),
 * otherwise the date it started, "Mon, Sep 28" (with the year when it isn't [today]'s year).
 */
fun formatNightLabel(wakeDate: LocalDate, today: LocalDate): String {
    val nightOf = wakeDate.minusDays(1)
    val daysAgo = ChronoUnit.DAYS.between(nightOf, today)
    return when {
        daysAgo == 1L -> "Last night"
        daysAgo in 2..6 -> "${nightOf.dayOfWeek.getDisplayName(TextStyle.FULL, English)} night"
        else -> formatShortDate(nightOf, today)
    }
}

/** "Mon, Sep 28"; "Mon, Dec 29, 2025" when [date] isn't in [today]'s year. */
fun formatShortDate(date: LocalDate, today: LocalDate): String {
    val base = "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, English)}, ${date.monthDay()}"
    return if (date.year == today.year) base else "$base, ${date.year}"
}

/** "Sep 22 – Oct 5"; "Dec 22, 2025 – Jan 4, 2026" across a new year. */
fun formatWindowHeader(from: LocalDate, to: LocalDate): String =
    if (from.year == to.year) {
        from.monthDay() + RANGE_SEPARATOR + to.monthDay()
    } else {
        "${from.monthDay()}, ${from.year}" + RANGE_SEPARATOR + "${to.monthDay()}, ${to.year}"
    }

private fun LocalDate.monthDay(): String = "${month.getDisplayName(TextStyle.SHORT, English)} $dayOfMonth"
