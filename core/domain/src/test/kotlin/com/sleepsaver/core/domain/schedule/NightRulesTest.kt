package com.sleepsaver.core.domain.schedule

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingOutcome
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.energy.EnergyScheduleCalculator
import com.sleepsaver.core.domain.model.BedtimeMode
import com.sleepsaver.core.domain.model.SleepTiming
import com.sleepsaver.core.domain.model.WakeTimeMode
import com.sleepsaver.core.domain.testing.Fx
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NightRulesTest {

    /** Monday 2026-10-05. */
    private val monday = LocalDate.of(2026, 10, 5)
    private val tuesday = monday.plusDays(1)

    private fun at(date: LocalDate, time: String): Instant = Fx.instant(date, time)

    @Nested
    inner class EveningFor {

        private val at23 = { d: LocalDate -> at(d, "23:00") }

        /** A night owl: bedtime 01:30 after midnight. */
        private val at0130 = { d: LocalDate -> at(d.plusDays(1), "01:30") }

        @Test
        fun `after yesterday's melatonin window the evening is today`() {
            assertThat(NightRules.eveningFor(at(tuesday, "00:10"), Fx.UTC, at23)).isEqualTo(tuesday)
            assertThat(NightRules.eveningFor(at(tuesday, "21:00"), Fx.UTC, at23)).isEqualTo(tuesday)
        }

        @Test
        fun `a night owl's evening runs until bedtime plus half the melatonin window`() {
            assertThat(NightRules.eveningFor(at(tuesday, "01:59"), Fx.UTC, at0130)).isEqualTo(monday)
            assertThat(NightRules.eveningFor(at(tuesday, "02:00"), Fx.UTC, at0130)).isEqualTo(tuesday)
        }

        @Test
        fun `today is the date in the given zone`() {
            // 03:00 UTC on Tuesday is still Monday 23:00 in New York.
            val evening = NightRules.eveningFor(Instant.parse("2026-10-06T03:00:00Z"), Fx.NEW_YORK, { d -> Fx.instant(d, "23:00", Fx.NEW_YORK) })

            assertThat(evening).isEqualTo(monday)
        }
    }

    @Nested
    inner class IsNight {

        private val bedtime = at(monday, "23:00")
        private val end = at(tuesday, "06:00")

        @Test
        fun `night runs from the bedtime until the end`() {
            assertThat(NightRules.isNight(at(monday, "22:59"), bedtime, end, anchoredAt = null)).isFalse()
            assertThat(NightRules.isNight(at(monday, "23:00"), bedtime, end, anchoredAt = null)).isTrue()
            assertThat(NightRules.isNight(at(tuesday, "05:59"), bedtime, end, anchoredAt = null)).isTrue()
            assertThat(NightRules.isNight(at(tuesday, "06:00"), bedtime, end, anchoredAt = null)).isFalse()
        }

        @Test
        fun `dropping anchor starts the night before the bedtime`() {
            val anchoredAt = at(monday, "21:40")

            assertThat(NightRules.isNight(at(monday, "21:45"), bedtime, end, anchoredAt)).isTrue()
            assertThat(NightRules.isNight(at(tuesday, "06:00"), bedtime, end, anchoredAt)).isFalse()
        }

        @Test
        fun `a bedtime more than 18 hours before the end is stale`() {
            val laterEnd = at(tuesday, "17:00") // 18 h after 23:00
            val tooLateEnd = at(tuesday, "17:01")

            assertThat(NightRules.isNight(at(tuesday, "01:00"), bedtime, laterEnd, anchoredAt = null)).isTrue()
            assertThat(NightRules.isNight(at(tuesday, "01:00"), bedtime, tooLateEnd, anchoredAt = null)).isFalse()
            assertThat(NightRules.isNight(at(tuesday, "01:00"), bedtime, tooLateEnd, at(monday, "23:30"))).isTrue()
        }

        @Test
        fun `without an end only an anchor makes it night, for at most the night's maximum length`() {
            val anchoredAt = at(monday, "23:00")

            assertThat(NightRules.isNight(at(monday, "23:30"), bedtime, end = null, anchoredAt = null)).isFalse()
            assertThat(NightRules.isNight(at(monday, "23:30"), bedtime, end = null, anchoredAt)).isTrue()
            assertThat(NightRules.isNight(anchoredAt + Duration.ofHours(14), bedtime, end = null, anchoredAt)).isFalse()
        }

        @Test
        fun `no bedtime and no anchor is never night`() {
            assertThat(NightRules.isNight(at(tuesday, "01:00"), bedtime = null, end, anchoredAt = null)).isFalse()
        }

        @Test
        fun `the night ends at the alarm's window start, else at the wake zone`() {
            val window = at(tuesday, "06:00")
            val wakeZone = at(tuesday, "06:30")

            assertThat(NightRules.nightEnd(window, wakeZone)).isEqualTo(window)
            assertThat(NightRules.nightEnd(null, wakeZone)).isEqualTo(wakeZone)
            assertThat(NightRules.nightEnd(null, null)).isNull()
        }
    }

    @Nested
    inner class WakeDateOf {

        @Test
        fun `from 14 00 a moment belongs to the next wake date`() {
            assertThat(NightRules.wakeDateOf(at(monday, "13:59"), Fx.UTC)).isEqualTo(monday)
            assertThat(NightRules.wakeDateOf(at(monday, "14:00"), Fx.UTC)).isEqualTo(tuesday)
            assertThat(NightRules.wakeDateOf(at(monday, "23:10"), Fx.UTC)).isEqualTo(tuesday)
            assertThat(NightRules.wakeDateOf(at(tuesday, "01:00"), Fx.UTC)).isEqualTo(tuesday)
        }

        @Test
        fun `the local time in the recorded offset decides`() {
            // 12:30 UTC is 14:30 at +02:00.
            assertThat(NightRules.wakeDateOf(at(monday, "12:30"), ZoneOffset.ofHours(2))).isEqualTo(tuesday)
            assertThat(NightRules.wakeDateOf(at(monday, "12:30"), Fx.UTC)).isEqualTo(monday)
        }
    }

    /** Bedtime 23:00, a 6:45 alarm with a 30-min window (6:15), wake zone from 6:30, the same every day. */
    @Nested
    inner class Focus {

        private val wednesday = tuesday.plusDays(1)

        private fun bounds(evening: LocalDate, alarm: Boolean = true) = NightBounds(
            bedtime = at(evening, "23:00"),
            alarmWindowStart = if (alarm) at(evening.plusDays(1), "06:15") else null,
            wakeZoneStart = at(evening.plusDays(1), "06:30"),
        )

        private fun focus(
            now: Instant,
            morningStop: Instant? = null,
            anchoredAt: Instant? = null,
            endedAt: Instant? = null,
            stillYesterday: Boolean = false,
            alarmThisMorning: Boolean = true,
        ): FocusNight {
            val today = now.atZone(Fx.UTC).toLocalDate()
            return NightRules.focus(
                now = now,
                zone = Fx.UTC,
                lastNight = bounds(today.minusDays(1), alarm = alarmThisMorning),
                tonight = bounds(today),
                morningStop = morningStop,
                anchoredAt = anchoredAt,
                endedAt = endedAt,
                eveningStillYesterday = stillYesterday,
            )
        }

        @Test
        fun `night runs from the bedtime until the alarm window opens`() {
            assertThat(focus(at(tuesday, "22:59")).isNight).isFalse()
            val bedtime = focus(at(tuesday, "23:00"))
            assertThat(bedtime.isNight).isTrue()
            assertThat(bedtime.nightOf).isEqualTo(tuesday)
            assertThat(bedtime.end).isEqualTo(at(wednesday, "06:15"))

            val smallHours = focus(at(wednesday, "02:00"))
            assertThat(smallHours.isNight).isTrue()
            assertThat(smallHours.wakeDate).isEqualTo(wednesday)
            assertThat(focus(at(wednesday, "06:14")).isNight).isTrue()

            val open = focus(at(wednesday, "06:15"))
            assertThat(open.isNight).isFalse()
            assertThat(open.nightOf).isEqualTo(wednesday)
        }

        @Test
        fun `without an alarm the night ends where the wake zone starts`() {
            assertThat(focus(at(wednesday, "06:20"), alarmThisMorning = false).isNight).isTrue()
            assertThat(focus(at(wednesday, "06:30"), alarmThisMorning = false).isNight).isFalse()
        }

        @Test
        fun `a stopped or timed-out morning alarm ends the night at once`() {
            // An early surfacing ring at 6:05 (a 45-min window opened at 6:00) was stopped; the alarm then left the
            // upcoming list, so only the wake zone (6:30) would have ended the night.
            val stopped = focus(at(wednesday, "06:06"), morningStop = at(wednesday, "06:05"), alarmThisMorning = false)

            assertThat(stopped.isNight).isFalse()
            assertThat(stopped.nightOf).isEqualTo(wednesday)
            assertThat(focus(at(wednesday, "06:04"), morningStop = at(wednesday, "06:05"), alarmThisMorning = false).isNight).isTrue()
        }

        @Test
        fun `an anchor makes it night early, and only for the night of its own wake date`() {
            assertThat(focus(at(tuesday, "22:10")).isNight).isFalse()
            assertThat(focus(at(tuesday, "22:10"), anchoredAt = at(tuesday, "22:05")).isNight).isTrue()
            // Last night's anchor stays in the night state until the alarm is stopped, but no longer means night.
            assertThat(focus(at(wednesday, "06:20"), anchoredAt = at(tuesday, "22:55")).isNight).isFalse()
            assertThat(focus(at(wednesday, "08:00"), anchoredAt = at(wednesday, "07:00")).isNight).isFalse()
        }

        @Test
        fun `before the schedule rolls over last night stays in focus`() {
            val owl = focus(at(wednesday, "06:20"), stillYesterday = true)

            assertThat(owl.nightOf).isEqualTo(tuesday)
            assertThat(owl.isNight).isFalse() // its end (6:15) has passed
            assertThat(focus(at(wednesday, "01:00"), stillYesterday = true).isNight).isTrue()
        }

        @Test
        fun `I'm up ends this night's clock-free time until the next anchor, and only this night's`() {
            assertThat(focus(at(tuesday, "23:40"), endedAt = at(tuesday, "23:30")).isNight).isFalse()
            assertThat(focus(at(tuesday, "23:50"), anchoredAt = at(tuesday, "23:45"), endedAt = at(tuesday, "23:30")).isNight).isTrue()
            // Up at 5:30: the next bedtime (less than 18 h later) is night as usual.
            assertThat(focus(at(wednesday, "05:40"), endedAt = at(wednesday, "05:30")).isNight).isFalse()
            assertThat(focus(at(wednesday, "23:30"), endedAt = at(wednesday, "05:30")).isNight).isTrue()
        }

        @Test
        fun `the morning stop is the planned alarm's stop, or its first ring when nobody stopped it`() {
            val stopped = Fx.alarmEvent(wednesday, "06:45", firstRingAt = "06:20", RingReason.RESTLESS, stoppedAt = "06:22")
            val timedOut = Fx.alarmEvent(wednesday, "06:45", firstRingAt = "06:45", RingReason.DEADLINE, outcome = RingOutcome.TIMED_OUT)
            val sounding = Fx.alarmEvent(wednesday, "06:45", firstRingAt = "06:20", RingReason.RESTLESS)
            val nap = Fx.alarmEvent(wednesday, "15:00", firstRingAt = "15:00", RingReason.CATNAP_END, kind = RingKind.NAP, stoppedAt = "15:01")

            assertThat(NightRules.morningStop(stopped, wednesday)).isEqualTo(at(wednesday, "06:22"))
            assertThat(NightRules.morningStop(timedOut, wednesday)).isEqualTo(at(wednesday, "06:45"))
            assertThat(NightRules.morningStop(sounding, wednesday)).isNull()
            assertThat(NightRules.morningStop(nap, wednesday)).isNull()
            assertThat(NightRules.morningStop(stopped, tuesday)).isNull()
            assertThat(NightRules.morningStop(null, wednesday)).isNull()
        }

        @Test
        fun `bounds from the energy schedule for its own evening and the one before`() {
            val timing = SleepTiming(WakeTimeMode.FIXED, LocalTime.of(7, 0), BedtimeMode.FIXED, LocalTime.of(23, 0))
            val schedule = EnergyScheduleCalculator().schedule(tuesday, Fx.UTC, emptyList(), Duration.ofHours(8), Duration.ZERO, timing)
            val alarm = at(wednesday, "06:15")

            val tonight = NightRules.boundsFrom(schedule, tuesday, alarm)
            val lastNight = NightRules.boundsFrom(schedule, monday, alarmWindowStart = null)
            val planned = NightRules.boundsFrom(schedule, monday, alarmWindowStart = null, alarmBedtime = at(monday, "22:30"))

            assertThat(tonight).isEqualTo(NightBounds(schedule.targetBedtime, alarm, schedule.tomorrowWakeZone.start))
            assertThat(lastNight.bedtime).isEqualTo(at(monday, "23:00"))
            assertThat(lastNight.wakeZoneStart).isEqualTo(schedule.wakeZone.start)
            assertThat(planned.bedtime).isEqualTo(at(monday, "22:30"))
            assertThat(NightRules.boundsFrom(schedule, wednesday, alarm)).isEqualTo(NightBounds(null, alarm, null))
        }

        @Test
        fun `last night keeps its own evening's bedtime when the next evening's is later`() {
            // Monday's evening plan says 22:30, Tuesday's 01:30 (after midnight); no alarm on Tuesday morning.
            val week = WeekPlan.DEFAULT.evening(DayOfWeek.MONDAY, LocalTime.of(22, 30)).evening(DayOfWeek.TUESDAY, LocalTime.of(1, 30))
            val timing = SleepTiming(WakeTimeMode.FIXED, LocalTime.of(7, 0), BedtimeMode.FIXED, LocalTime.of(23, 0), week = week)
            val schedule = EnergyScheduleCalculator().schedule(tuesday, Fx.UTC, emptyList(), Duration.ofHours(8), Duration.ZERO, timing)
            val now = at(tuesday, "00:20")

            val lastNight = NightRules.boundsFrom(schedule, monday, alarmWindowStart = null)
            val focus = NightRules.focus(now, Fx.UTC, lastNight, tonight = NightRules.boundsFrom(schedule, tuesday, alarmWindowStart = null))

            assertThat(schedule.targetBedtime).isEqualTo(at(wednesday, "01:30"))
            assertThat(lastNight.bedtime).isEqualTo(at(monday, "22:30"))
            assertThat(focus.nightOf).isEqualTo(monday)
            assertThat(focus.isNight).isTrue()
        }

        private fun WeekPlan.evening(day: DayOfWeek, bedtime: LocalTime): WeekPlan =
            with(day(day).let { it.copy(evening = it.evening.copy(bedtime = bedtime)) })
    }
}
