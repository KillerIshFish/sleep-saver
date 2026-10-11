package com.sleepsaver.core.domain.config

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.nightsteps.EvidenceLevel
import com.sleepsaver.core.domain.nightsteps.StepTiming
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.domain.schedule.WakeStyle
import java.time.Duration
import org.junit.jupiter.api.Test

class SleepConfigGroupsTest {

    private val config = SleepConfig.DEFAULT

    @Test
    fun `every technique has a default placement`() {
        assertThat(config.steps.techniqueTimings.keys).containsExactlyElementsIn(Technique.entries)
        assertThat(config.steps.timing(Technique.SLOW_TIDE)).isEqualTo(StepTiming(Duration.ofMinutes(30), Duration.ofMinutes(10)))
        assertThat(config.steps.timing(Technique.WARM_SHOWER)).isEqualTo(StepTiming(Duration.ofMinutes(90), Duration.ofMinutes(15)))
    }

    @Test
    fun `a technique missing from the map uses the custom defaults`() {
        val steps = NightStepsConfig(techniqueTimings = emptyMap())

        assertThat(steps.timing(Technique.BODY_SCAN)).isEqualTo(StepTiming(Duration.ofMinutes(45), Duration.ofMinutes(10)))
    }

    @Test
    fun `each wake-up style has its own cue profile`() {
        val alarm = config.alarm

        assertThat(alarm.cue(WakeStyle.CALM).firstChime).isTrue()
        assertThat(alarm.cue(WakeStyle.CALM).swell).isEqualTo(Duration.ofSeconds(90))
        assertThat(alarm.cue(WakeStyle.STANDARD).vibrationAfter).isEqualTo(Duration.ofSeconds(30))
        assertThat(alarm.cue(WakeStyle.FIRM).escalateAfter).isEqualTo(Duration.ofMinutes(3))
        assertThat(alarm.cue(WakeStyle.QUIET_BED).speakerAfter).isEqualTo(Duration.ofMinutes(2))
    }

    @Test
    fun `defaults sit inside their ranges`() {
        val alarm = config.alarm
        val week = config.week

        assertThat(week.windDownMinLength).isAtLeast(config.minPhaseLength)
        assertBetween(config.windDownLength, week.windDownMinLength, week.windDownMaxLength)
        assertBetween(alarm.defaultWindow, alarm.windowMin, alarm.windowMax)
        assertBetween(alarm.fixedSnoozeDefault, alarm.fixedSnoozeMin, alarm.fixedSnoozeMax)
        assertBetween(alarm.fixedSnoozeCountDefault, alarm.fixedSnoozeCountMin, alarm.fixedSnoozeCountMax)
        assertThat(alarm.overrunDefault).isAtMost(alarm.overrunMax)
        assertThat(alarm.defaultVolume).isAtLeast(alarm.minVolume)
        assertBetween(config.surfacing.restlessZ, config.surfacing.restlessZMin, config.surfacing.restlessZMax)
        assertThat(config.hush.timers).contains(config.hush.defaultTimer)
        assertThat(config.steps.slowTideLengths).contains(config.steps.slowTideDefaultLength)
    }

    private fun <T : Comparable<T>> assertBetween(value: T, min: T, max: T) {
        assertThat(value).isAtLeast(min)
        assertThat(value).isAtMost(max)
    }

    @Test
    fun `screen-based and little-evidence techniques are never seeded on`() {
        assertThat(Technique.entries.filter { it.defaultOn })
            .containsExactly(Technique.SCREENS_DOWN, Technique.TOMORROW_LIST, Technique.SLOW_TIDE).inOrder()
        assertThat(Technique.entries.filter { it.screenBased }).containsExactly(Technique.TV, Technique.GAMING)
        assertThat(Technique.entries.filter { it.screenBased }.map { it.evidence }.toSet()).containsExactly(EvidenceLevel.LITTLE)
    }
}
