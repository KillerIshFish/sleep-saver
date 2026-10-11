package com.sleepsaver.feature.settings

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.AlarmSettings
import com.sleepsaver.core.domain.schedule.MorningPlan
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.ui.platform.AlarmBlocker
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

/** The Week plan row's summary and the order the test ring checks the phone's alarm settings in. */
class SettingsAlarmsTest {

    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    private fun WeekPlan.morning(weekday: DayOfWeek, time: LocalTime?, mode: AlarmMode): WeekPlan =
        this.with(day(weekday).copy(morning = MorningPlan(wakeTime = time, alarm = AlarmSettings(mode = mode))))

    // ---------------------------------------------------------------- Week plan summary

    @Test
    fun `no alarm days reads as no alarms set`() {
        assertThat(weekPlanSummary(WeekPlan.DEFAULT)).isEqualTo(WeekPlanSummary.NoAlarms)
    }

    @Test
    fun `alarm days at one time are listed with that time`() {
        assertThat(weekPlanSummary(PlanSamples.weekPlan())).isEqualTo(WeekPlanSummary.OneTime(weekdays, LocalTime.of(6, 45)))
    }

    @Test
    fun `alarm days at different times read as different times`() {
        val plan = PlanSamples.weekPlan().morning(DayOfWeek.SATURDAY, LocalTime.of(8, 30), AlarmMode.FIXED)

        assertThat(weekPlanSummary(plan)).isEqualTo(WeekPlanSummary.Varied)
    }

    @Test
    fun `a set time without an alarm isn't an alarm day`() {
        val plan = WeekPlan.DEFAULT
            .morning(DayOfWeek.SUNDAY, LocalTime.of(9, 0), AlarmMode.OFF)
            .morning(DayOfWeek.MONDAY, LocalTime.of(6, 0), AlarmMode.SURFACING)

        assertThat(weekPlanSummary(plan)).isEqualTo(WeekPlanSummary.OneTime(setOf(DayOfWeek.MONDAY), LocalTime.of(6, 0)))
    }

    // ---------------------------------------------------------------- Test ring gate

    @Test
    fun `notifications are checked before the alarm category, and nothing else blocks a test ring`() {
        assertThat(armingBlocker(listOf(AlarmBlocker.RING_CHANNEL_OFF, AlarmBlocker.NOTIFICATIONS_OFF)))
            .isEqualTo(AlarmBlocker.NOTIFICATIONS_OFF)
        assertThat(armingBlocker(listOf(AlarmBlocker.EXACT_ALARMS_OFF, AlarmBlocker.RING_CHANNEL_OFF)))
            .isEqualTo(AlarmBlocker.RING_CHANNEL_OFF)
        assertThat(armingBlocker(listOf(AlarmBlocker.EXACT_ALARMS_OFF, AlarmBlocker.FULL_SCREEN_OFF, AlarmBlocker.ALARM_VOLUME_ZERO)))
            .isNull()
    }
}
