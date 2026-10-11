package com.sleepsaver.core.domain.alarm

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.AlarmSettings
import com.sleepsaver.core.domain.testing.Fx
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import org.junit.jupiter.api.Test

class SurfacingWindowTest {

    private val date = LocalDate.of(2026, 10, 8)
    private val target = Fx.instant(date, "06:30")

    private fun occurrence(mode: AlarmMode = AlarmMode.SURFACING, window: Long = 30) = AlarmOccurrence(
        date = date,
        localTime = LocalTime.of(6, 30),
        target = target,
        offset = Fx.UTC,
        settings = AlarmSettings(mode = mode, window = Duration.ofMinutes(window)),
        source = AlarmSource.DAY_PLAN,
    )

    @Test
    fun `an ordinary night uses the base window`() {
        val window = SurfacingWindows.effective(occurrence(), Duration.ofHours(8), DebtStatus.LOW)

        assertThat(window.start).isEqualTo(Fx.instant(date, "06:00"))
        assertThat(window.target).isEqualTo(target)
        assertThat(window.length).isEqualTo(Duration.ofMinutes(30))
        assertThat(window.debtAware).isFalse()
    }

    @Test
    fun `a short planned night widens the window by 10 minutes`() {
        val window = SurfacingWindows.effective(occurrence(), Duration.ofHours(6), null)

        assertThat(window.debtAware).isTrue()
        assertThat(window.start).isEqualTo(Fx.instant(date, "05:50"))
    }

    @Test
    fun `exactly six and a half hours is not short`() {
        assertThat(SurfacingWindows.effective(occurrence(), Duration.ofMinutes(390), null).debtAware).isFalse()
        assertThat(SurfacingWindows.effective(occurrence(), Duration.ofMinutes(389), null).debtAware).isTrue()
    }

    @Test
    fun `moderate or high debt makes the night debt-aware`() {
        assertThat(SurfacingWindows.effective(occurrence(), null, DebtStatus.MODERATE).debtAware).isTrue()
        assertThat(SurfacingWindows.effective(occurrence(), null, DebtStatus.HIGH).length).isEqualTo(Duration.ofMinutes(40))
        assertThat(SurfacingWindows.effective(occurrence(), null, DebtStatus.LOW).debtAware).isFalse()
        assertThat(SurfacingWindows.effective(occurrence(), null, null).debtAware).isFalse()
    }

    @Test
    fun `widening never passes 45 minutes`() {
        val window = SurfacingWindows.effective(occurrence(window = 40), null, DebtStatus.HIGH)

        assertThat(window.length).isEqualTo(Duration.ofMinutes(45))
        assertThat(window.start).isEqualTo(Fx.instant(date, "05:45"))
    }

    @Test
    fun `a fixed alarm has no window but still reports debt awareness`() {
        val window = SurfacingWindows.effective(occurrence(mode = AlarmMode.FIXED), Duration.ofHours(5), null)

        assertThat(window.start).isEqualTo(target)
        assertThat(window.length).isEqualTo(Duration.ZERO)
        assertThat(window.debtAware).isTrue()
        assertThat(occurrence(mode = AlarmMode.FIXED).window).isEqualTo(Duration.ZERO)
    }
}
