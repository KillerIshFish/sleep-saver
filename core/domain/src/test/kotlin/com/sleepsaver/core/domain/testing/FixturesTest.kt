package com.sleepsaver.core.domain.testing

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingOutcome
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.model.BedtimeMode
import com.sleepsaver.core.domain.model.BlockKind
import com.sleepsaver.core.domain.model.SleepStageType.AWAKE
import com.sleepsaver.core.domain.model.SleepStageType.DEEP
import com.sleepsaver.core.domain.model.SleepStageType.LIGHT
import com.sleepsaver.core.domain.model.SleepStageType.REM
import com.sleepsaver.core.domain.model.SleepTiming
import com.sleepsaver.core.domain.model.WakeTimeMode
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.WeekPlan
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.jupiter.api.Test

/** The shared builders do what their KDoc says (B1 and B2 build on them). */
class FixturesTest {

    private val wakeDate = LocalDate.of(2026, 10, 7)

    @Test
    fun `a staged night spans its stages and adds up its totals`() {
        val stages = Fx.hypnogram(Fx.dt(wakeDate.minusDays(1), "23:00"), LIGHT to 20, DEEP to 60, AWAKE to 10, REM to 30, LIGHT to 20)
        val extra = Fx.hypnogram(Fx.dt(wakeDate, "05:00"), LIGHT to 40)

        val night = Fx.stagedNight(wakeDate, stages, extra = listOf(extra), napSleep = Duration.ofMinutes(15))

        assertThat(night.isPresent).isTrue()
        assertThat(night.fellAsleep).isEqualTo(Fx.instant(wakeDate.minusDays(1), "23:00"))
        assertThat(night.wokeUp).isEqualTo(Fx.instant(wakeDate, "01:20"))
        assertThat(night.main!!.stages).hasSize(5)
        assertThat(night.awakeInMain).isEqualTo(Duration.ofMinutes(10))
        assertThat(night.mainSleep).isEqualTo(Duration.ofMinutes(130))
        val totals = night.stageTotals!!
        assertThat(totals.deep).isEqualTo(Duration.ofMinutes(60))
        assertThat(totals.light).isEqualTo(Duration.ofMinutes(40))
        assertThat(totals.rem).isEqualTo(Duration.ofMinutes(30))
        assertThat(totals.unclassified).isEqualTo(Duration.ZERO)
        assertThat(night.interruptions).hasSize(1)
        assertThat(night.extraSegments.single().kind).isEqualTo(BlockKind.EXTRA)
        assertThat(night.extraSleep).isEqualTo(Duration.ofMinutes(40))
        assertThat(night.totalSleep).isEqualTo(Duration.ofMinutes(130 + 40 + 15))
    }

    @Test
    fun `a typical hypnogram is contiguous, front-loads deep sleep and can end awake`() {
        val stages = Fx.typicalStages(wakeDate, "23:00", "07:00", finalAwake = 10)

        assertThat(stages.first().start).isEqualTo(Fx.dt(wakeDate.minusDays(1), "23:00"))
        assertThat(stages.last().end).isEqualTo(Fx.dt(wakeDate, "07:00"))
        assertThat(stages.last().type).isEqualTo(AWAKE)
        assertThat(stages.last().start).isEqualTo(Fx.dt(wakeDate, "06:50"))
        stages.zipWithNext().forEach { (a, b) -> assertThat(b.start).isEqualTo(a.end) }
        val deepFirstHalf = stages.filter { it.type == DEEP && it.start < Fx.dt(wakeDate, "03:00") }
        val deepSecondHalf = stages.filter { it.type == DEEP && it.start >= Fx.dt(wakeDate, "03:00") }
        assertThat(deepFirstHalf.sumOf { Duration.between(it.start, it.end).toMinutes() })
            .isGreaterThan(deepSecondHalf.sumOf { Duration.between(it.start, it.end).toMinutes() })
        assertThat(Fx.typicalNight(wakeDate).stageTotals!!.hasStageData).isTrue()
    }

    @Test
    fun `a staged night in a zone gets its own offset at each end`() {
        // Fall back in New York: asleep at 23:00 EDT, awake at 07:00 EST (9 h later).
        val stages = Fx.hypnogram(Fx.dt(Fx.FALL_BACK.minusDays(1), "23:00"), LIGHT to 240, DEEP to 240)

        val night = Fx.stagedNightInZone(Fx.FALL_BACK, stages, Fx.NEW_YORK)

        assertThat(night.fellAsleepOffset).isEqualTo(ZoneOffset.ofHours(-4))
        assertThat(night.wokeUpOffset).isEqualTo(ZoneOffset.ofHours(-5))
        assertThat(night.wokeUp).isEqualTo(Instant.parse("2026-11-01T12:00:00Z"))
    }

