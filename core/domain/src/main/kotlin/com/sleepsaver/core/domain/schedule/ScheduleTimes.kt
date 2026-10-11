package com.sleepsaver.core.domain.schedule

import com.sleepsaver.core.domain.circadian.SleepTimingEstimator
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.BedtimeMode
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepTiming
import com.sleepsaver.core.domain.model.TimingSource
import com.sleepsaver.core.domain.model.WakeTimeMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * W and B as the schedule uses them, plan-aware and "usual" (plan ignored). One home for the logic the energy
 * schedule, the alarm planner's bedtime, Settings rows, the debt preview and planned keel all share.
 *
 * Example: with Tuesday's morning set to 05:30, `wake(tuesday)` is (05:30, WEEK_PLAN) while `usualWake(tuesday)` stays
 * the learned 06:50 (LEARNED); with BedtimeMode.SLEEP_NEED, `bedtime(monday)` moves earlier with it and
 * `usualBedtime(monday)` doesn't.
 *
 * Learned times use only nights strictly before the date they are for ([SleepTimingEstimator]), so passing every night
 * is the same as passing the history up to the day before.
 */
class ScheduleTimes(private val config: SleepConfig = SleepConfig.DEFAULT) {

    private val estimator = SleepTimingEstimator(config)

    /** W on [date]: the resolved day's own time (set time or just-once) → WEEK_PLAN, else [usualWake]. */
    fun wake(date: LocalDate, nights: List<Night>, timing: SleepTiming): Pair<LocalTime, TimingSource> =
        timing.week.resolve(date, timing.oneOffs).wakeTime?.let { it to TimingSource.WEEK_PLAN }
            ?: usualWake(date, nights, timing)

    /**
     * W ignoring the plan's own times: FIXED → (timing.wakeTime, FIXED); FROM_DATA → SleepTimingEstimator.wakeTime
     * (nights strictly before date) → LEARNED, else (timing.wakeTime, NOT_ENOUGH_DATA). Never null.
     */
    fun usualWake(date: LocalDate, nights: List<Night>, timing: SleepTiming): Pair<LocalTime, TimingSource> =
        when (timing.wakeTimeMode) {
            WakeTimeMode.FIXED -> timing.wakeTime to TimingSource.FIXED
            WakeTimeMode.FROM_DATA -> estimator.wakeTime(nights, date)?.let { it to TimingSource.LEARNED }
                ?: (timing.wakeTime to TimingSource.NOT_ENOUGH_DATA)
        }

    /**
     * B on the evening of [date]: the evening plan's set bedtime → WEEK_PLAN, else
     * `usualBedtime(date, zone, nights, need, timing, wakeNext = wake(date + 1, nights, timing).first)`, so the
     * schedule's SLEEP_NEED bedtime follows tomorrow's plan-aware wake time.
     */
    fun bedtime(date: LocalDate, zone: ZoneId, nights: List<Night>, need: Duration, timing: SleepTiming): Pair<Instant, TimingSource> {
        val planned = timing.week.resolve(date, timing.oneOffs).evening.bedtime
        if (planned != null) return eveningOf(date, planned, zone) to TimingSource.WEEK_PLAN
        return usualBedtime(date, zone, nights, need, timing, wakeNext = wake(date.plusDays(1), nights, timing).first)
    }

    /**
     * B by the Settings rule, ignoring the plan: SLEEP_NEED → (wakeNext ?: usualWake(date + 1).first) on date + 1,
     * as an instant, − need − latency; FIXED → eveningOf(timing.bedtime); FROM_DATA → learned fell-asleep for
     * date + 1 − latency, else eveningOf(timing.bedtime) NOT_ENOUGH_DATA. latency = timing.latency ?: config.sleepLatency.
     * Settings calls it without [wakeNext] (plan-free); only [bedtime] passes the plan-aware wake.
     */
    fun usualBedtime(
        date: LocalDate,
        zone: ZoneId,
        nights: List<Night>,
        need: Duration,
        timing: SleepTiming,
        wakeNext: LocalTime? = null,
    ): Pair<Instant, TimingSource> {
        val latency = timing.latency ?: config.sleepLatency
        val tomorrow = date.plusDays(1)
        return when (timing.bedtimeMode) {
            BedtimeMode.SLEEP_NEED -> {
                val wake = wakeNext ?: usualWake(tomorrow, nights, timing).first
                ZonedDateTime.of(tomorrow, wake, zone).toInstant() - need - latency to TimingSource.SLEEP_NEED
            }
            BedtimeMode.FIXED -> eveningOf(date, timing.bedtime, zone) to TimingSource.FIXED
            BedtimeMode.FROM_DATA -> estimator.fellAsleepTime(nights, tomorrow)
                ?.let { eveningOf(date, it, zone) - latency to TimingSource.LEARNED }
                ?: (eveningOf(date, timing.bedtime, zone) to TimingSource.NOT_ENOUGH_DATA)
        }
    }

    /**
     * [time] on the evening of [date]; before noon = the next calendar day (01:30 on Monday evening is Tuesday 01:30).
     * ZonedDateTime.of (gap → forward, overlap → earlier offset).
     */
    fun eveningOf(date: LocalDate, time: LocalTime, zone: ZoneId): Instant =
        ZonedDateTime.of(if (time.isBefore(LocalTime.NOON)) date.plusDays(1) else date, time, zone).toInstant()
}
