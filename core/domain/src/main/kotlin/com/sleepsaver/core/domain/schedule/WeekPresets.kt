package com.sleepsaver.core.domain.schedule

import com.sleepsaver.core.domain.config.SleepConfig
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime

/**
 * Applies a preset to the mornings; evenings are untouched. [wake] is rounded to `week.timeRounding` first
 * (06:52 → 06:50).
 *  - WORK_WEEK: Mon–Fri wake at [wake], an alarm that was OFF becomes `alarm.defaultMode` with the weekday style;
 *    Sat–Sun have no set time and no alarm.
 *  - SAME_EVERY_DAY: all seven days wake at [wake]; an alarm that was OFF becomes `alarm.defaultMode`.
 */
fun WeekPlan.withPreset(preset: WeekPreset, wake: LocalTime, config: SleepConfig = SleepConfig.DEFAULT): WeekPlan {
    val time = wake.roundedTo(config.week.timeRounding)
    return WeekPlan(
        days.map { day ->
            val morning = day.morning
            val alarm = morning.alarm
            val wasOff = alarm.mode == AlarmMode.OFF
            val newMorning = when {
                preset == WeekPreset.WORK_WEEK && day.day.isWeekend() ->
                    morning.copy(wakeTime = null, alarm = alarm.copy(mode = AlarmMode.OFF))
                preset == WeekPreset.WORK_WEEK -> morning.copy(
                    wakeTime = time,
                    alarm = alarm.copy(
                        mode = if (wasOff) config.alarm.defaultMode else alarm.mode,
                        style = if (wasOff) config.alarm.defaultWeekdayStyle else alarm.style,
                    ),
                )
                else -> morning.copy(
                    wakeTime = time,
                    alarm = alarm.copy(mode = if (wasOff) config.alarm.defaultMode else alarm.mode),
                )
            }
            day.copy(morning = newMorning)
        },
    )
}

/**
 * This time rounded to the nearest multiple of [step] (half up), wrapping past midnight: 06:52 → 06:50,
 * 06:53 → 06:55 and 23:58 → 00:00 with a 5-min step. A step under a minute returns the time to the minute.
 */
fun LocalTime.roundedTo(step: Duration): LocalTime {
    val stepMinutes = step.toMinutes()
    val minuteOfDay = toSecondOfDay() / 60L
    if (stepMinutes <= 1) return LocalTime.ofSecondOfDay(minuteOfDay * 60)
    val rounded = Math.floorDiv(minuteOfDay + stepMinutes / 2, stepMinutes) * stepMinutes
    return LocalTime.ofSecondOfDay(Math.floorMod(rounded, MINUTES_PER_DAY) * 60)
}

private fun DayOfWeek.isWeekend(): Boolean = this == DayOfWeek.SATURDAY || this == DayOfWeek.SUNDAY

private const val MINUTES_PER_DAY = 1440L
