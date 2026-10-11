package com.sleepsaver.core.alarm.synth

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.sleepsaver.core.alarm.AlarmPlatform
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.config.SleepConfig
import org.junit.Test
import java.time.Duration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sqrt

class NoiseSynthTest {

    private val hush = SleepConfig.DEFAULT.hush
    private val loops = HushColor.entries.associateWith { NoiseSynth.render(it) }

    @Test
    fun `each loop is the configured length at the configured rate`() {
        loops.values.forEach { loop ->
            assertThat(loop.size.toLong()).isEqualTo(hush.loopLength.toMillis() * hush.sampleRate / 1000)
        }
    }

    @Test
    fun `levels - no clipping, no DC, the target loudness`() {
        loops.forEach { (color, loop) ->
            val rms = sqrt(loop.sumOf { it.toDouble() * it } / loop.size)
            assertThat(loop.maxOf { abs(it) }).isAtMost(0.95f)
            assertThat(abs(loop.average())).isLessThan(0.01)
            assertWithMessage("$color rms").that(rms).isWithin(0.05).of(AlarmPlatform.NOISE_RMS)
        }
    }

    @Test
    fun `pink falls about 3 dB and brown about 6 dB per octave from 100 Hz to 4 kHz`() {
        val white = slope(loops.getValue(HushColor.WHITE))
        val pink = slope(loops.getValue(HushColor.PINK))
        val brown = slope(loops.getValue(HushColor.BROWN))

        assertThat(white).isWithin(1.5).of(0.0)
        assertThat(pink).isWithin(1.5).of(-3.0)
        assertThat(brown).isWithin(1.5).of(-6.0)
    }

    @Test
    fun `the seam is as smooth as the rest of the loop`() {
        loops.forEach { (color, loop) ->
            val steps = (1 until loop.size).map { abs(loop[it] - loop[it - 1]) }.sorted()
            val median = steps[steps.size / 2]
            val seam = abs(loop[0] - loop[loop.size - 1])
            assertWithMessage("$color seam").that(seam).isLessThan(median * 4)
        }
    }

    @Test
    fun `a seed always gives the same loop, another seed another one`() {
        val short = Duration.ofSeconds(2)
        assertThat(NoiseSynth.render(HushColor.PINK, short, seed = 7).toList()).isEqualTo(NoiseSynth.render(HushColor.PINK, short, seed = 7).toList())
        assertThat(NoiseSynth.render(HushColor.PINK, short, seed = 8).toList()).isNotEqualTo(NoiseSynth.render(HushColor.PINK, short, seed = 7).toList())
    }

    @Test
    fun `the drift stays within ±driftDb and is deterministic`() {
        val a = NoiseDrift(hush, seed = 3)
        val b = NoiseDrift(hush, seed = 3)
        val maxGain = Math.pow(10.0, hush.driftDb / 20)
        var moved = false
        for (ms in 0L..Duration.ofMinutes(30).toMillis() step 1_000) {
            val gain = a.gain(ms)
            assertThat(gain).isEqualTo(b.gain(ms))
            assertThat(gain.toDouble()).isIn(Range.closed(1 / maxGain - 1e-6, maxGain + 1e-6))
            if (abs(gain - 1f) > 0.05f) moved = true
        }
        assertThat(moved).isTrue()
    }

    /**
     * Least-squares slope in dB per octave of the power spectral density (Welch, Hann windows of 8192) over octave-wide
     * bands centred from 125 Hz to 4 kHz (each band spans centre/√2..centre·√2, so together 89 Hz..5.7 kHz).
     */
    private fun slope(samples: FloatArray): Double {
        val n = 8192
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
        val psd = DoubleArray(n / 2)
        var segments = 0
        var start = 0
        while (start + n <= samples.size) {
            val re = DoubleArray(n) { samples[start + it] * window[it] }
            val im = DoubleArray(n)
            fft(re, im)
            for (k in psd.indices) psd[k] += re[k] * re[k] + im[k] * im[k]
            segments++
            start += n / 2
        }
        val rate = hush.sampleRate.toDouble()
        val centres = listOf(125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0)
        val x = centres.map { log2(it) }
        val y = centres.map { centre ->
            val from = (centre / sqrt(2.0) * n / rate).toInt()
            val to = (centre * sqrt(2.0) * n / rate).toInt()
            10 * log10((from..to).sumOf { psd[it] } / (to - from + 1) / segments)
        }
        val mx = x.average()
        val my = y.average()
        return x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / x.sumOf { (it - mx) * (it - mx) }
    }

    /** In-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2 * PI / length
            for (i in 0 until n step length) {
                for (k in 0 until length / 2) {
                    val wr = cos(angle * k)
                    val wi = sin(angle * k)
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + length / 2] * wr - im[i + k + length / 2] * wi
                    val vi = re[i + k + length / 2] * wi + im[i + k + length / 2] * wr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + length / 2] = ur - vr
                    im[i + k + length / 2] = ui - vi
                }
            }
            length = length shl 1
        }
    }
}
