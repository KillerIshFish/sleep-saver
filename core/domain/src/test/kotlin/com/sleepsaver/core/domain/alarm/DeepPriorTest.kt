package com.sleepsaver.core.domain.alarm

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepStageType.AWAKE
import com.sleepsaver.core.domain.model.SleepStageType.DEEP
import com.sleepsaver.core.domain.model.SleepStageType.LIGHT
import com.sleepsaver.core.domain.model.SleepStageType.REM
import com.sleepsaver.core.domain.model.SleepStageType.SLEEPING
import com.sleepsaver.core.domain.testing.Fx
import java.time.Duration
import java.time.LocalDate
import org.junit.jupiter.api.Test

/**
 * Fx.typicalNight (23:00–07:00): LIGHT 23:00–23:15, DEEP 23:15–00:00, … the last DEEP 04:10–04:20, then LIGHT and
 * REM only. With n nights a bin that was deep every night reads (n + 1) / (n + 2), one never deep 1 / (n + 2).
 */
class DeepPriorTest {

    private val estimator = DeepPriorEstimator()
    private val asOf = LocalDate.of(2026, 10, 8)

    private fun typical(count: Int, wokeUp: String = "07:00", finalAwake: Long = 0): List<Night> =
        (0 until count).map { Fx.typicalNight(asOf.minusDays(it.toLong()), wokeUp = wokeUp, finalAwake = finalAwake) }

    private fun minutes(m: Long): Duration = Duration.ofMinutes(m)

    // ---------------------------------------------------------------- Which nights

    @Test
    fun `fewer than 7 stage nights give no prior`() {
        assertThat(estimator.estimate(typical(6), asOf)).isNull()
        assertThat(estimator.estimate(typical(7), asOf)?.nightsUsed).isEqualTo(7)
    }

    @Test
    fun `nights without stages or outside the lookback don't count`() {
        val noise = listOf(
            Fx.night(asOf.minusDays(10)), // no stages
            Fx.stagedNight(asOf.minusDays(11), Fx.hypnogram(Fx.dt(asOf.minusDays(12), "23:00"), SLEEPING to 480L)),
            Fx.typicalNight(asOf.minusDays(90)), // just outside (asOf − 90, asOf]
            Fx.typicalNight(asOf.plusDays(1)), // after asOf
        )

        assertThat(estimator.estimate(typical(6) + noise, asOf)).isNull()
        assertThat(estimator.estimate(typical(6) + noise + Fx.typicalNight(asOf.minusDays(89)), asOf)?.nightsUsed)
            .isEqualTo(7)
    }

    @Test
    fun `only the most recent 60 nights count`() {
        // Five older nights that are deep from the start would lift the first bin if they were used.
        val old = (60 until 65).map { back ->
            val wakeDate = asOf.minusDays(back.toLong())
            Fx.stagedNight(wakeDate, Fx.hypnogram(Fx.dt(wakeDate.minusDays(1), "23:00"), DEEP to 480L))
        }

        val prior = checkNotNull(estimator.estimate(old + typical(60), asOf))

        assertThat(prior.nightsUsed).isEqualTo(60)
        assertThat(prior.sinceOnset[0]).isWithin(1e-12).of(1.0 / 62)
    }

    // ---------------------------------------------------------------- Bins

    @Test
    fun `deep sleep is likely early after onset and unlikely before the wake`() {
        val prior = checkNotNull(estimator.estimate(typical(7), asOf))

        assertThat(prior.bin).isEqualTo(minutes(10))
        assertThat(prior.sinceOnset).hasSize(72)
        assertThat(prior.beforeWake).hasSize(72)
        assertThat(prior.sinceOnset[0]).isWithin(1e-12).of(1.0 / 9) // 23:05 light
        for (k in 1..5) assertThat(prior.sinceOnset[k]).isWithin(1e-12).of(8.0 / 9) // 23:15 … 23:55 deep
        for (k in 0..15) assertThat(prior.beforeWake[k]).isWithin(1e-12).of(1.0 / 9) // 06:55 back to 04:25
        assertThat(prior.beforeWake[16]).isWithin(1e-12).of(8.0 / 9) // 04:15 deep
        // Bins past the night's length were never sampled: (0 + 1) / (0 + 2).
        assertThat(prior.sinceOnset[48]).isEqualTo(0.5)

        assertThat(prior.pDeepAt(minutes(30), minutes(450))).isWithin(1e-12).of(8.0 / 9)
        assertThat(prior.pDeepAt(minutes(470), minutes(10))).isWithin(1e-12).of(1.0 / 9)
    }

