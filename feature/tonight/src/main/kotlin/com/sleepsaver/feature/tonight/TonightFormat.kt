package com.sleepsaver.feature.tonight

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.schedule.EveningPlan
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.formatDayShort
import com.sleepsaver.core.ui.format.keepTimesTogether
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

// Copy is English-only for now, like core:ui's format helpers.
private val English = Locale.US

/** "Wed 6:45 AM": a planned alarm's day and time (the next alarm, a week-plan subtitle). */
@Composable
@ReadOnlyComposable
internal fun dayAndTime(date: LocalDate, time: LocalTime): String =
    "${formatDayShort(date.dayOfWeek)} ${clockTime(time)}".keepTimesTogether()

/** "Oct 6": a date in the current year without its weekday ("Last test: rang at 9:14 PM on Oct 6"). */
internal fun formatMonthDay(date: LocalDate): String = "${date.month.getDisplayName(TextStyle.SHORT, English)} ${date.dayOfMonth}"

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

/**
 * Where an evening's Night steps start for [bedtime]: the wind-down phase start, `B − melatoninHalfWidth − length`
 * with the evening's length clamped to `week.windDownMinLength..windDownMaxLength` (as EnergyScheduleCalculator
 * places the phase). For rows that show an evening the schedule hasn't computed (the week plan, the day plan).
 */
internal fun nightStepsFrom(bedtime: Instant, evening: EveningPlan, config: SleepConfig = SleepConfig.DEFAULT): Instant =
    bedtime - config.melatoninHalfWidth - evening.windDownLength.coerceIn(config.week.windDownMinLength, config.week.windDownMaxLength)

/** This time rounded to the nearest [step] (half up), wrapping past midnight: 7:03 → 7:05 for 5-minute steps. */
internal fun LocalTime.roundTo(step: Duration): LocalTime {
    val stepMinutes = step.toMinutes().coerceAtLeast(1)
    val minutes = (toSecondOfDay() / 60 + stepMinutes / 2) / stepMinutes * stepMinutes
    return LocalTime.MIDNIGHT.plusMinutes(minutes % (24 * 60))
}
