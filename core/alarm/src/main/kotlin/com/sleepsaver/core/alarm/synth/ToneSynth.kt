package com.sleepsaver.core.alarm.synth

import com.sleepsaver.core.alarm.AlarmPlatform
import com.sleepsaver.core.domain.schedule.AlarmTone
import java.time.Duration
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The alarm tones, synthesized on the phone (SPEC_V2 §4.8): additive sines with soft attack and decay envelopes, peak
 * ≤ [AlarmPlatform.TONE_PEAK], no files and no downloads. Each tone repeats a cycle whose envelope starts and ends at
 * silence, so the loop never clicks.
 *
 *  - SOFT_CHIMES: E5, G5, C6 rising, 0.6 s apart, a 2.4-s cycle.
 *  - WARM_RISE: a slow A3/E4 pad under one amplitude swell, a 4-s cycle.
 *  - BRIGHT_BELLS: bell partials 1, 2.76 and 5.4 × C6, one strike per 1.2-s cycle.
 *
 * SYSTEM_ALARM is the phone's own sound (MediaPlayer); as a synth it falls back to SOFT_CHIMES. Stateful only in its
 * position: [fill] continues where the last call stopped.
 */
internal class ToneSynth(tone: AlarmTone, private val sampleRate: Int = AlarmPlatform.SAMPLE_RATE) {

    private val tone = if (tone == AlarmTone.SYSTEM_ALARM) AlarmTone.SOFT_CHIMES else tone
    private var frame = 0L

    /** One cycle of the tone (Calm's first chime is one cycle). */
    val cycle: Duration get() = cycleOf(tone)

    /** Writes the next [count] samples into [buffer]. */
    fun fill(buffer: FloatArray, count: Int = buffer.size) {
        val cycleSeconds = cycleSeconds(tone)
        for (i in 0 until count) {
            val t = (frame + i).toDouble() / sampleRate
            buffer[i] = sampleAt(tone, t % cycleSeconds)
        }
        frame += count
    }

    companion object {
        /** Attack of every note and the fade that brings each cycle back to silence. */
        private const val ATTACK_S = 0.01
        private const val CYCLE_FADE_S = 0.08

        fun cycleOf(tone: AlarmTone): Duration = Duration.ofMillis((cycleSeconds(tone) * 1000).toLong())

        private fun cycleSeconds(tone: AlarmTone): Double = when (tone) {
            AlarmTone.WARM_RISE -> AlarmPlatform.WARM_RISE_CYCLE_S
            AlarmTone.BRIGHT_BELLS -> AlarmPlatform.BRIGHT_BELL_CYCLE_S
            AlarmTone.SOFT_CHIMES, AlarmTone.SYSTEM_ALARM -> AlarmPlatform.SOFT_CHIME_CYCLE_S
        }

        /** The sample at [t] seconds into a cycle of [tone]. */
        fun sampleAt(tone: AlarmTone, t: Double): Float {
            val cycle = cycleSeconds(tone)
            val value = when (tone) {
                AlarmTone.WARM_RISE -> warmRise(t, cycle)
                AlarmTone.BRIGHT_BELLS -> brightBell(t)
                AlarmTone.SOFT_CHIMES, AlarmTone.SYSTEM_ALARM -> softChimes(t)
            }
            val fade = ((cycle - t) / CYCLE_FADE_S).coerceIn(0.0, 1.0)
            return (value * fade).toFloat().coerceIn(-AlarmPlatform.TONE_PEAK, AlarmPlatform.TONE_PEAK)
        }

        private fun softChimes(t: Double): Double {
            var sum = 0.0
            AlarmPlatform.SOFT_CHIME_NOTES_HZ.forEachIndexed { index, hz ->
                val local = t - index * AlarmPlatform.SOFT_CHIME_SPACING_S
                if (local >= 0) {
                    val envelope = attack(local) * exp(-local / 0.5)
                    sum += 0.5 * envelope * (0.8 * sin(2 * PI * hz * local) + 0.2 * sin(4 * PI * hz * local))
                }
            }
            return sum
        }

        private fun warmRise(t: Double, cycle: Double): Double {
            val swell = sin(PI * t / cycle).let { it * it }
            var sum = 0.0
            AlarmPlatform.WARM_RISE_NOTES_HZ.forEach { hz ->
                sum += 0.33 * sin(2 * PI * hz * t) + 0.08 * sin(4 * PI * hz * t)
            }
            return swell * sum
        }

        private fun brightBell(t: Double): Double {
            val base = AlarmPlatform.BRIGHT_BELL_HZ
            val partials = AlarmPlatform.BRIGHT_BELL_PARTIALS
            val amplitudes = doubleArrayOf(0.45, 0.22, 0.11)
            val decays = doubleArrayOf(0.6, 0.25, 0.12)
            var sum = 0.0
            for (k in partials.indices) {
                sum += amplitudes[k] * exp(-t / decays[k]) * sin(2 * PI * base * partials[k] * t)
            }
            return attack(t) * sum
        }

        private fun attack(local: Double): Double = min(1.0, local / ATTACK_S)
    }
}
