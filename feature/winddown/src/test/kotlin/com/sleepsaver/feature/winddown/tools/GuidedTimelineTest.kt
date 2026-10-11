package com.sleepsaver.feature.winddown.tools

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.nightsteps.GuidedTool
import org.junit.Test
import java.time.Duration

class GuidedTimelineTest {

    private val config = SleepConfig.DEFAULT
    private val defaultPace = config.slowTidePatterns().first()

    @Test
    fun `segments follow each other and the timeline ends after the last`() {
        val timeline = GuidedTimeline(slowTideScript(defaultPace, Duration.ofSeconds(20)))

        assertThat(timeline.totalMs).isEqualTo(20_000)
        assertThat(timeline.at(0)?.segment?.cue).isEqualTo(BreathPhase.IN)
        assertThat(timeline.at(3_999)?.index).isEqualTo(0)
        assertThat(timeline.at(4_000)?.index).isEqualTo(1)
        assertThat(timeline.at(4_000)?.startMs).isEqualTo(4_000)
        assertThat(timeline.at(4_000)?.endMs).isEqualTo(10_000)
        assertThat(timeline.at(10_000)?.segment?.cue).isEqualTo(BreathPhase.IN)
        assertThat(timeline.at(19_999)?.index).isEqualTo(3)
        assertThat(timeline.at(20_000)).isNull()
        assertThat(timeline.at(-1)).isNull()
    }

    @Test
    fun `zero-length segments are skipped`() {
        val timeline = GuidedTimeline(
            listOf(
                GuidedSegment("a", Duration.ofSeconds(1), HapticCue.NONE),
                GuidedSegment("skip", Duration.ZERO, HapticCue.TICK),
                GuidedSegment("b", Duration.ofSeconds(1), HapticCue.NONE),
            ),
        )

        assertThat(timeline.at(1_000)?.segment?.cue).isEqualTo("b")
    }

    @Test
    fun `slow tide fills its length with whole breaths, in on a tick and out on two`() {
        val script = slowTideScript(defaultPace, config.steps.slowTideDefaultLength)

        assertThat(script).hasSize(120)
        assertThat(script.first()).isEqualTo(GuidedSegment(BreathPhase.IN, Duration.ofSeconds(4), HapticCue.TICK))
        assertThat(script[1]).isEqualTo(GuidedSegment(BreathPhase.OUT, Duration.ofSeconds(6), HapticCue.DOUBLE_TICK))
        // A length that isn't a whole number of breaths rounds up to the next breath.
        val longOut = config.slowTidePatterns()[2]
        assertThat(GuidedTimeline(slowTideScript(longOut, Duration.ofMinutes(5))).totalMs).isEqualTo(300_000)
        assertThat(GuidedTimeline(slowTideScript(longOut, Duration.ofSeconds(13))).totalMs).isEqualTo(24_000)
    }

    @Test
    fun `the paces are the configured ones, the default first`() {
        assertThat(config.slowTidePatterns()).containsExactly(
            BreathPattern(Duration.ofSeconds(4), Duration.ofSeconds(6)),
            BreathPattern(Duration.ofSeconds(5), Duration.ofSeconds(5)),
            BreathPattern(Duration.ofSeconds(4), Duration.ofSeconds(8)),
        ).inOrder()
    }

    @Test
    fun `body scan is ten one-minute areas, each starting with a pulse`() {
        val script = bodyScanScript(config)

        assertThat(script.map { it.cue }).isEqualTo((0..9).toList())
        assertThat(script.all { it.length == config.steps.bodyScanStep && it.haptic == HapticCue.PULSE }).isTrue()
    }

    @Test
    fun `muscle release tenses and lets go each of the twelve groups, hands to feet`() {
        val script = muscleReleaseScript(config)

        assertThat(script).hasSize(24)
        assertThat(script.first()).isEqualTo(GuidedSegment(MuscleCue(MuscleGroup.HANDS, tense = true), config.steps.muscleTense, HapticCue.TICK))
        assertThat(script[1]).isEqualTo(GuidedSegment(MuscleCue(MuscleGroup.HANDS, tense = false), config.steps.muscleRelease, HapticCue.DOUBLE_TICK))
        assertThat(script.last().cue).isEqualTo(MuscleCue(MuscleGroup.FEET, tense = false))
        assertThat(GuidedTimeline(script).totalMs).isEqualTo(Duration.ofMinutes(4).toMillis())
    }

    @Test
    fun `only tools with a screen open from a route`() {
        assertThat(screenToolOf("SLOW_TIDE")).isEqualTo(GuidedTool.SLOW_TIDE)
        assertThat(screenToolOf("TOMORROW_LIST")).isEqualTo(GuidedTool.TOMORROW_LIST)
        assertThat(screenToolOf("HUSH")).isNull()
        assertThat(screenToolOf("NONE")).isNull()
        assertThat(screenToolOf("WORD_GAME")).isNull()
    }
}