    @Test
    fun `a night in a zone spans the real elapsed time`() {
        val night = Fx.nightInZone(Fx.SPRING_FORWARD, "23:00", "07:00", zone = Fx.NEW_YORK)

        assertThat(night.mainSleep).isEqualTo(Duration.ofHours(7))
        assertThat(night.fellAsleepOffset).isEqualTo(ZoneOffset.ofHours(-5))
        assertThat(night.wokeUpOffset).isEqualTo(ZoneOffset.ofHours(-4))
    }

    @Test
    fun `regular nights end on the last wake date, oldest first`() {
        val nights = Fx.regularNights(3, wakeDate)

        assertThat(nights.map { it.wakeDate }).containsExactly(wakeDate.minusDays(2), wakeDate.minusDays(1), wakeDate).inOrder()
    }

    @Test
    fun `nights that follow a plan sleep from bedtime plus latency to the planned wake`() {
        val timing = SleepTiming(
            WakeTimeMode.FIXED, LocalTime.of(7, 0), BedtimeMode.FIXED, LocalTime.of(23, 0),
            week = Fx.weekPlan(MONDAY to LocalTime.of(6, 0)),
        )

        val nights = Fx.nightsFollowing(timing, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6), Fx.UTC)

        assertThat(nights.map { it.fellAsleep }).containsExactly(
            Fx.instant(LocalDate.of(2026, 10, 4), "23:15"), Fx.instant(LocalDate.of(2026, 10, 5), "23:15"),
        ).inOrder()
        assertThat(nights.map { it.wokeUp }).containsExactly(
            Fx.instant(LocalDate.of(2026, 10, 5), "06:00"), Fx.instant(LocalDate.of(2026, 10, 6), "07:00"),
        ).inOrder()
    }

    @Test
    fun `a week plan fixture sets the listed days only`() {
        val plan = Fx.weekPlan(MONDAY to LocalTime.of(6, 30), SATURDAY to null, mode = AlarmMode.FIXED)

        assertThat(plan.day(MONDAY).morning.wakeTime).isEqualTo(LocalTime.of(6, 30))
        assertThat(plan.day(MONDAY).morning.alarm.mode).isEqualTo(AlarmMode.FIXED)
        assertThat(plan.day(SATURDAY).morning.alarm.mode).isEqualTo(AlarmMode.OFF)
        assertThat(plan.days.drop(1).dropLast(2)).isEqualTo(WeekPlan.DEFAULT.days.drop(1).dropLast(2))
    }

    @Test
    fun `an anchor's wake date follows the 14 00 rule`() {
        assertThat(Fx.anchor(Fx.dt(wakeDate, "23:10")).wakeDate).isEqualTo(wakeDate.plusDays(1))
        assertThat(Fx.anchor(Fx.dt(wakeDate, "00:40")).wakeDate).isEqualTo(wakeDate)
    }

    @Test
    fun `an alarm event fixture fills the window, occurrence and outcome`() {
        val event = Fx.alarmEvent(wakeDate, "06:30", "06:12", RingReason.RESTLESS, snoozes = listOf("06:12"), stoppedAt = "06:21")

        assertThat(event.occurrenceId).isEqualTo("2026-10-07T06:30")
        assertThat(event.windowStart).isEqualTo(Fx.instant(wakeDate, "06:00"))
        assertThat(event.sensingStart).isEqualTo(Fx.instant(wakeDate, "05:45"))
        assertThat(event.earlyBy).isEqualTo(Duration.ofMinutes(18))
        assertThat(event.outcome).isEqualTo(RingOutcome.STOPPED)
        assertThat(event.snoozeTimes).containsExactly(Fx.instant(wakeDate, "06:12"))

        val nap = Fx.alarmEvent(wakeDate, "14:25", "14:25", RingReason.CATNAP_END, mode = AlarmMode.FIXED, kind = RingKind.NAP)
        assertThat(nap.occurrenceId).isNull()
        assertThat(nap.windowStart).isEqualTo(nap.target)
        assertThat(nap.earlyBy).isEqualTo(Duration.ZERO)
    }
}
