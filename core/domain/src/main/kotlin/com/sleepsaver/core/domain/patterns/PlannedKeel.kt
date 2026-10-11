package com.sleepsaver.core.domain.patterns

import com.sleepsaver.core.domain.circadian.isWeekend
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.ScheduleTimes
import com.sleepsaver.core.domain.schedule.WeekPlan
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The Week plan editor's plan check: the Planned keel and the social-jetlag notice. */
data class PlanCheck(
    val plannedKeel: Int?,
    val plannedBand: KeelBand?,
    /** circular mean W(free) − W(alarm days), wrapped; null without both kinds. */
    val freeDayWakeShift: Duration?,
    /** planned social jetlag (signed). */
    val plannedSocialJetlag: Duration?,
    val warnFreeDayShift: Boolean,
    val warnSocialJetlag: Boolean,
) {
    val warn: Boolean get() = warnFreeDayShift || warnSocialJetlag
}

/**
 * Keel of the plan itself over the next plannedDays wake dates: asleep [B + latency, W] each night, scored like
 * [KeelCalculator.score], plus the free-day wake shift and planned social jetlag (§2.8).
 *
 *  - timing = the settings with this [week] and [oneOffs]; latency = timing.latency ?: sleepLatency.
 *  - For each wake date D in from .. from + (plannedDays − 1): W = ScheduleTimes.wake(D) in [zone],
 *    B = ScheduleTimes.bedtime(D − 1); asleep [B + latency, W] when W is later; window [noon(D − 1), noon(D)) in [zone].
 *  - Alarm days are the dates whose resolved day has an alarm; with no alarm in the whole span, Monday–Friday are
 *    the alarm days and the weekend is free.
 *  - freeDayWakeShift and plannedSocialJetlag (mid-sleep) are circular-mean differences free − alarm days, wrapped and
 *    rounded to the minute; null without both kinds. Warnings: free days wake more than freeDayShiftWarning later, or
 *    |planned social jetlag| ≥ jetlagWarning.
 *
 * Example: alarms at 07:00 on weekdays and set 09:00 weekend mornings with a fixed 23:00 bedtime → free days wake
 * 2 h later (warning) and mid-sleep moves 1 h later (warning).
 */
class PlannedKeelCalculator(private val config: SleepConfig = SleepConfig.DEFAULT) {

    private val times = ScheduleTimes(config)
    private val keelCalculator = KeelCalculator(config)

    /** [from] = the first wake date (tomorrow). Uses ScheduleTimes for W and B. */
    fun check(
        week: WeekPlan,
        oneOffs: OneOffs,
        nights: List<Night>,
        settings: UserSleepSettings,
        from: LocalDate,
        zone: ZoneId,
    ): PlanCheck {
        val timing = settings.copy(week = week, oneOffs = oneOffs).timing
        val latency = timing.latency ?: config.sleepLatency
        val planned = (0 until config.keel.plannedDays).map { i ->
            val date = from.plusDays(i.toLong())
            val wake = ZonedDateTime.of(date, times.wake(date, nights, timing).first, zone)
            val asleepAt = times.bedtime(date.minusDays(1), zone, nights, settings.sleepNeed, timing).first + latency
            PlannedNight(date, wake, asleepAt, week.resolve(date, oneOffs).alarmOn)
        }

        val days = planned.map { night ->
            KeelDay(
                wakeDate = night.date,
                windowStart = ZonedDateTime.of(night.date.minusDays(1), LocalTime.NOON, zone).toInstant(),
                windowEnd = ZonedDateTime.of(night.date, LocalTime.NOON, zone).toInstant(),
                asleep = night.span?.let(::listOf).orEmpty(),
            )
        }
        val plannedKeel = keelCalculator.score(days)

        val anyAlarm = planned.any { it.alarmOn }
        val (alarmDays, freeDays) = planned.partition { if (anyAlarm) it.alarmOn else !it.date.isWeekend() }
        val freeDayWakeShift = shift(
            free = freeDays.map { minuteOfDay(it.wake.toLocalTime()) },
            alarm = alarmDays.map { minuteOfDay(it.wake.toLocalTime()) },
        )
        val plannedSocialJetlag = shift(
            free = freeDays.mapNotNull { it.midSleepMinute(zone) },
            alarm = alarmDays.mapNotNull { it.midSleepMinute(zone) },
        )
        return PlanCheck(
            plannedKeel = plannedKeel,
            plannedBand = plannedKeel?.let(keelCalculator::band),
            freeDayWakeShift = freeDayWakeShift,
            plannedSocialJetlag = plannedSocialJetlag,
            warnFreeDayShift = freeDayWakeShift != null && freeDayWakeShift > config.keel.freeDayShiftWarning,
            warnSocialJetlag = plannedSocialJetlag != null && plannedSocialJetlag.abs() >= config.keel.jetlagWarning,
        )
    }

    /** Circular mean of [free] − circular mean of [alarm], wrapped and rounded; null when either side is empty. */
    private fun shift(free: List<Double>, alarm: List<Double>): Duration? {
        if (free.isEmpty() || alarm.isEmpty()) return null
        return roundedMinutes(wrapMinutes(circularMeanMinutes(free) - circularMeanMinutes(alarm)))
    }

    /** One planned night: wake W on [date] and asleep from [asleepAt] (= B + latency). */
    private class PlannedNight(val date: LocalDate, val wake: ZonedDateTime, val asleepAt: Instant, val alarmOn: Boolean) {
        val span: SleepSpan? get() = wake.toInstant().takeIf { it > asleepAt }?.let { SleepSpan(asleepAt, it) }

        fun midSleepMinute(zone: ZoneId): Double? = span?.let { span ->
            val mid = span.start.plusMillis(Duration.between(span.start, span.end).toMillis() / 2)
            minuteOfDay(mid.atZone(zone).toLocalTime())
        }
    }
}
