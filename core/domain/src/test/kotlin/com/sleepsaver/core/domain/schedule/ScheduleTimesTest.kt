package com.sleepsaver.core.domain.schedule

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.circadian.isWeekend
import com.sleepsaver.core.domain.model.BedtimeMode
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepTiming
import com.sleepsaver.core.domain.model.TimingSource
import com.sleepsaver.core.domain.model.WakeTimeMode
import com.sleepsaver.core.domain.testing.Fx
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.TUESDAY
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.jupiter.api.Test

class ScheduleTimesTest {

    private val times = ScheduleTimes()
    private val need = Duration.ofHours(8)

    /** Monday 2026-10-05. */
    private val monday = LocalDate.of(2026, 10, 5)
    private val tuesday = monday.plusDays(1)

    /** Three weeks of weekday mornings 23:00 → 06:50 and weekend mornings 00:30 → 08:40 before Monday. */
    private val nights: List<Night> = (1L..21L).map { back ->
        val wakeDate = monday.minusDays(back)
        if (wakeDate.isWeekend()) Fx.night(wakeDate, "00:30", "08:40") else Fx.night(wakeDate, "23:00", "06:50")
    }

    private val tuesdayAt530 = Fx.weekPlan(TUESDAY to LocalTime.of(5, 30))

    private fun at(date: LocalDate, time: String): Instant = Fx.instant(date, time)

    @Test
    fun `a set wake time wins and is reported as the week plan`() {
        val timing = SleepTiming(week = tuesdayAt530)

        assertThat(times.wake(tuesday, nights, timing)).isEqualTo(LocalTime.of(5, 30) to TimingSource.WEEK_PLAN)
        assertThat(times.wake(tuesday.plusDays(1), nights, timing)).isEqualTo(LocalTime.of(6, 50) to TimingSource.LEARNED)
    }

    @Test
    fun `just this once sets the wake time of its date`() {
        val timing = SleepTiming(oneOffs = OneOffs(listOf(DateOverride(tuesday, OverrideKind.JUST_ONCE, LocalTime.of(9, 0), AlarmMode.OFF))))

        assertThat(times.wake(tuesday, nights, timing)).isEqualTo(LocalTime.of(9, 0) to TimingSource.WEEK_PLAN)
    }

    @Test
    fun `the usual wake time ignores the plan`() {
        val timing = SleepTiming(week = tuesdayAt530)

        assertThat(times.usualWake(tuesday, nights, timing)).isEqualTo(LocalTime.of(6, 50) to TimingSource.LEARNED)
        assertThat(times.usualWake(tuesday, nights, timing.copy(wakeTimeMode = WakeTimeMode.FIXED, wakeTime = LocalTime.of(6, 0))))
            .isEqualTo(LocalTime.of(6, 0) to TimingSource.FIXED)
        assertThat(times.usualWake(tuesday, emptyList(), timing.copy(wakeTime = LocalTime.of(7, 15))))
            .isEqualTo(LocalTime.of(7, 15) to TimingSource.NOT_ENOUGH_DATA)
    }

    @Test
    fun `a day off and a pause fall back to the usual wake time`() {
        val dayOff = SleepTiming(week = tuesdayAt530, oneOffs = OneOffs(listOf(DateOverride(tuesday, OverrideKind.DAY_OFF))))
        val paused = SleepTiming(week = tuesdayAt530, oneOffs = OneOffs(pause = PlanPause(monday, tuesday)))

        assertThat(times.wake(tuesday, nights, dayOff)).isEqualTo(LocalTime.of(6, 50) to TimingSource.LEARNED)
        assertThat(times.wake(tuesday, nights, paused)).isEqualTo(LocalTime.of(6, 50) to TimingSource.LEARNED)
    }

    @Test
    fun `an evening's set bedtime wins, after midnight on the next day`() {
        fun eveningAt(time: LocalTime) = SleepTiming(
            week = WeekPlan.DEFAULT.with(WeekPlan.DEFAULT.day(MONDAY).copy(evening = EveningPlan(bedtime = time))),
        )

        assertThat(times.bedtime(monday, Fx.UTC, nights, need, eveningAt(LocalTime.of(22, 15))))
            .isEqualTo(at(monday, "22:15") to TimingSource.WEEK_PLAN)
        assertThat(times.bedtime(monday, Fx.UTC, nights, need, eveningAt(LocalTime.of(0, 30))))
            .isEqualTo(at(tuesday, "00:30") to TimingSource.WEEK_PLAN)
        // Only Monday's evening has it.
        assertThat(times.bedtime(tuesday, Fx.UTC, nights, need, eveningAt(LocalTime.of(22, 15))).second)
            .isEqualTo(TimingSource.LEARNED)
    }

