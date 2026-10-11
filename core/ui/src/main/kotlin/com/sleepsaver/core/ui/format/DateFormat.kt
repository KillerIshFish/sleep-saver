package com.sleepsaver.core.ui.format

import java.time.DayOfWeek
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

// ---------------------------------------------------------------- Days of the week

/** "Mon". */
fun formatDayShort(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, English)

/** "M": the first letter of the day's name (so Tuesday and Thursday are both "T"; pair it with [formatDayLong]). */
fun formatDayNarrow(day: DayOfWeek): String = formatDayLong(day).take(1)

/** "Monday". */
fun formatDayLong(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, English)

/**
 * A set of weekdays in a few words, Monday first: "Every day", "Weekdays" (Mon–Fri), "Weekends" (Sat and Sun),
 * a run of three or more days as a range ("Mon–Thu", "Fri–Sun"), otherwise a list ("Mon, Wed, Fri", "Tue, Wed"),
 * and "No days" for an empty set. Runs don't wrap past Sunday: Sat, Sun, Mon reads "Mon, Sat, Sun".
 */
fun formatDays(days: Set<DayOfWeek>): String {
    val sorted = days.sorted()
    return when {
        sorted.isEmpty() -> "No days"
        sorted.size == DayOfWeek.entries.size -> "Every day"
        days == Weekdays -> "Weekdays"
        days == Weekend -> "Weekends"
        sorted.size >= MIN_RANGE_DAYS && sorted.last().value - sorted.first().value == sorted.size - 1 ->
            formatDayShort(sorted.first()) + DAY_RANGE_SEPARATOR + formatDayShort(sorted.last())
        else -> sorted.joinToString(", ") { formatDayShort(it) }
    }
}

private val Weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
private val Weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

/** Two days in a row read better as a list ("Tue, Wed") than as a range. */
private const val MIN_RANGE_DAYS = 3

/** En dash without spaces: "Mon–Thu" (time ranges use the spaced [RANGE_SEPARATOR]). */
private const val DAY_RANGE_SEPARATOR = "–"
