package com.sleepsaver.core.domain.energy

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.circadian.isWeekend
import com.sleepsaver.core.domain.model.BedtimeMode
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.domain.model.SleepTiming
import com.sleepsaver.core.domain.model.TimingSource
import com.sleepsaver.core.domain.model.WakeTimeMode
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.EveningPlan
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.OverrideKind
import com.sleepsaver.core.domain.schedule.PlanPause
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.domain.testing.Fx
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.Test

/** The week plan in the energy schedule (PLAN §3.11): per-day W and B, one-offs, wind-down length, Night steps switch. */
class EnergySchedulePlanTest {

    private val calculator = EnergyScheduleCalculator()
    private val need = Duration.ofHours(8)

    /** Monday 2026-10-05. */
    private val monday = LocalDate.of(2026, 10, 5)
    private val tuesday = monday.plusDays(1)
    private val wednesday = monday.plusDays(2)
    private val thursday = monday.plusDays(3)
    private val saturday = monday.plusDays(5)

    /** The user's own times: wake 07:00, bed 23:00. */
    private val fixed = SleepTiming(WakeTimeMode.FIXED, LocalTime.of(7, 0), BedtimeMode.FIXED, LocalTime.of(23, 0))

    private val workWeek = Fx.weekPlan(
        MONDAY to LocalTime.of(6, 0), TUESDAY to LocalTime.of(6, 30), WEDNESDAY to LocalTime.of(6, 0),
        THURSDAY to LocalTime.of(6, 0), FRIDAY to LocalTime.of(6, 0),
    )

    private fun at(date: LocalDate, time: String, zone: ZoneId = Fx.UTC): Instant = Fx.instant(date, time, zone)

    private fun WeekPlan.evening(day: DayOfWeek, change: (EveningPlan) -> EveningPlan): WeekPlan =
        with(day(day).let { it.copy(evening = change(it.evening)) })

    private fun schedule(
        date: LocalDate,
        timing: SleepTiming,
        nights: List<Night> = emptyList(),
        zone: ZoneId = Fx.UTC,
    ): EnergySchedule = calculator.schedule(date, zone, nights, need, Duration.ZERO, timing)

    @Test
    fun `each day uses its own wake time and bedtime`() {
        val timing = fixed.copy(week = workWeek.evening(MONDAY) { it.copy(bedtime = LocalTime.of(22, 0)) })

        val mon = schedule(monday, timing)
        val wed = schedule(wednesday, timing.copy(week = timing.week.with(WeekPlan.defaultDay(WEDNESDAY))))

        assertThat(mon.typicalWake.toInstant()).isEqualTo(at(monday, "06:00"))
        assertThat(mon.anchor.wakeSource).isEqualTo(TimingSource.WEEK_PLAN)
        assertThat(mon.targetBedtime).isEqualTo(at(monday, "22:00"))
        assertThat(mon.anchor.bedtimeSource).isEqualTo(TimingSource.WEEK_PLAN)
        assertThat(mon.tomorrowWakeZone.start).isEqualTo(at(tuesday, "06:00")) // Tuesday 06:30 − 30 min
        assertThat(mon.anchor.isFallback).isFalse()
        assertThat(schedule(tuesday, timing).targetBedtime).isEqualTo(at(tuesday, "23:00"))
        assertThat(wed.typicalWake.toInstant()).isEqualTo(at(wednesday, "07:00"))
        assertThat(wed.anchor.wakeSource).isEqualTo(TimingSource.FIXED)
    }

    @Test
    fun `just this once sets the wake time of a day without an alarm`() {
        val oneOffs = OneOffs(listOf(DateOverride(saturday, OverrideKind.JUST_ONCE, LocalTime.of(5, 30), AlarmMode.FIXED)))

        val s = schedule(saturday, fixed.copy(week = workWeek, oneOffs = oneOffs))

        assertThat(s.typicalWake.toInstant()).isEqualTo(at(saturday, "05:30"))
        assertThat(s.anchor.wakeSource).isEqualTo(TimingSource.WEEK_PLAN)
        assertThat(schedule(saturday, fixed.copy(week = workWeek)).typicalWake.toInstant()).isEqualTo(at(saturday, "07:00"))
    }

