package com.sleepsaver.core.domain.alarm

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.alarm.AlarmFixtures.activityAt
import com.sleepsaver.core.domain.alarm.AlarmFixtures.at
import com.sleepsaver.core.domain.alarm.AlarmFixtures.record
import com.sleepsaver.core.domain.config.SleepConfig
import java.time.Duration
import org.junit.jupiter.api.Test

class MovementMeterTest {

    private val placement = SleepConfig.DEFAULT.surfacing.placementActiveActivity
    private val g = 9.81f

    /** The epoch the fixtures' motions happen in (they start 40 s into a recording that starts at 06:00). */
    private val moving = at("06:00:30")

    /** Feeds still samples at 25 Hz for the instants in [from, until] (ms after 06:00) and returns the closed epochs. */
    private fun MovementMeter.still(fromMs: Long, untilMs: Long): List<MovementEpoch> {
        val epochs = ArrayList<MovementEpoch>()
        var t = fromMs
        while (t <= untilMs) {
            epochs += add(at("06:00").plusMillis(t), 0f, 0f, g)
            t += 40
        }
        return epochs
    }

    // ---------------------------------------------------------------- Activity

    @Test
    fun `the same motion sampled at 10, 25 and 50 Hz reads within 5 percent`() {
        for (motion in listOf(AlarmFixtures.roll(), AlarmFixtures.tilt())) {
            val readings = listOf(10, 25, 50).map { hz -> checkNotNull(record(motion, hz).activityAt(moving)) }

            assertThat(readings.max() / readings.min()).isAtMost(1.05)
        }
    }

    @Test
    fun `a phone lying perfectly still reads zero`() {
        val epochs = record(AlarmFixtures.still)

        assertThat(epochs.activityAt(at("06:00"))).isEqualTo(0.0)
        assertThat(epochs.activityAt(moving)).isEqualTo(0.0)
    }

    @Test
    fun `sensor noise on a still phone reads below the placement threshold`() {
        val epochs = record(AlarmFixtures.noise(sd = 0.015), length = Duration.ofMinutes(5)).filter { it.activity != null }

        assertThat(epochs).hasSize(10)
        assertThat(epochs.mapNotNull { it.activity }.max()).isLessThan(placement)
    }

    @Test
    fun `a 2-s jolt reads above 20`() {
        assertThat(record(AlarmFixtures.jolt(amplitude = 0.3)).activityAt(moving)).isGreaterThan(20.0)
        // On one axis only it is still clearly a movement, not a phone on the nightstand.
        assertThat(record(AlarmFixtures.jolt(amplitude = 0.3, axes = 1)).activityAt(moving)).isGreaterThan(placement)
    }

    @Test
    fun `a pure tilt reads above 10 even though the vector length never changes`() {
        assertThat(record(AlarmFixtures.tilt()).activityAt(moving)).isGreaterThan(10.0)
    }

    // ---------------------------------------------------------------- Slots, gaps and order

    @Test
    fun `slots are aligned to the instant, not to the first sample`() {
        val meter = MovementMeter()
        meter.add(at("06:00:17"), 0f, 0f, g)
        val closed = meter.add(at("06:00:31"), 0f, 0f, g)

        assertThat(closed.map { it.start }).containsExactly(at("06:00"))
    }

    @Test
    fun `a gap closes the open slot and emits every skipped slot as missing`() {
        val meter = MovementMeter()
        val before = meter.still(0, 40_000) // 06:00:00 – 06:00:40
        val resumed = meter.still(130_000, 180_000) // 06:02:10 – 06:03:00
        val epochs = before + resumed + listOfNotNull(meter.flush())

        assertThat(epochs.map { it.start }).containsExactly(
            at("06:00"), at("06:00:30"), at("06:01"), at("06:01:30"), at("06:02"), at("06:02:30"), at("06:03"),
        ).inOrder()
        assertThat(epochs.map { it.activity != null }).containsExactly(true, false, false, false, true, true, false).inOrder()
        // The first sample after the gap returns the three slots it closed at once.
        assertThat(resumed.first().start).isEqualTo(at("06:00:30"))
    }

    @Test
    fun `out-of-order and repeated samples are dropped`() {
        val clean = MovementMeter().still(0, 40_000)
        val meter = MovementMeter()
        val noisy = ArrayList<MovementEpoch>()
        var t = 0L
        while (t <= 40_000) {
            noisy += meter.add(at("06:00").plusMillis(t), 0f, 0f, g)
            if (t == 10_000L) {
                assertThat(meter.add(at("06:00").plusMillis(t), 50f, 50f, 50f)).isEmpty() // same time
                assertThat(meter.add(at("06:00").plusMillis(t - 500), 50f, 50f, 50f)).isEmpty() // earlier
            }
            t += 40
        }

        assertThat(noisy).isEqualTo(clean)
        assertThat(noisy.single().activity).isEqualTo(0.0)
    }

    @Test
    fun `a sample with a NaN or infinite axis is dropped and the baseline survives`() {
        val clean = MovementMeter().still(0, 70_000)
        val meter = MovementMeter()
        val glitched = ArrayList<MovementEpoch>()
        var t = 0L
        while (t <= 70_000) {
            if (t == 10_000L) {
                assertThat(meter.add(at("06:00").plusMillis(t - 20), Float.NaN, 0f, g)).isEmpty()
                assertThat(meter.add(at("06:00").plusMillis(t - 10), 0f, Float.POSITIVE_INFINITY, g)).isEmpty()
            }
            glitched += meter.add(at("06:00").plusMillis(t), 0f, 0f, g)
            t += 40
        }

        assertThat(glitched).isEqualTo(clean)
        assertThat(glitched.map { it.activity }).containsExactly(0.0, 0.0).inOrder()
    }

    // ---------------------------------------------------------------- flush

    @Test
    fun `flush follows the coverage rule`() {
        val half = MovementMeter()
        half.still(0, 15_000) // Σ Δt = 15 s: exactly half the epoch

        assertThat(half.flush()).isEqualTo(MovementEpoch(at("06:00"), 0.0))
        assertThat(half.flush()).isNull()

        val less = MovementMeter()
        less.still(0, 14_960)

        assertThat(less.flush()).isEqualTo(MovementEpoch(at("06:00"), null))
        assertThat(MovementMeter().flush()).isNull()
    }

    @Test
    fun `a flushed slot is never emitted twice`() {
        val meter = MovementMeter()
        meter.still(0, 20_000)
        val flushed = meter.flush()
        val after = meter.still(20_040, 95_000) + listOfNotNull(meter.flush())

        assertThat(flushed?.start).isEqualTo(at("06:00"))
        assertThat(after.map { it.start }).containsExactly(at("06:00:30"), at("06:01"), at("06:01:30")).inOrder()
    }

    @Test
    fun `slots skipped after a flush come out as missing`() {
        val meter = MovementMeter()
        meter.still(0, 20_000)
        meter.flush()
        val after = meter.add(at("06:01:40"), 0f, 0f, g)

        assertThat(after).containsExactly(MovementEpoch(at("06:00:30"), null), MovementEpoch(at("06:01"), null)).inOrder()
    }
}
