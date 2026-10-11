package com.sleepsaver.core.alarm.ring

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.schedule.WakeStyle
import org.junit.Test
import java.time.Duration

class CueTimelineTest {

    private val alarm = SleepConfig.DEFAULT.alarm
    private val chime = Duration.ofMillis(2_400)

    private fun timeline(style: WakeStyle, volume: Float = 0.8f) = CueTimeline(alarm.cue(style), volume, chime, alarm)
    private fun s(seconds: Long) = Duration.ofSeconds(seconds)

    @Test
    fun `Standard swells from the start, vibrates from 30 s and is full at 60 s`() {
        val t = timeline(WakeStyle.STANDARD)

        assertThat(t.at(Duration.ZERO)).isEqualTo(CueState(speaker = true, gain = 0.8f * alarm.swellStartGain, vibrate = false, escalated = false))
        assertThat(t.at(s(29)).vibrate).isFalse()
        assertThat(t.at(s(30)).vibrate).isTrue()
        assertThat(t.at(s(30)).gain).isWithin(1e-4f).of(0.8f * (0.05f + 0.95f * 0.5f))
        assertThat(t.at(s(60)).gain).isWithin(1e-4f).of(0.8f)
        assertThat(t.at(s(299)).escalated).isFalse()
    }

    @Test
    fun `Calm plays one soft chime, then quiet, then the swell, and vibrates from 60 s`() {
        val t = timeline(WakeStyle.CALM)

        assertThat(t.at(Duration.ZERO).gain).isWithin(1e-4f).of(0.8f * alarm.firstChimeGain)
        assertThat(t.at(chime + s(1))).isEqualTo(CueState(speaker = true, gain = 0f, vibrate = false, escalated = false))
        val swellStart = chime + alarm.firstChimeGap
        assertThat(t.at(swellStart).gain).isWithin(1e-4f).of(0.8f * alarm.swellStartGain)
        assertThat(t.at(swellStart + s(90)).gain).isWithin(1e-4f).of(0.8f)
        assertThat(t.at(s(59)).vibrate).isFalse()
        assertThat(t.at(s(60)).vibrate).isTrue()
    }

    @Test
    fun `Quiet bed vibrates alone for 2 min before the speaker starts`() {
        val t = timeline(WakeStyle.QUIET_BED)

        assertThat(t.at(Duration.ZERO)).isEqualTo(CueState(speaker = false, gain = 0f, vibrate = true, escalated = false))
        assertThat(t.at(s(119)).speaker).isFalse()
        assertThat(t.at(s(120))).isEqualTo(CueState(speaker = true, gain = 0.8f * alarm.swellStartGain, vibrate = true, escalated = false))
    }

    @Test
    fun `Firm vibrates at once, swells in 20 s and escalates at 3 min`() {
        val t = timeline(WakeStyle.FIRM)

        assertThat(t.at(Duration.ZERO).vibrate).isTrue()
        assertThat(t.at(s(20)).gain).isWithin(1e-4f).of(0.8f)
        assertThat(t.at(s(179)).escalated).isFalse()
        assertThat(t.at(s(180)).escalated).isTrue()
    }

    @Test
    fun `escalation means the day's full volume and vibration, never above it`() {
        WakeStyle.entries.forEach { style ->
            val escalated = timeline(style, volume = 0.6f).at(alarm.cue(style).escalateAfter)
            assertThat(escalated).isEqualTo(CueState(speaker = true, gain = 0.6f, vibrate = true, escalated = true))
        }
    }
}