    @Test
    fun `the final awakening doesn't shift the before-wake bins`() {
        // The same sleep, once ending at 07:00 and once followed by 20 min awake until 07:20.
        val ended = checkNotNull(estimator.estimate(typical(7), asOf))
        val lingered = checkNotNull(estimator.estimate(typical(7, wokeUp = "07:20", finalAwake = 20), asOf))

        assertThat(lingered.beforeWake).isEqualTo(ended.beforeWake)
        assertThat(lingered.sinceOnset).isEqualTo(ended.sinceOnset)
    }

    @Test
    fun `unscored and uncovered midpoints are skipped, awake ones count as not deep`() {
        val nights = (0 until 7).map { back ->
            val wakeDate = asOf.minusDays(back.toLong())
            val evening = wakeDate.minusDays(1)
            val first = Fx.hypnogram(Fx.dt(evening, "23:00"), LIGHT to 30L, SLEEPING to 30L, DEEP to 30L)
            val afterGap = Fx.hypnogram(Fx.dt(wakeDate, "01:00"), AWAKE to 30L, REM to 30L)
            Fx.stagedNight(wakeDate, first + afterGap)
        }

        val prior = checkNotNull(estimator.estimate(nights, asOf))

        // Onset 23:00, end 02:00, half hours: LIGHT, SLEEPING, DEEP, no stage (00:30–01:00), AWAKE, REM, past the end.
        val expected = listOf(1.0 / 9, 0.5, 8.0 / 9, 0.5, 1.0 / 9, 1.0 / 9, 0.5)
        val sampled = (0 until 7).map { prior.sinceOnset[it * 3] }
        for (i in expected.indices) assertThat(sampled[i]).isWithin(1e-12).of(expected[i])
    }

    // ---------------------------------------------------------------- pDeepAt

    private val literal =
        DeepPrior(minutes(10), sinceOnset = listOf(0.8, 0.6, 0.4), beforeWake = listOf(0.1, 0.3), nightsUsed = 7)

    @Test
    fun `pDeepAt blends both sides by the onset weight`() {
        assertThat(literal.pDeepAt(minutes(5), minutes(15))).isWithin(1e-12).of(0.5 * 0.8 + 0.5 * 0.3)
        assertThat(literal.pDeepAt(minutes(10), minutes(0))).isWithin(1e-12).of(0.5 * 0.6 + 0.5 * 0.1)

        val onsetHeavy = SleepConfig.DEFAULT.copy(surfacing = SleepConfig.DEFAULT.surfacing.copy(priorOnsetWeight = 0.75))
        assertThat(literal.pDeepAt(minutes(5), minutes(15), onsetHeavy)).isWithin(1e-12).of(0.75 * 0.8 + 0.25 * 0.3)
    }

    @Test
    fun `pDeepAt uses one side when the other is out of range or negative`() {
        assertThat(literal.pDeepAt(minutes(30), minutes(15))).isWithin(1e-12).of(0.3)
        assertThat(literal.pDeepAt(minutes(-5), minutes(5))).isWithin(1e-12).of(0.1)
        assertThat(literal.pDeepAt(minutes(29), minutes(20))).isWithin(1e-12).of(0.4)
        assertThat(literal.pDeepAt(Duration.ofSeconds(-1), Duration.ofSeconds(-1))).isNull()
        assertThat(literal.pDeepAt(minutes(30), minutes(20))).isNull()
    }
}
