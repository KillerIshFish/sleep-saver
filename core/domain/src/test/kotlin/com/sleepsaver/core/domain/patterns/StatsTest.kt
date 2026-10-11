package com.sleepsaver.core.domain.patterns

import com.google.common.truth.Truth.assertThat
import java.time.LocalTime
import kotlin.math.abs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StatsTest {

    @Test
    fun `type 7 quantiles interpolate between neighbours`() {
        val sorted = listOf(1.0, 2.0, 3.0, 4.0)

        assertThat(quantile7(sorted, 0.0)).isEqualTo(1.0)
        assertThat(quantile7(sorted, 0.25)).isWithin(1e-12).of(1.75)
        assertThat(quantile7(sorted, 0.5)).isWithin(1e-12).of(2.5)
        assertThat(quantile7(sorted, 0.95)).isWithin(1e-12).of(3.85)
        assertThat(quantile7(sorted, 1.0)).isEqualTo(4.0)
        assertThat(quantile7(listOf(7.0), 0.3)).isEqualTo(7.0)
    }

    @Test
    fun `quantiles of nothing throw`() {
        assertThrows<IllegalArgumentException> { quantile7(emptyList(), 0.5) }
        assertThrows<IllegalArgumentException> { median(emptyList()) }
    }

    @Test
    fun `the median sorts first`() {
        assertThat(median(listOf(5.0, 1.0, 3.0))).isEqualTo(3.0)
        assertThat(median(listOf(4.0, 1.0, 3.0, 2.0))).isWithin(1e-12).of(2.5)
    }

    @Test
    fun `the circular mean averages across midnight`() {
        val acrossMidnight = circularMeanMinutes(listOf(23 * 60.0, 60.0)) // 23:00 and 01:00

        assertThat(abs(wrapMinutes(acrossMidnight))).isLessThan(1e-6)
        assertThat(circularMeanMinutes(listOf(600.0, 660.0))).isWithin(1e-9).of(630.0)
        assertThat(circularMeanMinutes(listOf(1420.0, 1430.0))).isWithin(1e-9).of(1425.0)
        assertThat(circularMeanMinutes(listOf(1300.0, 1400.0, 1350.0))).isWithin(1e-9).of(1350.0)
    }

    @Test
    fun `the circular mean is in the day's range`() {
        for (minutes in listOf(listOf(1439.0), listOf(0.0), listOf(1430.0, 20.0), listOf(700.0, 760.0))) {
            val mean = circularMeanMinutes(minutes)

            assertThat(mean).isAtLeast(0.0)
            assertThat(mean).isLessThan(1440.0)
        }
    }

    @Test
    fun `the circular SD is zero for identical times and close to the linear SD for a small spread`() {
        assertThat(circularSdMinutes(listOf(420.0, 420.0, 420.0))).isWithin(1e-3).of(0.0)
        // ±30 min around 23:30 → about 30 min.
        assertThat(circularSdMinutes(listOf(1380.0, 0.0))).isWithin(0.5).of(30.0)
        assertThat(circularSdMinutes(listOf(1380.0, 0.0))).isWithin(1e-9).of(circularSdMinutes(listOf(600.0, 660.0)))
    }

    @Test
    fun `wrapped differences fall between minus 720 exclusive and 720 inclusive`() {
        assertThat(wrapMinutes(1380.0)).isEqualTo(-60.0)
        assertThat(wrapMinutes(-1380.0)).isEqualTo(60.0)
        assertThat(wrapMinutes(720.0)).isEqualTo(720.0)
        assertThat(wrapMinutes(-720.0)).isEqualTo(720.0)
        assertThat(wrapMinutes(721.0)).isEqualTo(-719.0)
        assertThat(wrapMinutes(1440.0)).isEqualTo(0.0)
        assertThat(wrapMinutes(-30.0)).isEqualTo(-30.0)
    }

    @Test
    fun `noon-shifted minutes keep an evening and the small hours in order`() {
        assertThat(noonShiftedMinutes(LocalTime.NOON)).isEqualTo(0)
        assertThat(noonShiftedMinutes(LocalTime.of(22, 0))).isEqualTo(600)
        assertThat(noonShiftedMinutes(LocalTime.MIDNIGHT)).isEqualTo(720)
        assertThat(noonShiftedMinutes(LocalTime.of(1, 0))).isEqualTo(780)
        assertThat(noonShiftedMinutes(LocalTime.of(11, 59, 59))).isEqualTo(1439)
    }
}
