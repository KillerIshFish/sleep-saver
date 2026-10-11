package com.sleepsaver.core.domain.config

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.schedule.WakeStyle
import java.time.Duration
import org.junit.jupiter.api.Test

class AlarmConfigTest {

    private val alarm = SleepConfig.DEFAULT.alarm

    @Test
    fun `a ring stops itself after ringTimeout, a test ring after testRingLength`() {
        WakeStyle.entries.forEach { style ->
            assertThat(alarm.ringCap(RingKind.PLANNED, style, uiBlocked = false)).isEqualTo(alarm.ringTimeout)
            assertThat(alarm.ringCap(RingKind.NAP, style, uiBlocked = false)).isEqualTo(alarm.ringTimeout)
            assertThat(alarm.ringCap(RingKind.TEST, style, uiBlocked = false)).isEqualTo(alarm.testRingLength)
        }
    }

    @Test
    fun `with the ring UI blocked every style still sounds for the whole cap after its speaker starts`() {
        WakeStyle.entries.forEach { style ->
            val cap = alarm.ringCap(RingKind.PLANNED, style, uiBlocked = true)

            assertThat(cap).isEqualTo(alarm.cue(style).speakerAfter + alarm.notificationsBlockedRingCap)
            assertThat(cap).isGreaterThan(alarm.cue(style).speakerAfter)
            assertThat(cap).isAtMost(alarm.ringTimeout)
        }
        assertThat(alarm.ringCap(RingKind.PLANNED, WakeStyle.STANDARD, uiBlocked = true)).isEqualTo(Duration.ofMinutes(2))
        assertThat(alarm.ringCap(RingKind.PLANNED, WakeStyle.QUIET_BED, uiBlocked = true)).isEqualTo(Duration.ofMinutes(4))
    }

    @Test
    fun `the blocked cap never runs past the ring timeout`() {
        val late = alarm.copy(quietBed = alarm.quietBed.copy(speakerAfter = Duration.ofMinutes(9)))

        assertThat(late.ringCap(RingKind.PLANNED, WakeStyle.QUIET_BED, uiBlocked = true)).isEqualTo(late.ringTimeout)
    }
}
