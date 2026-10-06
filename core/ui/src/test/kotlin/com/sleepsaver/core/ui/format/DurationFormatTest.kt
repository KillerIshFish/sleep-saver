package com.sleepsaver.core.ui.format

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration

class DurationFormatTest {

    private fun min(m: Long) = Duration.ofMinutes(m)

    @Test
    fun `formatDuration drops zero parts`() {
        assertThat(formatDuration(Duration.ofHours(8))).isEqualTo("8h")
        assertThat(formatDuration(min(6 * 60 + 55))).isEqualTo("6h 55m")
        assertThat(formatDuration(min(45))).isEqualTo("45m")
        assertThat(formatDuration(Duration.ZERO)).isEqualTo("0m")
        assertThat(formatDuration(min(7 * 60 + 5))).isEqualTo("7h 5m")
        assertThat(formatDuration(min(25 * 60))).isEqualTo("25h")
    }

    @Test
    fun `formatDuration rounds to the nearest minute`() {
        assertThat(formatDuration(Duration.ofSeconds(6 * 3600 + 55 * 60 + 29))).isEqualTo("6h 55m")
        assertThat(formatDuration(Duration.ofSeconds(6 * 3600 + 55 * 60 + 30))).isEqualTo("6h 56m")
        assertThat(formatDuration(Duration.ofSeconds(59 * 60 + 45))).isEqualTo("1h")
        assertThat(formatDuration(Duration.ofSeconds(20))).isEqualTo("0m")
    }

    @Test
    fun `formatDuration uses a real minus sign`() {
        assertThat(formatDuration(min(-45))).isEqualTo("−45m")
        assertThat(formatDuration(min(-65))).isEqualTo("−1h 5m")
        assertThat(formatDuration(Duration.ofSeconds(-20))).isEqualTo("0m")
    }

    @Test
    fun `formatHours1 shows one decimal`() {
        assertThat(formatHours1(min(222))).isEqualTo("3.7")
        assertThat(formatHours1(Duration.ZERO)).isEqualTo("0.0")
        assertThat(formatHours1(Duration.ofHours(12))).isEqualTo("12.0")
        assertThat(formatHours1(min(3))).isEqualTo("0.1") // 0.05 h rounds up
        assertThat(formatHours1(Duration.ofSeconds(179))).isEqualTo("0.0")
        assertThat(formatHours1(min(-24))).isEqualTo("−0.4")
        assertThat(formatHours1(Duration.ofSeconds(-60))).isEqualTo("0.0")
    }

    @Test
    fun `formatHours1 from a double`() {
        assertThat(formatHours1(3.7)).isEqualTo("3.7")
        assertThat(formatHours1(3.66)).isEqualTo("3.7")
        assertThat(formatHours1(0.0)).isEqualTo("0.0")
        assertThat(formatHours1(-1.25)).isEqualTo("−1.3")
        assertThat(formatHours1(-0.04)).isEqualTo("0.0")
    }

    @Test
    fun `formatDebtChange signs both ways`() {
        assertThat(formatDebtChange(min(-12))).isEqualTo("−0.2 hrs")
        assertThat(formatDebtChange(min(66))).isEqualTo("+1.1 hrs")
        assertThat(formatDebtChange(Duration.ZERO)).isEqualTo("0.0 hrs")
        assertThat(formatDebtChange(min(2))).isEqualTo("0.0 hrs")
        assertThat(formatDebtChange(min(-2))).isEqualTo("0.0 hrs")
    }
}
