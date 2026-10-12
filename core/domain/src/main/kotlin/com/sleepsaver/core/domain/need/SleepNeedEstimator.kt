package com.sleepsaver.core.domain.need

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Confidence
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepNeedEstimate
import com.sleepsaver.core.domain.model.SleepNeedEstimateResult
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.floor

/**
 * Estimates sleep need from "free" nights (docs/PLAN.md §3.2).
 *
 *  - Uses present nights with wakeDate ≤ today, at most `needEstimateLookbackNights` most recent.
 *  - Requires ≥ `needEstimateMinNights` present nights.
 *  - typical weekday wake = median local wake time (Night.wokeUp in wokeUpOffset) over nights whose
 *    wakeDate is Mon–Fri.
 *  - free night = wake ≥ typical weekday wake + `freeNightWakeDelay` (compare as local time of day). Free means
 *    "woke well after the usual weekday time", never "no alarm": sleep data doesn't say whether an alarm rang.
 *  - drop a free night if the night before it (wakeDate − 1) is present and its total < currentNeed − `shortNightMargin`.
 *  - estimate = median totalSleep of remaining free nights, rounded to `needRounding`, clamped [needClampMin, needClampMax].
 *  - requires ≥ `needMinFreeNights`; confidence from count + IQR (see SleepConfig).
 *
 * Medians average the two middle values; quartiles interpolate linearly between ranks (IQR = Q3 − Q1).
 */
class SleepNeedEstimator(private val config: SleepConfig = SleepConfig.DEFAULT) {

    fun estimate(nights: List<Night>, currentNeed: Duration, today: LocalDate): SleepNeedEstimateResult {
        val presentByDate = nights.filter { it.isPresent && !it.wakeDate.isAfter(today) }.associateBy { it.wakeDate }
        val recent = presentByDate.values.sortedByDescending { it.wakeDate }.take(config.needEstimateLookbackNights)
        val wakes = recent.mapNotNull { night -> localWake(night)?.let { night to it.toSecondOfDay().toDouble() } }
        val weekdayWakes = wakes.filter { (night, _) -> night.wakeDate.dayOfWeek !in WEEKEND }.map { it.second }.sorted()
        val typicalWake = if (weekdayWakes.isEmpty()) null else quantile(weekdayWakes, 0.5)

        val freeTotals = if (typicalWake == null) {
            emptyList()
        } else {
            val freeFrom = typicalWake + config.freeNightWakeDelay.seconds
            val shortNight = currentNeed.minus(config.shortNightMargin)
            wakes.filter { (_, wake) -> wake >= freeFrom }
                .map { it.first }
                .filterNot { night -> presentByDate[night.wakeDate.minusDays(1)]?.let { it.totalSleep < shortNight } == true }
                .map { it.totalSleep.toSecondsDouble() }
                .sorted()
        }

        if (typicalWake == null || recent.size < config.needEstimateMinNights || freeTotals.size < config.needMinFreeNights) {
            return SleepNeedEstimateResult.NotEnoughData(
                nightsSoFar = recent.size,
                nightsRequired = config.needEstimateMinNights,
                freeNightsSoFar = freeTotals.size,
                freeNightsRequired = config.needMinFreeNights,
            )
        }

        val step = config.needRounding.seconds
        val need = Duration.ofSeconds(Math.round(quantile(freeTotals, 0.5) / step) * step)
            .coerceIn(config.needClampMin, config.needClampMax)
        val iqr = Duration.ofSeconds(Math.round(quantile(freeTotals, 0.75) - quantile(freeTotals, 0.25)))
        val n = freeTotals.size
        val confidence = when {
            n >= config.needHighConfidenceNights && iqr <= config.needHighConfidenceIqr -> Confidence.HIGH
            n >= config.needMediumConfidenceNights && iqr <= config.needMediumConfidenceIqr -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        return SleepNeedEstimateResult.Available(
            SleepNeedEstimate(
                need = need,
                freeNightsUsed = n,
                interquartileRange = iqr,
                confidence = confidence,
                typicalWeekdayWake = LocalTime.ofSecondOfDay(Math.round(typicalWake)),
                freeNightWakeDelay = config.freeNightWakeDelay,
            ),
        )
    }

    /**
     * Whether to show the suggestion card. Never when the estimate is within `needReofferMinChange` of the
     * current need. After the user ignored [lastDismissedValue] at [lastDismissedAt], offer again only when
     * the estimate differs from that value by ≥ `needReofferMinChange` AND ≥ `needReofferAfterDays` have passed.
     *
     * "Within" is strict: a difference of exactly `needReofferMinChange` is offered.
     */
    fun shouldOffer(
        estimate: SleepNeedEstimate,
        currentNeed: Duration,
        lastDismissedValue: Duration?,
        lastDismissedAt: Instant?,
        now: Instant,
    ): Boolean {
        val minChange = config.needReofferMinChange
        if (estimate.need.minus(currentNeed).abs() < minChange) return false
        if (lastDismissedValue == null) return true
        val movedSinceDismissal = estimate.need.minus(lastDismissedValue).abs() >= minChange
        val waitedLongEnough = lastDismissedAt == null ||
            Duration.between(lastDismissedAt, now) >= Duration.ofDays(config.needReofferAfterDays)
        return movedSinceDismissal && waitedLongEnough
    }

    private fun localWake(night: Night): LocalTime? {
        val wokeUp = night.wokeUp ?: return null
        val offset = night.wokeUpOffset ?: night.main?.endOffset ?: return null
        // Not LocalTime.ofInstant: that needs API 31 and minSdk is 28.
        return LocalDateTime.ofInstant(wokeUp, offset).toLocalTime()
    }

    private companion object {
        val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

        /** Linear interpolation between closest ranks (h = (n − 1)·p) over an ascending, non-empty list. */
        fun quantile(sorted: List<Double>, p: Double): Double {
            val h = (sorted.size - 1) * p
            val lo = floor(h).toInt()
            val hi = minOf(lo + 1, sorted.lastIndex)
            return sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo])
        }
    }
}

private fun Duration.toSecondsDouble(): Double = seconds + nano / 1e9
