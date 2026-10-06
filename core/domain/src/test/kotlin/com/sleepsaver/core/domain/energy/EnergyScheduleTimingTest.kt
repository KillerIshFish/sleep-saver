package com.sleepsaver.core.domain.energy

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.circadian.isWeekend
import com.sleepsaver.core.domain.model.BedtimeMode
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.domain.model.SleepTiming
import com.sleepsaver.core.domain.model.TimingSource
import com.sleepsaver.core.domain.model.WakeTimeMode
import com.sleepsaver.core.domain.testing.Fx
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Where the schedule's wake time (W) and target bedtime (B) come from. */
class EnergyScheduleTimingTest {

    private val calculator = EnergyScheduleCalculator()
    private val need = Duration.ofHours(8)

    /** A Monday. */
    private val monday = LocalDate.of(2026, 10, 5)
    private val friday = monday.plusDays(4)
    private val saturday = monday.plusDays(5)

    /** Three weeks of weekday mornings 23:00 → 06:50 and weekend mornings 00:30 → 08:40, before [until]. */
    private fun weeks(until: LocalDate): List<Night> = (1L..21L).map { back ->
        val wakeDate = until.minusDays(back)
        if (wakeDate.isWeekend()) Fx.night(wakeDate, "00:30", "08:40") else Fx.night(wakeDate, "23:00", "06:50")
    }

    private fun at(date: LocalDate, time: String): Instant = Fx.dt(date, time).toInstant(Fx.UTC)

    @Test
    fun `by default W and B are learned from the data`() {
        val s = calculator.schedule(monday, Fx.UTC, weeks(monday), need, Duration.ZERO, SleepTiming())

        assertThat(s.typicalWake.toLocalTime()).isEqualTo(LocalTime.of(6, 50))
        // Usually asleep at 23:00 before a weekday, minus 15 min to fall asleep.
        assertThat(s.targetBedtime).isEqualTo(at(monday, "22:45"))
        assertThat(s.anchor.wakeSource).isEqualTo(TimingSource.LEARNED)
        assertThat(s.anchor.bedtimeSource).isEqualTo(TimingSource.LEARNED)
        assertThat(s.phase(PhaseType.MELATONIN_WINDOW)!!.start).isEqualTo(at(monday, "22:15"))
    }

    @Test
    fun `Friday's bedtime follows the nights before weekend mornings`() {
        val s = calculator.schedule(friday, Fx.UTC, weeks(friday), need, Duration.ZERO, SleepTiming())

        assertThat(s.typicalWake.toLocalTime()).isEqualTo(LocalTime.of(6, 50))
        assertThat(s.targetBedtime).isEqualTo(at(saturday, "00:15"))
    }

    @Test
    fun `Saturday's wake time follows weekend mornings`() {
        val s = calculator.schedule(saturday, Fx.UTC, weeks(saturday), need, Duration.ZERO, SleepTiming())

        assertThat(s.typicalWake.toLocalTime()).isEqualTo(LocalTime.of(8, 40))
        assertThat(s.wakeZone.start).isEqualTo(at(saturday, "08:10"))
    }

    @Test
    fun `sleep-need bedtime is tomorrow's wake time minus the need and the time to fall asleep`() {
        val timing = SleepTiming(bedtimeMode = BedtimeMode.SLEEP_NEED)

        val s = calculator.schedule(monday, Fx.UTC, weeks(monday), need, Duration.ZERO, timing)

        assertThat(s.targetBedtime).isEqualTo(at(monday, "22:35"))
        assertThat(s.anchor.bedtimeSource).isEqualTo(TimingSource.SLEEP_NEED)
    }

    @Test
    fun `fixed times are used as they are`() {
        val timing = SleepTiming(WakeTimeMode.FIXED, LocalTime.of(6, 0), BedtimeMode.FIXED, LocalTime.of(22, 0))

        val s = calculator.schedule(monday, Fx.UTC, weeks(monday), need, Duration.ZERO, timing)

        assertThat(s.typicalWake.toLocalTime()).isEqualTo(LocalTime.of(6, 0))
        assertThat(s.tomorrowWakeZone.start).isEqualTo(at(monday.plusDays(1), "05:30"))
        assertThat(s.targetBedtime).isEqualTo(at(monday, "22:00"))
        assertThat(s.anchor.wakeSource).isEqualTo(TimingSource.FIXED)
        assertThat(s.anchor.bedtimeSource).isEqualTo(TimingSource.FIXED)
    }

    @Test
    fun `a fixed bedtime after midnight belongs to the next day`() {
        val timing = SleepTiming(bedtimeMode = BedtimeMode.FIXED, bedtime = LocalTime.of(1, 30))

        val s = calculator.schedule(monday, Fx.UTC, weeks(monday), need, Duration.ZERO, timing)

        assertThat(s.targetBedtime).isEqualTo(at(monday.plusDays(1), "01:30"))
    }

    @Test
    fun `without nights the user's own times stand in`() {
        val timing = SleepTiming(wakeTime = LocalTime.of(7, 15), bedtime = LocalTime.of(23, 30))

        val s = calculator.schedule(monday, Fx.UTC, emptyList(), need, Duration.ZERO, timing)

        assertThat(s.typicalWake.toLocalTime()).isEqualTo(LocalTime.of(7, 15))
        assertThat(s.targetBedtime).isEqualTo(at(monday, "23:30"))
        assertThat(s.anchor.isFallback).isTrue()
        assertThat(s.anchor.wakeSource).isEqualTo(TimingSource.NOT_ENOUGH_DATA)
        assertThat(s.anchor.bedtimeSource).isEqualTo(TimingSource.NOT_ENOUGH_DATA)
    }

    @Test
    fun `last night arriving doesn't move the learned wake time`() {
        val before = calculator.schedule(monday, Fx.UTC, weeks(monday), need, Duration.ZERO, SleepTiming())
        val after = calculator.schedule(
            monday, Fx.UTC, weeks(monday) + Fx.night(monday, "01:00", "05:30"), need, Duration.ZERO, SleepTiming(),
        )

        assertThat(after.typicalWake).isEqualTo(before.typicalWake)
        assertThat(after.actualWake).isEqualTo(at(monday, "05:30"))
    }
}
