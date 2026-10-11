package com.sleepsaver.core.alarm.synth

import com.sleepsaver.core.alarm.AlarmPlatform
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.config.HushConfig
import com.sleepsaver.core.domain.config.SleepConfig
import java.time.Duration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Hush noise, pre-rendered as one seamless loop (SPEC_V2 §4.8). Pure and deterministic for a seed.
 *
 *  - WHITE: Gaussian samples (Box–Muller).
 *  - PINK: white through Paul Kellet's filter (about −3 dB per octave).
 *  - BROWN: white through a leaky integrator (leak [AlarmPlatform.BROWN_LEAK]) and a DC-blocking high-pass at
 *    [AlarmPlatform.BROWN_HIGH_PASS_HZ] (about −6 dB per octave above its corner).
 *
 * Each loop has zero mean, an RMS of [AlarmPlatform.NOISE_RMS] (less when that would push a peak past 0.95), and its
 * end flows into its start through an equal-power crossfade of [AlarmPlatform.NOISE_SEAM_MS].
 */
internal object NoiseSynth {
    private const val PEAK_LIMIT = 0.95

    fun render(
        color: HushColor,
        length: Duration = SleepConfig.DEFAULT.hush.loopLength,
        sampleRate: Int = SleepConfig.DEFAULT.hush.sampleRate,
        seed: Long = AlarmPlatform.NOISE_SEED,
    ): FloatArray {
        val n = (length.toMillis() * sampleRate / 1000).toInt()
        val seam = AlarmPlatform.NOISE_SEAM_MS * sampleRate / 1000
        val raw = colored(color, n + seam, sampleRate, Random(seed))

        // Equal-power crossfade: the loop's first [seam] samples fade from the raw tail (which continues the loop's last
        // sample) into the raw head, so loop[n − 1] → loop[0] is two neighbouring raw samples.
        val loop = DoubleArray(n)
        for (i in 0 until n) {
            loop[i] = if (i < seam) {
                val theta = PI / 2 * i / seam
                raw[n + i] * cos(theta) + raw[i] * sin(theta)
            } else {
                raw[i]
            }
        }
        val mean = loop.average()
        for (i in loop.indices) loop[i] -= mean
        val rms = sqrt(loop.sumOf { it * it } / n)
        val peak = loop.maxOf { abs(it) }
        val scale = if (rms <= 0.0) 0.0 else minOf(AlarmPlatform.NOISE_RMS / rms, PEAK_LIMIT / peak)
        return FloatArray(n) { (loop[it] * scale).toFloat() }
    }

    private fun colored(color: HushColor, count: Int, sampleRate: Int, random: Random): DoubleArray {
        val white = DoubleArray(count) { gaussian(random) }
        return when (color) {
            HushColor.WHITE -> white
            HushColor.PINK -> pink(white)
            HushColor.BROWN -> brown(white, sampleRate)
        }
    }

    /** Box–Muller: one standard normal sample per call (the second of each pair is dropped for simplicity). */
    private fun gaussian(random: Random): Double {
        var u1 = random.nextDouble()
        while (u1 <= 1e-12) u1 = random.nextDouble()
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2 * PI * u2)
    }

    /** Paul Kellet's refined pink filter. */
    private fun pink(white: DoubleArray): DoubleArray {
        var b0 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        var b3 = 0.0
        var b4 = 0.0
        var b5 = 0.0
        var b6 = 0.0
        return DoubleArray(white.size) { i ->
            val w = white[i]
            b0 = 0.99886 * b0 + w * 0.0555179
            b1 = 0.99332 * b1 + w * 0.0750759
            b2 = 0.96900 * b2 + w * 0.1538520
            b3 = 0.86650 * b3 + w * 0.3104856
            b4 = 0.55000 * b4 + w * 0.5329522
            b5 = -0.7616 * b5 - w * 0.0168980
            val out = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362
            b6 = w * 0.115926
            out
        }
    }

    /** Leaky integrator, then a one-pole DC blocker. */
    private fun brown(white: DoubleArray, sampleRate: Int): DoubleArray {
        val leak = AlarmPlatform.BROWN_LEAK
        val r = 1 - 2 * PI * AlarmPlatform.BROWN_HIGH_PASS_HZ / sampleRate
        var integrated = 0.0
        var previousIn = 0.0
        var previousOut = 0.0
        return DoubleArray(white.size) { i ->
            integrated = (integrated + leak * white[i]) / (1 + leak)
            val out = integrated - previousIn + r * previousOut
            previousIn = integrated
            previousOut = out
            out
        }
    }
}

/**
 * The slow random gain drift applied to Hush at playback: ±driftDb along a sine whose period is drawn anew in
 * [driftPeriodMin, driftPeriodMax] each cycle, so the noise never sounds like a short loop. Deterministic for a seed.
 */
internal class NoiseDrift(private val config: HushConfig = SleepConfig.DEFAULT.hush, seed: Long = AlarmPlatform.NOISE_SEED) {
    private val random = Random(seed)
    private var cycleStartMs = 0L
    private var periodMs = nextPeriod()

    /** The linear gain factor [elapsedMs] after playback started (call with non-decreasing times). */
    fun gain(elapsedMs: Long): Float {
        while (elapsedMs - cycleStartMs >= periodMs) {
            cycleStartMs += periodMs
            periodMs = nextPeriod()
        }
        val phase = 2 * PI * (elapsedMs - cycleStartMs) / periodMs
        val db = config.driftDb * sin(phase)
        return 10.0.pow(db / 20).toFloat()
    }

    private fun nextPeriod(): Long {
        val min = config.driftPeriodMin.toMillis()
        val max = config.driftPeriodMax.toMillis()
        return if (max <= min) min.coerceAtLeast(1) else min + random.nextLong(max - min + 1)
    }
}
