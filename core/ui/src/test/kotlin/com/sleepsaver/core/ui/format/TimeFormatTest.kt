package com.sleepsaver.core.ui.format

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class TimeFormatTest {

    private val plus2 = ZoneOffset.ofHours(2)
    private fun at(h: Int, m: Int, zone: ZoneId = plus2) = LocalDateTime.of(2026, 10, 5, h, m).atZone(zone).toInstant()

    @Test
    fun `clock time in 12 and 24 hour`() {
        assertThat(formatClockTime(LocalTime.of(23, 47), use24h = false)).isEqualTo("11:47 PM")
        assertThat(formatClockTime(LocalTime.of(23, 47), use24h = true)).isEqualTo("23:47")
        assertThat(formatClockTime(LocalTime.of(0, 5), use24h = false)).isEqualTo("12:05 AM")
        assertThat(formatClockTime(LocalTime.of(0, 5), use24h = true)).isEqualTo("00:05")
        assertThat(formatClockTime(LocalTime.of(12, 0), use24h = false)).isEqualTo("12:00 PM")
        assertThat(formatClockTime(LocalTime.of(7, 9), use24h = false)).isEqualTo("7:09 AM")
        assertThat(formatClockTime(LocalTime.of(7, 9), use24h = true)).isEqualTo("07:09")
    }

    @Test
    fun `clock time uses the given zone or offset`() {
        val instant = at(23, 47)
        assertThat(formatClockTime(instant, plus2, use24h = false)).isEqualTo("11:47 PM")
        assertThat(formatClockTime(instant, ZoneOffset.UTC, use24h = true)).isEqualTo("21:47")
        assertThat(formatClockTime(instant, ZoneId.of("America/New_York"), use24h = false)).isEqualTo("5:47 PM")
    }

    @Test
    fun `axis labels are compact`() {
        assertThat(formatClockAxis(LocalTime.of(23, 0), use24h = false)).isEqualTo("11p")
        assertThat(formatClockAxis(LocalTime.of(0, 0), use24h = false)).isEqualTo("12a")
        assertThat(formatClockAxis(LocalTime.of(12, 0), use24h = false)).isEqualTo("12p")
        assertThat(formatClockAxis(LocalTime.of(6, 30), use24h = false)).isEqualTo("6:30a")
        assertThat(formatClockAxis(LocalTime.of(23, 0), use24h = true)).isEqualTo("23")
        assertThat(formatClockAxis(LocalTime.of(0, 0), use24h = true)).isEqualTo("00")
        assertThat(formatClockAxis(LocalTime.of(6, 30), use24h = true)).isEqualTo("06:30")
        assertThat(formatClockAxis(at(23, 0), plus2, use24h = false)).isEqualTo("11p")
    }

    @Test
    fun `time range shares AM or PM when both ends match`() {
        assertThat(formatTimeRange(LocalTime.of(23, 47), LocalTime.of(7, 12), use24h = false))
            .isEqualTo("11:47 PM – 7:12 AM")
        assertThat(formatTimeRange(LocalTime.of(9, 15), LocalTime.of(11, 30), use24h = false))
            .isEqualTo("9:15 – 11:30 AM")
        assertThat(formatTimeRange(LocalTime.of(23, 47), LocalTime.of(7, 12), use24h = true))
            .isEqualTo("23:47 – 07:12")
        assertThat(formatTimeRange(at(14, 0), at(17, 0), plus2, use24h = false)).isEqualTo("2:00 – 5:00 PM")
    }

    @Test
    fun `shift reads as earlier or later`() {
        assertThat(formatShift(Duration.ofMinutes(-13))).isEqualTo("↑13m earlier")
        assertThat(formatShift(Duration.ofMinutes(8))).isEqualTo("↓8m later")
        assertThat(formatShift(Duration.ofMinutes(65))).isEqualTo("↓1h 5m later")
        assertThat(formatShift(Duration.ZERO)).isEqualTo("same as yesterday")
        assertThat(formatShift(Duration.ofSeconds(-59))).isEqualTo("same as yesterday")
        assertThat(formatShift(Duration.ofSeconds(61))).isEqualTo("↓1m later")
        assertThat(formatShift(null)).isNull()
    }
}
