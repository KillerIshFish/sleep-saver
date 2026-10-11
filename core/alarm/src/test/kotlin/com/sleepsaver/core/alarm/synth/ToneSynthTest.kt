package com.sleepsaver.core.alarm.synth

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.alarm.AlarmPlatform
import com.sleepsaver.core.domain.schedule.AlarmTone
import org.junit.Test
import java.time.Duration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

class ToneSynthTest {

    private val rate = AlarmPlatform.SAMPLE_RATE
    private val synthesized = listOf(AlarmTone.SOFT_CHIMES, AlarmTone.WARM_RISE, AlarmTone.BRIGHT_BELLS)

    private fun render(tone: AlarmTone, seconds: Double): FloatArray =
        FloatArray((seconds * rate).toInt()).also { ToneSynth(tone, rate).fill(it) }

    @Test
    fun `every tone stays at or below the peak and is clearly audible`() {
        synthesized.forEach { tone ->
            val samples = render(tone, 8.0)
            val peak = samples.maxOf { abs(it) }
            val rms = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

            assertThat(peak).isAtMost(AlarmPlatform.TONE_PEAK)
            assertThat(peak).isGreaterThan(0.2f)
            assertThat(rms).isGreaterThan(0.03)
        }
    }

    @Test
    fun `no tone carries DC`() {
        synthesized.forEach { tone ->
            val samples = render(tone, 4 * ToneSynth.cycleOf(tone).toMillis() / 1000.0)
            assertThat(abs(samples.average())).isLessThan(0.01)
        }
    }

    @Test
    fun `cycles are 2·4, 4 and 1·2 seconds`() {
        assertThat(ToneSynth.cycleOf(AlarmTone.SOFT_CHIMES)).isEqualTo(Duration.ofMillis(2_400))
        assertThat(ToneSynth.cycleOf(AlarmTone.WARM_RISE)).isEqualTo(Duration.ofMillis(4_000))
        assertThat(ToneSynth.cycleOf(AlarmTone.BRIGHT_BELLS)).isEqualTo(Duration.ofMillis(1_200))
        assertThat(ToneSynth(AlarmTone.SYSTEM_ALARM).cycle).isEqualTo(Duration.ofMillis(2_400))
    }

    @Test
    fun `each cycle starts and ends in silence, so the loop never clicks`() {
        synthesized.forEach { tone ->
            val cycle = ToneSynth.cycleOf(tone).toMillis() / 1000.0
            assertThat(abs(ToneSynth.sampleAt(tone, 0.0))).isLessThan(0.01f)
            assertThat(abs(ToneSynth.sampleAt(tone, cycle - 1.0 / rate))).isLessThan(0.01f)
        }
    }

    @Test
    fun `filling in pieces continues the same stream`() {
        val whole = render(AlarmTone.BRIGHT_BELLS, 1.0)
        val synth = ToneSynth(AlarmTone.BRIGHT_BELLS, rate)
        val first = FloatArray(10_000).also { synth.fill(it) }
        val second = FloatArray(rate - 10_000).also { synth.fill(it) }

        assertThat((first + second).toList()).isEqualTo(whole.toList())
    }

    @Test
    fun `the phone's alarm sound as a synth is Soft chimes`() {
        assertThat(render(AlarmTone.SYSTEM_ALARM, 2.4).toList()).isEqualTo(render(AlarmTone.SOFT_CHIMES, 2.4).toList())
    }

    @Test
    fun `the notes are where the tones say`() {
        // Soft chimes: E5 rings in the first 0.6 s, before G5 starts.
        val chimes = render(AlarmTone.SOFT_CHIMES, 0.6)
        assertThat(power(chimes, 659.25)).isGreaterThan(20 * power(chimes, 783.99))
        // Warm rise: A3 and E4, nothing up at C6.
        val pad = render(AlarmTone.WARM_RISE, 4.0)
        assertThat(power(pad, 220.0)).isGreaterThan(20 * power(pad, 1046.5))
        assertThat(power(pad, 329.63)).isGreaterThan(20 * power(pad, 1046.5))
        // Bright bells: C6 with its 2.76 partial.
        val bells = render(AlarmTone.BRIGHT_BELLS, 1.2)
        assertThat(power(bells, 1046.5)).isGreaterThan(20 * power(bells, 659.25))
        assertThat(power(bells, 1046.5 * 2.76)).isGreaterThan(20 * power(bells, 659.25))
    }

    /** Goertzel power of [hz] in [samples]. */
    private fun power(samples: FloatArray, hz: Double): Double {
        val coefficient = 2 * cos(2 * PI * hz / rate)
        var s1 = 0.0
        var s2 = 0.0
        for (x in samples) {
            val s0 = x + coefficient * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - coefficient * s1 * s2
    }
}
