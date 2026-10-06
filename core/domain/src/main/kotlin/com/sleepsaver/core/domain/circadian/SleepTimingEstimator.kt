package com.sleepsaver.core.domain.circadian

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.LearnedSleepTiming
import com.sleepsaver.core.domain.model.Night
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Typical wake and fall-asleep times learned from recent main sleeps (naps and extra segments ignored; edits count).
 *
 * For a target wake date T:
 *  - Nights with wakeDate in [T − timingLookbackDays, T): strictly before T, so a schedule is a prediction and doesn't
 *    move when that night's own data arrives.
 *  - Weekdays and weekends apart, because alarms usually are: only nights whose wake date is the same kind of day as T
 *    (Saturday/Sunday vs Monday–Friday) when there are at least `timingMinSameKindNights` of them; otherwise every
 *    night when there are at least `anchorMinNights`; otherwise null (not enough data).
 *  - The most recent `anchorMaxNights`, weighted anchorDecay^(i−1), averaged as a circular mean of the local time of
 *    day (each night in its own recorded offset, so travel and DST don't skew it), rounded to the minute.
 */
class SleepTimingEstimator(private val config: SleepConfig = SleepConfig.DEFAULT) {

    /** Typical wake time on [wakeDate], or null without enough nights. */
    fun wakeTime(nights: List<Night>, wakeDate: LocalDate): LocalTime? = estimate(nights, wakeDate, ::wokeUpLocal)

    /** Typical time of falling asleep on the night that ends on [wakeDate], or null without enough nights. */
    fun fellAsleepTime(nights: List<Night>, wakeDate: LocalDate): LocalTime? = estimate(nights, wakeDate, ::fellAsleepLocal)

    /** What has been learned as of [today], for the next weekday and the next weekend day. */
    fun learned(nights: List<Night>, today: LocalDate): LearnedSleepTiming {
        val nextWeekday = generateSequence(today.plusDays(1)) { it.plusDays(1) }.first { !it.isWeekend() }
        val nextWeekendDay = generateSequence(today.plusDays(1)) { it.plusDays(1) }.first { it.isWeekend() }
        return LearnedSleepTiming(
            weekdayWake = wakeTime(nights, nextWeekday),
            weekendWake = wakeTime(nights, nextWeekendDay),
            weekdayFellAsleep = fellAsleepTime(nights, nextWeekday),
            weekendFellAsleep = fellAsleepTime(nights, nextWeekendDay),
        )
    }

    private fun estimate(nights: List<Night>, target: LocalDate, timeOf: (Night) -> LocalTime?): LocalTime? {
        val from = target.minusDays(config.timingLookbackDays)
        val candidates = nights
            .filter { it.isPresent && it.wakeDate.isBefore(target) && !it.wakeDate.isBefore(from) }
            .sortedByDescending { it.wakeDate }
            .mapNotNull { night -> timeOf(night)?.let { night.wakeDate to it } }
        val sameKind = candidates.filter { (wakeDate, _) -> wakeDate.isWeekend() == target.isWeekend() }
        val chosen = when {
            sameKind.size >= config.timingMinSameKindNights -> sameKind
            candidates.size >= config.anchorMinNights -> candidates
            else -> return null
        }
        val minutes = chosen.take(config.anchorMaxNights).map { (_, time) -> time.toSecondOfDay() / 60.0 }
        return circularMeanTime(minutes, config.anchorDecay)
    }

    private fun wokeUpLocal(night: Night): LocalTime? = localTime(
        instant = night.wokeUp ?: night.main?.end,
        offset = night.wokeUpOffset ?: night.main?.endOffset ?: night.override?.offset,
    )

    private fun fellAsleepLocal(night: Night): LocalTime? = localTime(
        instant = night.fellAsleep ?: night.main?.start,
        offset = night.fellAsleepOffset ?: night.main?.startOffset ?: night.override?.offset,
    )

    private fun localTime(instant: Instant?, offset: ZoneOffset?): LocalTime? =
        if (instant == null || offset == null) null else instant.atOffset(offset).toLocalTime()
}

internal fun LocalDate.isWeekend(): Boolean = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY

/**
 * Circular mean of times of day given as minutes after midnight, most recent first, weighted decay^(i−1);
 * 23:30 and 00:30 average to 00:00, not noon. Rounded to the minute.
 */
internal fun circularMeanTime(minutes: List<Double>, decay: Double): LocalTime {
    var sinSum = 0.0
    var cosSum = 0.0
    var weight = 1.0
    for (minute in minutes) {
        val theta = 2 * PI * minute / MINUTES_PER_DAY
        sinSum += weight * sin(theta)
        cosSum += weight * cos(theta)
        weight *= decay
    }
    val mean = atan2(sinSum, cosSum) * MINUTES_PER_DAY / (2 * PI)
    val minute = Math.floorMod(mean.roundToLong(), MINUTES_PER_DAY.toLong())
    return LocalTime.ofSecondOfDay(minute * 60)
}

private const val MINUTES_PER_DAY = 1440.0