    @Test
    fun `a day off and a pause sleep to the usual time`() {
        val dayOff = OneOffs(listOf(DateOverride(wednesday, OverrideKind.DAY_OFF)))
        val pause = OneOffs(pause = PlanPause(thursday, thursday.plusDays(1)))

        val off = schedule(wednesday, fixed.copy(week = workWeek, oneOffs = dayOff))
        val paused = schedule(thursday, fixed.copy(week = workWeek, oneOffs = pause))

        assertThat(off.typicalWake.toInstant()).isEqualTo(at(wednesday, "07:00"))
        assertThat(off.anchor.wakeSource).isEqualTo(TimingSource.FIXED)
        assertThat(paused.typicalWake.toInstant()).isEqualTo(at(thursday, "07:00"))
    }

    @Test
    fun `just this once inside a pause wins`() {
        val oneOffs = OneOffs(
            overrides = listOf(DateOverride(thursday, OverrideKind.JUST_ONCE, LocalTime.of(5, 45), AlarmMode.SURFACING)),
            pause = PlanPause(monday, saturday),
        )

        val s = schedule(thursday, fixed.copy(week = workWeek, oneOffs = oneOffs))

        assertThat(s.typicalWake.toInstant()).isEqualTo(at(thursday, "05:45"))
    }

    @Test
    fun `the wind-down lasts the evening's length, clamped to 30 to 150 minutes`() {
        val expected = mapOf(30L to 30L, 90L to 90L, 150L to 150L, 15L to 30L, 200L to 150L)
        for ((length, shown) in expected) {
            val timing = fixed.copy(week = WeekPlan.DEFAULT.evening(MONDAY) { it.copy(windDownLength = Duration.ofMinutes(length)) })

            val s = schedule(monday, timing)
            val windDown = s.phase(PhaseType.WIND_DOWN)!!
            val melatonin = s.phase(PhaseType.MELATONIN_WINDOW)!!

            assertThat(windDown.end).isEqualTo(melatonin.start)
            assertThat(windDown.end).isEqualTo(at(monday, "22:30"))
            assertThat(Duration.between(windDown.start, windDown.end)).isEqualTo(Duration.ofMinutes(shown))
        }
    }

    @Test
    fun `tomorrow's set wake time feeds a sleep-need bedtime`() {
        val timing = fixed.copy(bedtimeMode = BedtimeMode.SLEEP_NEED, week = Fx.weekPlan(TUESDAY to LocalTime.of(5, 30)))

        val s = schedule(monday, timing)

        // Tuesday 05:30 − 8 h − 15 min.
        assertThat(s.targetBedtime).isEqualTo(at(monday, "21:15"))
        assertThat(s.anchor.bedtimeSource).isEqualTo(TimingSource.SLEEP_NEED)
        assertThat(s.tomorrowWakeZone.start).isEqualTo(at(tuesday, "05:00"))
        assertThat(schedule(monday, timing.copy(week = WeekPlan.DEFAULT)).targetBedtime).isEqualTo(at(monday, "22:45"))
    }

    @Test
    fun `the personal latency moves learned and sleep-need bedtimes`() {
        val nights = (1L..21L).map { back ->
            val wakeDate = monday.minusDays(back)
            if (wakeDate.isWeekend()) Fx.night(wakeDate, "00:30", "08:40") else Fx.night(wakeDate, "23:00", "06:50")
        }
        val learned = SleepTiming(latency = Duration.ofMinutes(30))

        assertThat(schedule(monday, SleepTiming(), nights).targetBedtime).isEqualTo(at(monday, "22:45"))
        assertThat(schedule(monday, learned, nights).targetBedtime).isEqualTo(at(monday, "22:30"))
        // Learned Tuesday wake 06:50 − 8 h − 30 min.
        assertThat(schedule(monday, learned.copy(bedtimeMode = BedtimeMode.SLEEP_NEED), nights).targetBedtime)
            .isEqualTo(at(monday, "22:20"))
    }

