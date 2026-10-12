package com.sleepsaver.feature.tonight

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.formatDayShort
import com.sleepsaver.core.ui.format.formatShortDate
import com.sleepsaver.core.ui.format.keepTimesTogether
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

// Copy is English-only for now, like core:ui's format helpers.
private val English = Locale.US

/**
 * "Wed 6:45 AM": a planned alarm's day and time (the next alarm, a week-plan subtitle), with the date when the
 * weekday alone could mean another day ([nearDay]): "Mon, Oct 19, 6:50 AM" seen on a Monday.
 */
@Composable
@ReadOnlyComposable
internal fun dayAndTime(date: LocalDate, time: LocalTime, today: LocalDate): String {
    // "Wed 6:45 AM", but "Mon, Oct 19, 6:50 AM": a comma after a date.
    val separator = if (weekdayAlone(date, today)) " " else ", "
    return "${nearDay(date, today)}$separator${clockTime(time)}".keepTimesTogether()
}

/**
 * A date as its weekday ("Wed") while that can only mean the coming one, 1 to 5 days after [today]; otherwise with
 * the date ("Mon, Oct 19"), so today's weekday a week on, a date 6 or more days away (or today, or one past) is never
 * mistaken for a nearer one. The month and day stay on one line.
 */
internal fun nearDay(date: LocalDate, today: LocalDate): String =
    if (weekdayAlone(date, today)) {
        formatDayShort(date.dayOfWeek)
    } else {
        formatShortDate(date, today).replace(MONTH_DAY_SPACE, "$1\u00A0$2")
    }

private fun weekdayAlone(date: LocalDate, today: LocalDate): Boolean = ChronoUnit.DAYS.between(today, date) in WEEKDAY_ALONE_DAYS

/** Days after today that a bare weekday names unambiguously ("Next alarm: Thu" on a Monday is this Thursday). */
private val WEEKDAY_ALONE_DAYS = 1L..5L

/** The space in "Oct 19". */
private val MONTH_DAY_SPACE = Regex("([A-Za-z]{3}) (\\d)")

/**
 * "Oct 6": a date in the current year without its weekday ("Last test: rang at 9:14 PM on Oct 6"), with a no-break
 * space so the day never wraps away from its month.
 */
internal fun formatMonthDay(date: LocalDate): String =
    "${date.month.getDisplayName(TextStyle.SHORT, English)}\u00A0${date.dayOfMonth}"

/** "1 h 40 m", "2 h", "45 m": the spaced form the plan notices use ("Free days wake 1 h 40 m later …"). */
internal fun formatHoursSpaced(duration: Duration): String {
    val minutes = Math.abs((duration.toMillis() + if (duration.isNegative) -30_000 else 30_000) / 60_000)
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "$m m"
        m == 0L -> "$h h"
        else -> "$h h $m m"
    }
}

/** This time rounded to the nearest [step] (half up), wrapping past midnight: 7:03 → 7:05 for 5-minute steps. */
internal fun LocalTime.roundTo(step: Duration): LocalTime {
    val stepMinutes = step.toMinutes().coerceAtLeast(1)
    val minutes = (toSecondOfDay() / 60 + stepMinutes / 2) / stepMinutes * stepMinutes
    return LocalTime.MIDNIGHT.plusMinutes(minutes % (24 * 60))
}
