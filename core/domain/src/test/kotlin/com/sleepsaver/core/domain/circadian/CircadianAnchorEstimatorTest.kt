package com.sleepsaver.core.domain.circadian

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.NightOverride
import com.sleepsaver.core.domain.testing.Fx
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import org.junit.jupiter.api.Test

class CircadianAnchorEstimatorTest {

    private val asOf = LocalDate.of(2026, 9, 30)
    private val need = Duration.ofHours(8)
    private val fallback = LocalTime.of(7, 0)
    private val estimator = CircadianAnchorEstimator()

    private fun nights(days: LongRange, fellAsleep: String, wokeUp: String, offset: ZoneOffset = Fx.UTC): List<Night> =
        days.map { Fx.night(asOf.minusDays(it), fellAsleep, wokeUp, offset = offset) }

    @Test
    fun `steady nights give their midpoint and wake time`() {
        val anchor = estimator.estimate(nights(0L..6L, "23:00", "07:00"), asOf, need, fallback)

        assertThat(anchor.midpointMinuteOfDay).isWithin(1e-6).of(180.0)
        assertThat(anchor.typicalWake).isEqualTo(LocalTime.of(7, 0))
        assertThat(anchor.nightsUsed).isEqualTo(7)
        assertThat(anchor.isFallback).isFalse()
    }

    @Test
    fun `circular mean across midnight lands on midnight, not noon`() {
        val flat = CircadianAnchorEstimator(SleepConfig(anchorDecay = 1.0))
        val nights = nights(0L..0L, "19:30", "03:30") + nights(1L..1L, "20:30", "04:30") +
            nights(2L..2L, "19:30", "03:30") + nights(3L..3L, "20:30", "04:30")

        val anchor = flat.estimate(nights, asOf, need, fallback)

        assertThat(circularDistance(anchor.midpointMinuteOfDay, 0.0)).isLessThan(1e-6)
        assertThat(anchor.midpointMinuteOfDay).isAtLeast(0.0)
        assertThat(anchor.midpointMinuteOfDay).isLessThan(1440.0)
        assertThat(anchor.typicalWake).isEqualTo(LocalTime.of(4, 0))
    }

    @Test
    fun `midpoints on both sides of midnight with default weights stay near midnight`() {
        val nights = nights(0L..0L, "19:30", "03:30") + nights(1L..1L, "20:30", "04:30") + nights(2L..2L, "19:30", "03:30")

        val anchor = estimator.estimate(nights, asOf, need, fallback)

        assertThat(circularDistance(anchor.midpointMinuteOfDay, 0.0)).isLessThan(30.0)
    }

    @Test
    fun `recent nights pull the anchor toward them`() {
        // Three most recent nights have midpoint 04:00, four older ones 02:00.
        val nights = nights(0L..2L, "00:00", "08:00") + nights(3L..6L, "22:00", "06:00")
        val midpoints = listOf(240.0, 240.0, 240.0, 120.0, 120.0, 120.0, 120.0)

        val anchor = estimator.estimate(nights.shuffled(java.util.Random(7)), asOf, need, fallback)

        assertThat(anchor.midpointMinuteOfDay).isWithin(1e-6).of(weightedCircularMean(midpoints, 0.85))
        assertThat(anchor.midpointMinuteOfDay).isGreaterThan(weightedCircularMean(midpoints, 1.0) + 10)
    }

    @Test
    fun `fewer than three nights falls back to the onboarding wake time`() {
        val anchor = estimator.estimate(nights(0L..1L, "23:00", "07:00"), asOf, need, LocalTime.of(6, 45))

        assertThat(anchor.isFallback).isTrue()
        assertThat(anchor.typicalWake).isEqualTo(LocalTime.of(6, 45))
        assertThat(anchor.midpointMinuteOfDay).isWithin(1e-9).of(165.0)
        assertThat(anchor.nightsUsed).isEqualTo(2)
    }

    @Test
    fun `fallback anchor wraps before midnight`() {
        val anchor = estimator.estimate(emptyList(), asOf, Duration.ofHours(9), LocalTime.of(2, 0))

        assertThat(anchor.isFallback).isTrue()
        assertThat(anchor.midpointMinuteOfDay).isWithin(1e-9).of(1290.0) // 21:30
        assertThat(anchor.typicalWake).isEqualTo(LocalTime.of(2, 0))
    }

