package com.sleepsaver.core.domain.schedule

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.testing.Fx
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
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
}
