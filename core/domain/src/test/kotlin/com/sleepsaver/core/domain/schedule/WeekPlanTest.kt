package com.sleepsaver.core.domain.schedule

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.config.AlarmConfig
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.testing.Fx
import java.time.DayOfWeek
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WeekPlanTest {

    /** Monday 2026-10-05. */
    private val monday = LocalDate.of(2026, 10, 5)
    private val saturday = monday.plusDays(5)
    private val sixThirty = LocalTime.of(6, 30)

    /** Mon–Fri 06:30 SURFACING. */
    private val workWeek = Fx.weekPlan(
        MONDAY to sixThirty, TUESDAY to sixThirty, WEDNESDAY to sixThirty, THURSDAY to sixThirty, DayOfWeek.FRIDAY to sixThirty,
    )

    private fun justOnce(date: LocalDate, time: LocalTime, mode: AlarmMode) = DateOverride(date, OverrideKind.JUST_ONCE, time, mode)

    @Nested
    inner class Defaults {

        @Test
        fun `the default week has every alarm off, calm weekends and standard weekdays`() {
            val plan = WeekPlan.DEFAULT

            assertThat(plan.days.map { it.day }).containsExactlyElementsIn(DayOfWeek.values()).inOrder()
            assertThat(plan.days.map { it.morning.alarm.mode }.toSet()).containsExactly(AlarmMode.OFF)
            assertThat(plan.days.mapNotNull { it.morning.wakeTime }).isEmpty()
            assertThat(plan.day(MONDAY).morning.alarm.style).isEqualTo(WakeStyle.STANDARD)
            assertThat(plan.day(SATURDAY).morning.alarm.style).isEqualTo(WakeStyle.CALM)
            assertThat(plan.day(SUNDAY).morning.alarm.style).isEqualTo(WakeStyle.CALM)
            assertThat(plan.day(MONDAY).evening).isEqualTo(EveningPlan())
            assertThat(plan.day(MONDAY).evening.windDownLength).isEqualTo(Duration.ofMinutes(60))
            assertThat(plan.hasAlarms).isFalse()
        }

        @Test
        fun `a default day takes its defaults from the config`() {
            val config = SleepConfig(
                windDownLength = Duration.ofMinutes(90),
                alarm = AlarmConfig(defaultWindow = Duration.ofMinutes(20), defaultWeekendStyle = WakeStyle.QUIET_BED),
            )

            val day = WeekPlan.defaultDay(SUNDAY, config)

            assertThat(day.morning.alarm.window).isEqualTo(Duration.ofMinutes(20))
            assertThat(day.morning.alarm.style).isEqualTo(WakeStyle.QUIET_BED)
            assertThat(day.evening.windDownLength).isEqualTo(Duration.ofMinutes(90))
        }

        @Test
        fun `a week plan needs all seven days in order`() {
            assertThrows<IllegalArgumentException> { WeekPlan(WeekPlan.DEFAULT.days.drop(1)) }
            assertThrows<IllegalArgumentException> { WeekPlan(WeekPlan.DEFAULT.days.asReversed()) }
        }

        @Test
        fun `an alarm needs a mode and a set time`() {
            assertThat(workWeek.hasAlarms).isTrue()
            assertThat(Fx.weekPlan(MONDAY to sixThirty, mode = AlarmMode.OFF).hasAlarms).isFalse()
            val modeWithoutTime = WeekPlan.DEFAULT.day(MONDAY).let {
                it.copy(morning = it.morning.copy(alarm = it.morning.alarm.copy(mode = AlarmMode.FIXED)))
            }
            assertThat(WeekPlan.DEFAULT.with(modeWithoutTime).hasAlarms).isFalse()
        }

        @Test
        fun `with replaces only its own day`() {
            val tuesday = WeekPlan.DEFAULT.day(TUESDAY).copy(evening = EveningPlan(bedtime = LocalTime.of(22, 0)))

            val plan = WeekPlan.DEFAULT.with(tuesday)

            assertThat(plan.day(TUESDAY)).isEqualTo(tuesday)
            assertThat(plan.days.filter { it.day != TUESDAY }).isEqualTo(WeekPlan.DEFAULT.days.filter { it.day != TUESDAY })
        }
    }

    @Nested
    inner class Resolve {

        @Test
        fun `a planned day rings at its set time`() {
            val day = workWeek.resolve(monday)

            assertThat(day.alarmMode).isEqualTo(AlarmMode.SURFACING)
            assertThat(day.wakeTime).isEqualTo(sixThirty)
            assertThat(day.wakeSource).isEqualTo(WakeSource.SET_TIME)
            assertThat(day.alarmOn).isTrue()
            assertThat(day.paused).isFalse()
            assertThat(day.override).isNull()
        }

        @Test
        fun `a day without a set time wakes at the usual time`() {
            val day = workWeek.resolve(saturday)

            assertThat(day.alarmMode).isEqualTo(AlarmMode.OFF)
            assertThat(day.wakeTime).isNull()
            assertThat(day.wakeSource).isEqualTo(WakeSource.USUAL)
            assertThat(day.alarmOn).isFalse()
        }

        @Test
        fun `just this once adds an alarm on a day that has none`() {
            val day = workWeek.resolve(saturday, OneOffs(listOf(justOnce(saturday, LocalTime.of(8, 0), AlarmMode.FIXED))))

            assertThat(day.isJustOnce).isTrue()
            assertThat(day.alarmMode).isEqualTo(AlarmMode.FIXED)
            assertThat(day.wakeTime).isEqualTo(LocalTime.of(8, 0))
            assertThat(day.wakeSource).isEqualTo(WakeSource.JUST_ONCE)
            assertThat(day.alarmOn).isTrue()
            // The rest of the alarm comes from that day's plan.
            assertThat(day.alarm).isEqualTo(workWeek.day(SATURDAY).morning.alarm.copy(mode = AlarmMode.FIXED))
        }

        @Test
        fun `just this once with the alarm off only moves the wake time`() {
            val day = workWeek.resolve(monday, OneOffs(listOf(justOnce(monday, LocalTime.of(9, 0), AlarmMode.OFF))))

            assertThat(day.wakeTime).isEqualTo(LocalTime.of(9, 0))
            assertThat(day.wakeSource).isEqualTo(WakeSource.JUST_ONCE)
            assertThat(day.alarmOn).isFalse()
        }

        @Test
        fun `a day off skips the alarm and sleeps to the usual time`() {
            val day = workWeek.resolve(monday, OneOffs(listOf(DateOverride(monday, OverrideKind.DAY_OFF))))

            assertThat(day.isDayOff).isTrue()
            assertThat(day.alarmMode).isEqualTo(AlarmMode.OFF)
            assertThat(day.alarm.mode).isEqualTo(AlarmMode.OFF)
            assertThat(day.wakeTime).isNull()
            assertThat(day.wakeSource).isEqualTo(WakeSource.USUAL)
            assertThat(day.alarmOn).isFalse()
        }

        @Test
        fun `a pause switches alarms off on the dates it covers`() {
            val oneOffs = OneOffs(pause = PlanPause(monday, monday.plusDays(1)))

            assertThat(workWeek.resolve(monday, oneOffs).paused).isTrue()
            assertThat(workWeek.resolve(monday, oneOffs).alarmMode).isEqualTo(AlarmMode.OFF)
            assertThat(workWeek.resolve(monday, oneOffs).wakeTime).isNull()
            assertThat(workWeek.resolve(monday.plusDays(1), oneOffs).alarmOn).isFalse()
            assertThat(workWeek.resolve(monday.plusDays(2), oneOffs).alarmOn).isTrue()
        }

        @Test
        fun `just this once inside a pause wins`() {
            val oneOffs = OneOffs(
                overrides = listOf(justOnce(monday, LocalTime.of(5, 45), AlarmMode.SURFACING)),
                pause = PlanPause(monday.minusDays(3), monday.plusDays(3)),
            )

            val day = workWeek.resolve(monday, oneOffs)

            assertThat(day.paused).isTrue()
            assertThat(day.alarmMode).isEqualTo(AlarmMode.SURFACING)
            assertThat(day.wakeTime).isEqualTo(LocalTime.of(5, 45))
            assertThat(day.alarmOn).isTrue()
        }

        @Test
        fun `a day off inside a pause stays off`() {
            val oneOffs = OneOffs(listOf(DateOverride(monday, OverrideKind.DAY_OFF)), PlanPause(monday, monday))

            assertThat(workWeek.resolve(monday, oneOffs).alarmOn).isFalse()
        }

        @Test
        fun `the last override for a date wins and other dates are untouched`() {
            val oneOffs = OneOffs(
                listOf(DateOverride(monday, OverrideKind.DAY_OFF), justOnce(monday, LocalTime.of(7, 15), AlarmMode.FIXED)),
            )

            assertThat(workWeek.resolve(monday, oneOffs).wakeTime).isEqualTo(LocalTime.of(7, 15))
            assertThat(workWeek.resolve(monday.plusDays(7), oneOffs).wakeTime).isEqualTo(sixThirty)
            assertThat(workWeek.resolve(monday.plusDays(7), oneOffs).override).isNull()
        }

        @Test
        fun `one-offs never change the evening`() {
            val evening = EveningPlan(bedtime = LocalTime.of(22, 30), nightStepsOn = false, routine = listOf(3L))
            val plan = workWeek.with(workWeek.day(MONDAY).copy(evening = evening))
            val oneOffs = OneOffs(listOf(DateOverride(monday, OverrideKind.DAY_OFF)), PlanPause(monday, monday))

            assertThat(plan.resolve(monday, oneOffs).evening).isEqualTo(evening)
        }

        @Test
        fun `just this once needs a time and a mode`() {
            assertThrows<IllegalArgumentException> { DateOverride(monday, OverrideKind.JUST_ONCE, time = sixThirty) }
            assertThrows<IllegalArgumentException> { DateOverride(monday, OverrideKind.JUST_ONCE, mode = AlarmMode.FIXED) }
            assertThat(DateOverride(monday, OverrideKind.DAY_OFF).time).isNull()
        }

        @Test
        fun `a pause covers both ends and can't end before it starts`() {
            val pause = PlanPause(monday, monday.plusDays(2))

            assertThat(pause.covers(monday.minusDays(1))).isFalse()
            assertThat(pause.covers(monday)).isTrue()
            assertThat(pause.covers(monday.plusDays(2))).isTrue()
            assertThat(pause.covers(monday.plusDays(3))).isFalse()
            assertThrows<IllegalArgumentException> { PlanPause(monday, monday.minusDays(1)) }
        }
    }

    @Nested
    inner class CopyParts {

        private val source = DayPlan(
            day = MONDAY,
            morning = MorningPlan(LocalTime.of(5, 50), AlarmSettings(mode = AlarmMode.FIXED, style = WakeStyle.FIRM)),
            evening = EveningPlan(bedtime = LocalTime.of(22, 15), nightStepsOn = false, windDownLength = Duration.ofMinutes(90), routine = listOf(4L, 2L)),
        )
        private val plan = WeekPlan.DEFAULT.with(source)

        @Test
        fun `morning copies only the morning`() {
            val copied = plan.copyParts(MONDAY, setOf(TUESDAY, WEDNESDAY), setOf(DayPart.MORNING))

            for (day in listOf(TUESDAY, WEDNESDAY)) {
                assertThat(copied.day(day).morning).isEqualTo(source.morning)
                assertThat(copied.day(day).evening).isEqualTo(WeekPlan.DEFAULT.day(day).evening)
            }
            assertThat(copied.day(THURSDAY)).isEqualTo(WeekPlan.DEFAULT.day(THURSDAY))
        }

        @Test
        fun `evening copies bedtime, switch, length and routine`() {
            val copied = plan.copyParts(MONDAY, setOf(SUNDAY), setOf(DayPart.EVENING))

            assertThat(copied.day(SUNDAY).evening).isEqualTo(source.evening)
            assertThat(copied.day(SUNDAY).morning).isEqualTo(WeekPlan.DEFAULT.day(SUNDAY).morning)
        }

        @Test
        fun `both parts copy the whole day but keep the target's weekday`() {
            val copied = plan.copyParts(MONDAY, setOf(SATURDAY), setOf(DayPart.MORNING, DayPart.EVENING))

            assertThat(copied.day(SATURDAY)).isEqualTo(source.copy(day = SATURDAY))
        }

        @Test
        fun `the source day is never a target`() {
            val copied = WeekPlan.DEFAULT.with(source)
                .copyParts(TUESDAY, setOf(MONDAY, TUESDAY), setOf(DayPart.MORNING, DayPart.EVENING))

            assertThat(copied.day(MONDAY)).isEqualTo(WeekPlan.DEFAULT.day(MONDAY))
            assertThat(copied.day(TUESDAY)).isEqualTo(WeekPlan.DEFAULT.day(TUESDAY))
            assertThat(plan.copyParts(MONDAY, setOf(MONDAY), setOf(DayPart.MORNING))).isEqualTo(plan)
        }

        @Test
        fun `no parts change nothing`() {
            assertThat(plan.copyParts(MONDAY, DayOfWeek.values().toSet(), emptySet())).isEqualTo(plan)
        }
    }
}
