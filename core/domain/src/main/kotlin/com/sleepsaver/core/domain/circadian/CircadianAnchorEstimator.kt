package com.sleepsaver.core.domain.circadian

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.CircadianAnchor
import com.sleepsaver.core.domain.model.Night
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Body-clock timing from main-sleep midpoints (docs/PLAN.md §3.5). Naps and extra segments are ignored.
 *
 *  - Candidates: present nights with a main sleep (or manual night) and wakeDate in (asOf − anchorLookbackDays, asOf],
 *    most recent first, at most `anchorMaxNights`. Effective fellAsleep/wokeUp are used (edits count).
 *  - midpoint local minute-of-day m_i uses the night's own offsets (midpoint instant shown in wokeUpOffset).
 *  - w_i = anchorDecay^(i−1), θ_i = 2π m_i / 1440, anchor = atan2(Σ w sin θ, Σ w cos θ) mapped to [0, 1440).
 *  - Fewer than `anchorMinNights` → fallback: anchor = fallbackWake − need/2, isFallback = true.
 *  - typicalWake = anchor + need/2 (mod 24 h), rounded to the minute.
 */
class CircadianAnchorEstimator(private val config: SleepConfig = SleepConfig.DEFAULT) {
    fun estimate(nights: List<Night>, asOf: LocalDate, need: Duration, fallbackWake: LocalTime): CircadianAnchor {
        val firstExcluded = asOf.minusDays(config.anchorLookbackDays)
        val midpoints = nights
            .filter { it.isPresent && it.wakeDate.isAfter(firstExcluded) && !it.wakeDate.isAfter(asOf) }
            .sortedByDescending { it.wakeDate }
            .mapNotNull { midpointMinuteOfDay(it) }
            .take(config.anchorMaxNights)
        val halfNeedMinutes = need.toMillis() / MILLIS_PER_MINUTE / 2

        val anchor = if (midpoints.size < config.anchorMinNights) {
            wrapMinutes(fallbackWake.toNanoOfDay() / NANOS_PER_MINUTE - halfNeedMinutes)
        } else {
            var sinSum = 0.0
            var cosSum = 0.0
            var weight = 1.0
            for (minute in midpoints) {
                val theta = 2 * PI * minute / MINUTES_PER_DAY
                sinSum += weight * sin(theta)
                cosSum += weight * cos(theta)
                weight *= config.anchorDecay
            }
            wrapMinutes(atan2(sinSum, cosSum) * MINUTES_PER_DAY / (2 * PI))
        }
        val wakeMinute = Math.floorMod((anchor + halfNeedMinutes).roundToLong(), MINUTES_PER_DAY.toLong())
        return CircadianAnchor(
            midpointMinuteOfDay = anchor,
            typicalWake = LocalTime.ofSecondOfDay(wakeMinute * 60),
            nightsUsed = midpoints.size,
            isFallback = midpoints.size < config.anchorMinNights,
        )
    }

    private fun midpointMinuteOfDay(night: Night): Double? {
        val start = night.fellAsleep ?: night.main?.start ?: return null
        val end = night.wokeUp ?: night.main?.end ?: return null
        val offset = night.wokeUpOffset ?: night.main?.endOffset ?: night.fellAsleepOffset ?: return null
        if (!end.isAfter(start)) return null
        val midpoint = start.plus(Duration.between(start, end).dividedBy(2))
        return LocalDateTime.ofInstant(midpoint, offset).toLocalTime().toNanoOfDay() / NANOS_PER_MINUTE
    }

    private fun wrapMinutes(minutes: Double): Double =
        ((minutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY

    private companion object {
        const val MINUTES_PER_DAY = 1440.0
        const val MILLIS_PER_MINUTE = 60_000.0
        const val NANOS_PER_MINUTE = 60_000_000_000.0
    }
}
