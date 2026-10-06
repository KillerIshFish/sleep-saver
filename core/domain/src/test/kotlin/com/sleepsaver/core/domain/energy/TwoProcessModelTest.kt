package com.sleepsaver.core.domain.energy

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.TimeSpan
import java.time.Duration
import java.time.Instant
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import org.junit.jupiter.api.Test

class TwoProcessModelTest {

    private val model = TwoProcessModel()
    private val t0 = Instant.parse("2026-09-27T07:00:00Z")

    private fun at(hours: Double): Instant = t0.plusSeconds((hours * 3600).toLong())
    private fun span(fromHours: Double, toHours: Double) = TimeSpan(at(fromHours), at(toHours))

    @Test
    fun `S rises while awake`() {
        val s = model.homeostaticPressure(t0, at(10.0), emptyList())

        assertThat(s).isWithin(1e-9).of(1 - 0.5 * exp(-10 / 18.2))
    }

    @Test
    fun `S decays while asleep`() {
        val s = model.homeostaticPressure(t0, at(8.0), listOf(span(0.0, 8.0)))

        assertThat(s).isWithin(1e-9).of(0.5 * exp(-8 / 4.2))
    }

    @Test
    fun `S integrates awake and asleep pieces in order`() {
        // 16 h awake, then 8 h asleep.
        val s = model.homeostaticPressure(t0, at(24.0), listOf(span(16.0, 24.0)))
        val expected = (1 - 0.5 * exp(-16 / 18.2)) * exp(-8 / 4.2)

        assertThat(s).isWithin(1e-9).of(expected)
    }

    @Test
    fun `intervals are clipped to the integration range, unsorted and overlapping`() {
        // Asleep from 2 h before the seed until 3 h after it, then awake for 2 h.
        val clipped = model.homeostaticPressure(t0, at(5.0), listOf(span(1.0, 3.0), span(-2.0, 2.0), span(6.0, 9.0)))
        val expected = (1 - (1 - 0.5 * exp(-3 / 4.2)) * exp(-2 / 18.2))

        assertThat(clipped).isWithin(1e-9).of(expected)
    }

    @Test
    fun `S is the seed when the range is empty`() {
        assertThat(model.homeostaticPressure(t0, t0, listOf(span(-1.0, 1.0)))).isEqualTo(0.5)
    }

    @Test
    fun `steady 16 h awake and 8 h asleep reaches about 0_093 at wake`() {
        val wake = at(72.0)
        val nights = (0 until 3).map { span(16.0 + 24 * it, 24.0 + 24 * it) }

        val s = model.homeostaticPressure(wake.minus(Duration.ofHours(72)), wake, nights)

        val a = exp(-16 / 18.2)
        val b = exp(-8 / 4.2)
        assertThat(s).isWithin(0.002).of(b * (1 - a) / (1 - a * b))
        assertThat(s).isWithin(0.002).of(0.093)
    }

    @Test
    fun `24 h circadian term peaks 9_5 h after typical wake`() {
        val only24h = TwoProcessModel(SleepConfig(c12Amplitude = 0.0))
        val grid = (0..24 * 12).map { at(it / 12.0) }

        val peak = grid.maxBy { only24h.circadian(it, t0) }

        assertThat(peak).isEqualTo(at(9.5))
        assertThat(only24h.circadian(at(9.5), t0)).isWithin(1e-12).of(0.35)
        assertThat(only24h.circadian(at(21.5), t0)).isWithin(1e-12).of(-0.35)
    }

    @Test
    fun `circadian combines both harmonics`() {
        val c = model.circadian(at(3.0), t0)
        val expected = 0.35 * cos(2 * PI * (3.0 - 9.5) / 24) + 0.15 * cos(2 * PI * (3.0 - 2.0) / 12)

        assertThat(c).isWithin(1e-12).of(expected)
        assertThat(model.circadian(at(27.0), t0)).isWithin(1e-12).of(expected)
    }

    @Test
    fun `inertia strength grows with debt and is capped`() {
        assertThat(model.inertiaStrength(0.0)).isWithin(1e-12).of(0.15)
        assertThat(model.inertiaStrength(4.0)).isWithin(1e-12).of(0.30)
        assertThat(model.inertiaStrength(8.0)).isWithin(1e-12).of(0.45)
        assertThat(model.inertiaStrength(20.0)).isWithin(1e-12).of(0.45)
    }

    @Test
    fun `inertia is zero before waking and fades with a 30 minute time constant`() {
        assertThat(model.inertia(at(-0.1), t0, 0.0)).isEqualTo(0.0)
        assertThat(model.inertia(t0, t0, 0.0)).isWithin(1e-12).of(-0.15)
        assertThat(model.inertia(at(0.5), t0, 4.0)).isWithin(1e-12).of(-0.30 / E)
    }

    @Test
    fun `grogginess length follows ln of k over epsilon`() {
        assertThat(model.inertiaDurationMinutes(0.0)).isWithin(0.1).of(60.4)
        assertThat(model.inertiaDurationMinutes(4.0)).isWithin(1e-9).of(30 * ln(0.30 / 0.02))
        assertThat(model.inertiaDurationMinutes(100.0)).isWithin(1e-9).of(30 * ln(0.45 / 0.02))
        assertThat(TwoProcessModel(SleepConfig(inertiaEpsilon = 0.5)).inertiaDurationMinutes(0.0)).isEqualTo(0.0)
    }
}