    @Test
    fun `nights older than 21 days and future nights are ignored`() {
        val recent = nights(0L..2L, "23:00", "07:00")
        val old = nights(21L..30L, "02:00", "10:00")
        val future = listOf(Fx.night(asOf.plusDays(1), "02:00", "10:00"))

        val anchor = estimator.estimate(recent + old + future, asOf, need, fallback)

        assertThat(anchor.nightsUsed).isEqualTo(3)
        assertThat(anchor.midpointMinuteOfDay).isWithin(1e-6).of(180.0)
    }

    @Test
    fun `lookback boundary includes 20 days ago and excludes 21`() {
        val inside = estimator.estimate(nights(18L..20L, "23:00", "07:00"), asOf, need, fallback)
        val straddling = estimator.estimate(nights(19L..21L, "23:00", "07:00"), asOf, need, fallback)

        assertThat(inside.isFallback).isFalse()
        assertThat(straddling.isFallback).isTrue()
        assertThat(straddling.nightsUsed).isEqualTo(2)
    }

    @Test
    fun `at most 14 nights are used`() {
        val anchor = estimator.estimate(nights(0L..19L, "23:00", "07:00"), asOf, need, fallback)

        assertThat(anchor.nightsUsed).isEqualTo(14)
    }

    @Test
    fun `edited sleep times are used instead of the raw block`() {
        val edited = nights(0L..2L, "23:00", "07:00").map { night ->
            val fellAsleep = LocalDateTime.of(night.wakeDate, LocalTime.of(1, 0)).toInstant(Fx.UTC)
            night.copy(
                fellAsleep = fellAsleep,
                override = NightOverride(night.wakeDate, fellAsleep = fellAsleep, offset = Fx.UTC, updatedAt = Instant.EPOCH),
            )
        }

        val anchor = estimator.estimate(edited, asOf, need, fallback)

        assertThat(anchor.midpointMinuteOfDay).isWithin(1e-6).of(240.0)
        assertThat(anchor.typicalWake).isEqualTo(LocalTime.of(8, 0))
    }

    @Test
    fun `manual nights count and nap-only days do not`() {
        val manual = nights(0L..1L, "23:00", "07:00").map { night ->
            night.copy(
                main = null,
                override = NightOverride(
                    night.wakeDate, night.fellAsleep, night.wokeUp, isManualNight = true, offset = Fx.UTC, updatedAt = Instant.EPOCH,
                ),
            )
        }
        val napOnly = nights(2L..4L, "23:00", "07:00").map { it.copy(main = null) }

        val withManual = estimator.estimate(manual + nights(2L..2L, "23:00", "07:00"), asOf, need, fallback)
        val withNapOnly = estimator.estimate(napOnly, asOf, need, fallback)

        assertThat(withManual.isFallback).isFalse()
        assertThat(withManual.nightsUsed).isEqualTo(3)
        assertThat(withNapOnly.isFallback).isTrue()
        assertThat(withNapOnly.nightsUsed).isEqualTo(0)
    }

    @Test
    fun `local midpoints use each night's recorded offset`() {
        val newYork = ZoneOffset.ofHours(-5)
        val anchor = estimator.estimate(nights(0L..6L, "23:00", "07:00", offset = newYork), asOf, need, fallback)

        assertThat(anchor.midpointMinuteOfDay).isWithin(1e-6).of(180.0)
        assertThat(anchor.typicalWake).isEqualTo(LocalTime.of(7, 0))
    }

    private fun circularDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 1440.0
        return min(d, 1440.0 - d)
    }

    private fun weightedCircularMean(minutesMostRecentFirst: List<Double>, decay: Double): Double {
        var s = 0.0
        var c = 0.0
        minutesMostRecentFirst.forEachIndexed { i, m ->
            val w = Math.pow(decay, i.toDouble())
            s += w * sin(2 * PI * m / 1440)
            c += w * cos(2 * PI * m / 1440)
        }
        return (atan2(s, c) * 1440 / (2 * PI) + 1440) % 1440
    }
}