    @Test
    fun `a sleep-need usual bedtime ignores tomorrow's set time while the plan-aware bedtime follows it`() {
        val plain = SleepTiming(bedtimeMode = BedtimeMode.SLEEP_NEED)
        val planned = plain.copy(week = tuesdayAt530)

        // Learned Tuesday wake 06:50 − 8 h − 15 min.
        assertThat(times.usualBedtime(monday, Fx.UTC, nights, need, plain)).isEqualTo(at(monday, "22:35") to TimingSource.SLEEP_NEED)
        assertThat(times.usualBedtime(monday, Fx.UTC, nights, need, planned)).isEqualTo(at(monday, "22:35") to TimingSource.SLEEP_NEED)
        assertThat(times.bedtime(monday, Fx.UTC, nights, need, plain)).isEqualTo(at(monday, "22:35") to TimingSource.SLEEP_NEED)
        // Tuesday 05:30 − 8 h − 15 min.
        assertThat(times.bedtime(monday, Fx.UTC, nights, need, planned)).isEqualTo(at(monday, "21:15") to TimingSource.SLEEP_NEED)
    }

    @Test
    fun `usualBedtime uses wakeNext when given`() {
        val timing = SleepTiming(bedtimeMode = BedtimeMode.SLEEP_NEED)

        assertThat(times.usualBedtime(monday, Fx.UTC, nights, need, timing, wakeNext = LocalTime.of(6, 0)).first)
            .isEqualTo(at(monday, "21:45"))
    }

    @Test
    fun `the personal latency moves learned and sleep-need bedtimes`() {
        val learned = SleepTiming(latency = Duration.ofMinutes(30))
        val sleepNeed = learned.copy(bedtimeMode = BedtimeMode.SLEEP_NEED)

        // Usually asleep at 23:00 before a weekday.
        assertThat(times.bedtime(monday, Fx.UTC, nights, need, SleepTiming()).first).isEqualTo(at(monday, "22:45"))
        assertThat(times.bedtime(monday, Fx.UTC, nights, need, learned)).isEqualTo(at(monday, "22:30") to TimingSource.LEARNED)
        assertThat(times.bedtime(monday, Fx.UTC, nights, need, sleepNeed)).isEqualTo(at(monday, "22:20") to TimingSource.SLEEP_NEED)
    }

    @Test
    fun `a fixed bedtime and the not-enough-data fallback ignore the latency`() {
        val timing = SleepTiming(bedtime = LocalTime.of(23, 30), latency = Duration.ofMinutes(30))

        assertThat(times.usualBedtime(monday, Fx.UTC, emptyList(), need, timing))
            .isEqualTo(at(monday, "23:30") to TimingSource.NOT_ENOUGH_DATA)
        assertThat(times.usualBedtime(monday, Fx.UTC, nights, need, timing.copy(bedtimeMode = BedtimeMode.FIXED)))
            .isEqualTo(at(monday, "23:30") to TimingSource.FIXED)
    }

    @Test
    fun `evening times in the spring-forward gap move forward`() {
        // 2026-03-08 02:30 doesn't exist in New York: it becomes 03:30 EDT.
        val instant = times.eveningOf(Fx.SPRING_FORWARD.minusDays(1), LocalTime.of(2, 30), Fx.NEW_YORK)

        assertThat(instant).isEqualTo(Instant.parse("2026-03-08T07:30:00Z"))
    }

    @Test
    fun `evening times in the fall-back overlap take the earlier offset`() {
        // 2026-11-01 01:30 happens twice in New York; the first (EDT, −04:00) is used.
        val instant = times.eveningOf(Fx.FALL_BACK.minusDays(1), LocalTime.of(1, 30), Fx.NEW_YORK)

        assertThat(instant).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"))
    }

    @Test
    fun `noon belongs to the evening's own date`() {
        assertThat(times.eveningOf(monday, LocalTime.NOON, Fx.UTC)).isEqualTo(at(monday, "12:00"))
        assertThat(times.eveningOf(monday, LocalTime.of(11, 59), Fx.UTC)).isEqualTo(at(tuesday, "11:59"))
    }
}