    @Test
    fun `a wake time in the spring-forward gap moves forward`() {
        val sunday = Fx.SPRING_FORWARD
        val timing = fixed.copy(
            week = Fx.weekPlan(SUNDAY to LocalTime.of(2, 30)).evening(SATURDAY) { it.copy(bedtime = LocalTime.of(2, 30)) },
        )

        val s = schedule(sunday, timing, zone = Fx.NEW_YORK)
        val saturdayEvening = schedule(sunday.minusDays(1), timing, zone = Fx.NEW_YORK)

        // 02:30 doesn't exist on 2026-03-08 in New York: 03:30 EDT.
        assertThat(s.typicalWake.toInstant()).isEqualTo(Instant.parse("2026-03-08T07:30:00Z"))
        assertThat(s.typicalWake.toLocalTime()).isEqualTo(LocalTime.of(3, 30))
        assertThat(s.typicalWake.offset).isEqualTo(ZoneOffset.ofHours(-4))
        assertThat(saturdayEvening.targetBedtime).isEqualTo(Instant.parse("2026-03-08T07:30:00Z"))
    }

    @Test
    fun `a wake time in the fall-back overlap takes the earlier offset`() {
        val sunday = Fx.FALL_BACK
        val timing = fixed.copy(
            week = Fx.weekPlan(SUNDAY to LocalTime.of(1, 30)).evening(SATURDAY) { it.copy(bedtime = LocalTime.of(1, 30)) },
        )

        val s = schedule(sunday, timing, zone = Fx.NEW_YORK)
        val saturdayEvening = schedule(sunday.minusDays(1), timing, zone = Fx.NEW_YORK)

        // The first 01:30 of 2026-11-01 (EDT, −04:00).
        assertThat(s.typicalWake.offset).isEqualTo(ZoneOffset.ofHours(-4))
        assertThat(s.typicalWake.toInstant()).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"))
        assertThat(saturdayEvening.targetBedtime).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"))
    }

    @Test
    fun `a missing night is assumed to follow that day's own plan`() {
        // Monday wakes at 05:00 by plan; everything else at 07:00. Tuesday's schedule has no nights at all.
        val timing = fixed.copy(week = Fx.weekPlan(MONDAY to LocalTime.of(5, 0)))
        val mondayNight = Fx.night(monday, "21:00", "05:00") // exactly [W − need, W] for Monday

        val nominal = schedule(tuesday, timing)
        val recorded = schedule(tuesday, timing, listOf(mondayNight))
        val usualMonday = schedule(tuesday, fixed)

        assertThat(nominal.curve).isEqualTo(recorded.curve)
        assertThat(nominal.phases).isEqualTo(recorded.phases)
        assertThat(nominal.curve).isNotEqualTo(usualMonday.curve)
    }

    @Test
    fun `the evening's Night steps switch reaches the schedule`() {
        val timing = fixed.copy(week = WeekPlan.DEFAULT.evening(MONDAY) { it.copy(nightStepsOn = false) })

        assertThat(schedule(monday, timing).nightStepsOn).isFalse()
        assertThat(schedule(tuesday, timing).nightStepsOn).isTrue()
        assertThat(schedule(monday, fixed).nightStepsOn).isTrue()
    }

    @Test
    fun `the default plan keeps the old schedule`() {
        val nights = Fx.regularNights(7, monday)

        val s = schedule(monday, SleepTiming(), nights)

        assertThat(s.typicalWake.toInstant()).isEqualTo(at(monday, "07:00"))
        assertThat(s.targetBedtime).isEqualTo(at(monday, "22:45"))
        val windDown = s.phase(PhaseType.WIND_DOWN)!!
        assertThat(Duration.between(windDown.start, windDown.end)).isEqualTo(Duration.ofMinutes(60))
    }
}
