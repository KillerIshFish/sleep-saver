package com.sleepsaver.core.alarm

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmTextTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(hour: Int, minute: Int): Instant = ZonedDateTime.of(2026, 10, 6, hour, minute, 0, 0, zone).toInstant()

    @Test
    fun `clock times read like the rest of the app in both clock styles`() {
        assertThat(AlarmText.clock(at(6, 30), zone, use24h = false)).isEqualTo("6:30 AM")
        assertThat(AlarmText.clock(at(18, 5), zone, use24h = false)).isEqualTo("6:05 PM")
        assertThat(AlarmText.clock(at(6, 30), zone, use24h = true)).isEqualTo("06:30")
        assertThat(AlarmText.clock(at(18, 5), zone, use24h = true)).isEqualTo("18:05")
    }

    @Test
    fun `minutes early count whole clock minutes and are never negative`() {
        assertThat(AlarmText.minutesEarly(at(6, 12).plusSeconds(50), at(6, 30))).isEqualTo(18)
        assertThat(AlarmText.minutesEarly(at(6, 31), at(6, 30))).isEqualTo(0)
    }
}
