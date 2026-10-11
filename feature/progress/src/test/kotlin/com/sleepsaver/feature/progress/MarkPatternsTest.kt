package com.sleepsaver.feature.progress

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.patterns.EffectMetric
import com.sleepsaver.core.domain.patterns.MarkEffect
import com.sleepsaver.core.domain.patterns.PatternLabel
import com.sleepsaver.core.testing.PlanSamples
import org.junit.Test

class MarkPatternsTest {

    private fun notEnough(mark: Mark, metric: EffectMetric, with: Int, without: Int) =
        MarkEffect(mark, metric, PatternLabel.NOT_ENOUGH, with, without, null, null, null)

    private fun noPattern(mark: Mark, metric: EffectMetric) =
        MarkEffect(mark, metric, PatternLabel.NO_PATTERN, 8, 30, 2.0, -6.0, 9.0)

    @Test
    fun `clear and possible rows are shown in order, never Night steps done`() {
        val steps = MarkEffect(Mark.NIGHT_STEPS_DONE, EffectMetric.TOTAL_SLEEP, PatternLabel.CLEAR, 12, 30, 20.0, 9.0, 31.0)

        val patterns = MarkPatterns.of(listOf(steps) + PlanSamples.markEffects())

        assertThat(patterns.shown.map { it.mark to it.label })
            .containsExactly(Mark.ALCOHOL to PatternLabel.CLEAR, Mark.LATE_CAFFEINE to PatternLabel.POSSIBLE).inOrder()
        assertThat(patterns.anyMeasured).isTrue()
    }

    @Test
    fun `a mark waits until every metric has enough nights, counted on its shorter side`() {
        val effects = listOf(
            notEnough(Mark.STRESS, EffectMetric.TOTAL_SLEEP, 4, 40),
            notEnough(Mark.STRESS, EffectMetric.AWAKE, 4, 40),
            notEnough(Mark.STRESS, EffectMetric.MORNING_FEEL, 2, 30),
            notEnough(Mark.SCREENS_IN_BED, EffectMetric.TOTAL_SLEEP, 41, 3),
            // Some nights measured on sleep: not "waiting", even though feel isn't there yet.
            noPattern(Mark.ALCOHOL, EffectMetric.TOTAL_SLEEP),
            notEnough(Mark.ALCOHOL, EffectMetric.MORNING_FEEL, 3, 20),
            // Never used: not listed at all.
            notEnough(Mark.UNWELL, EffectMetric.TOTAL_SLEEP, 0, 44),
            notEnough(Mark.NIGHT_STEPS_DONE, EffectMetric.TOTAL_SLEEP, 5, 39),
        )

        val patterns = MarkPatterns.of(effects)

        assertThat(patterns.shown).isEmpty()
        assertThat(patterns.waiting).containsExactly(
            MarkProgress(Mark.STRESS, 4, 6),
            // On 41 nights, missing on only 3: the short side is the nights without it.
            MarkProgress(Mark.SCREENS_IN_BED, 3, 6, shortOnNightsWith = false),
        ).inOrder()
        assertThat(patterns.anyMeasured).isTrue()
    }

    @Test
    fun `a pattern whose difference would read about 0 isn't shown`() {
        val zeroFeel = MarkEffect(Mark.STRESS, EffectMetric.MORNING_FEEL, PatternLabel.POSSIBLE, 8, 30, 0.0, -0.5, -0.1)
        val tinySleep = MarkEffect(Mark.ALCOHOL, EffectMetric.TOTAL_SLEEP, PatternLabel.CLEAR, 8, 30, -0.4, -9.0, -0.2)
        val halfStep = MarkEffect(Mark.LATE_MEAL, EffectMetric.MORNING_FEEL, PatternLabel.POSSIBLE, 8, 30, -0.5, -1.0, -0.1)

        val patterns = MarkPatterns.of(listOf(zeroFeel, tinySleep, halfStep))

        assertThat(patterns.shown).containsExactly(halfStep)
        assertThat(patterns.anyMeasured).isTrue()
    }

    @Test
    fun `no effects at all is the empty card`() {
        val patterns = MarkPatterns.of(emptyList())

        assertThat(patterns).isEqualTo(MarkPatterns())
        assertThat(patterns.anyMeasured).isFalse()
    }
}
