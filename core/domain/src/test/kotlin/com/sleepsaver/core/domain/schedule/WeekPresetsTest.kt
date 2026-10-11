package com.sleepsaver.core.domain.schedule

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.config.AlarmConfig
import com.sleepsaver.core.domain.config.SleepConfig
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Duration
import java.time.LocalTime
import org.junit.jupiter.api.Test

class WeekPresetsTest {

    private val weekdays = listOf(MONDAY, TUESDAY, WEDNESDAY, DayOfWeek.THURSDAY, FRIDAY)

    /** Tuesday already rings FIXED with FIRM; Wednesday is off but CALM; Sunday has a set time and an evening. */
    private val start: WeekPlan = WeekPlan.DEFAULT
        .with(DayPlan(TUESDAY, MorningPlan(LocalTime.of(5, 0), AlarmSettings(mode = AlarmMode.FIXED, style = WakeStyle.FIRM))))
        .with(DayPlan(WEDNESDAY, MorningPlan(alarm = AlarmSettings(style = WakeStyle.CALM))))
        .with(
            DayPlan(
                SUNDAY,
                MorningPlan(LocalTime.of(9, 0), AlarmSettings(mode = AlarmMode.SURFACING, style = WakeStyle.CALM)),
                EveningPlan(bedtime = LocalTime.of(22, 0), routine = listOf(1L)),
            ),
        )

    @Test
    fun `work week sets weekday alarms and frees the weekend`() {
        val plan = start.withPreset(WeekPreset.WORK_WEEK, LocalTime.of(6, 52))

        for (day in weekdays) assertThat(plan.day(day).morning.wakeTime).isEqualTo(LocalTime.of(6, 50))
        assertThat(plan.day(MONDAY).morning.alarm.mode).isEqualTo(AlarmMode.SURFACING)
        assertThat(plan.day(MONDAY).morning.alarm.style).isEqualTo(WakeStyle.STANDARD)
        for (day in listOf(SATURDAY, SUNDAY)) {
            assertThat(plan.day(day).morning.wakeTime).isNull()
            assertThat(plan.day(day).morning.alarm.mode).isEqualTo(AlarmMode.OFF)
        }
        assertThat(plan.hasAlarms).isTrue()
    }

    @Test
    fun `work week keeps an existing alarm's mode and style and resets the style of an alarm that was off`() {
        val plan = start.withPreset(WeekPreset.WORK_WEEK, LocalTime.of(7, 0))

        assertThat(plan.day(TUESDAY).morning.alarm.mode).isEqualTo(AlarmMode.FIXED)
        assertThat(plan.day(TUESDAY).morning.alarm.style).isEqualTo(WakeStyle.FIRM)
        assertThat(plan.day(WEDNESDAY).morning.alarm.mode).isEqualTo(AlarmMode.SURFACING)
        assertThat(plan.day(WEDNESDAY).morning.alarm.style).isEqualTo(WakeStyle.STANDARD)
    }

    @Test
    fun `same every day sets all seven mornings`() {
        val plan = start.withPreset(WeekPreset.SAME_EVERY_DAY, LocalTime.of(7, 3))

        assertThat(plan.days.map { it.morning.wakeTime }.toSet()).containsExactly(LocalTime.of(7, 5))
        assertThat(plan.days.map { it.morning.alarm.mode }.toSet()).containsExactly(AlarmMode.SURFACING, AlarmMode.FIXED)
        assertThat(plan.day(TUESDAY).morning.alarm.mode).isEqualTo(AlarmMode.FIXED)
        // Styles are left alone.
        assertThat(plan.day(SATURDAY).morning.alarm.style).isEqualTo(WakeStyle.CALM)
        assertThat(plan.day(WEDNESDAY).morning.alarm.style).isEqualTo(WakeStyle.CALM)
    }

    @Test
    fun `presets never touch evenings`() {
        for (preset in WeekPreset.values()) {
            val plan = start.withPreset(preset, LocalTime.of(6, 30))

            assertThat(plan.days.map { it.evening }).isEqualTo(start.days.map { it.evening })
        }
    }

    @Test
    fun `an alarm switched on by a preset uses the configured default mode`() {
        val config = SleepConfig(alarm = AlarmConfig(defaultMode = AlarmMode.FIXED))

        val plan = WeekPlan.DEFAULT.withPreset(WeekPreset.SAME_EVERY_DAY, LocalTime.of(6, 30), config)

        assertThat(plan.days.map { it.morning.alarm.mode }.toSet()).containsExactly(AlarmMode.FIXED)
    }

    @Test
    fun `times round to the nearest step`() {
        val five = Duration.ofMinutes(5)

        assertThat(LocalTime.of(6, 52).roundedTo(five)).isEqualTo(LocalTime.of(6, 50))
        assertThat(LocalTime.of(6, 53).roundedTo(five)).isEqualTo(LocalTime.of(6, 55))
        assertThat(LocalTime.of(6, 52, 40).roundedTo(five)).isEqualTo(LocalTime.of(6, 50))
        assertThat(LocalTime.of(23, 58).roundedTo(five)).isEqualTo(LocalTime.MIDNIGHT)
        assertThat(LocalTime.of(7, 7).roundedTo(Duration.ofMinutes(15))).isEqualTo(LocalTime.of(7, 0))
        assertThat(LocalTime.of(7, 8).roundedTo(Duration.ofMinutes(15))).isEqualTo(LocalTime.of(7, 15))
        assertThat(LocalTime.of(7, 8, 30).roundedTo(Duration.ofMinutes(1))).isEqualTo(LocalTime.of(7, 8))
    }
}
