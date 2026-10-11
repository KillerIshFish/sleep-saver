package com.sleepsaver.core.domain.patterns

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.testing.Fx
import java.time.LocalDate
import kotlin.math.abs
import org.junit.jupiter.api.Test

class MarkEffectsTest {

    private val calculator = MarkEffectsCalculator()

    /** Sunday 2026-10-11. */
    private val asOf = LocalDate.of(2026, 10, 11)

    /** 40 wake dates ending on [asOf], oldest first. */
    private val dates: List<LocalDate> = PatternFx.dates(40, asOf)

    /** Every mark could be logged from the first date. */
    private val allFromStart: Map<Mark, LocalDate> = Mark.entries.associateWith { dates.first() }

    /** Alcohol on every 4th night (10 nights): about 6 h asleep against 7.5 h without it; always 20 min awake. */
    private val alcoholDates: List<LocalDate> = dates.filterIndexed { i, _ -> i % 4 == 0 }
    private val alcoholNights: List<Night> = dates.mapIndexed { i, date ->
        val asleep = if (date in alcoholDates) 360L + (i % 3) * 5 else 442L + (i % 5) * 4
        PatternFx.night(date, asleep = asleep, awake = 20)
    }
    private val alcoholMarks = PatternFx.marked(Mark.ALCOHOL, alcoholDates)

    private fun List<MarkEffect>.of(mark: Mark, metric: EffectMetric): MarkEffect = single { it.mark == mark && it.metric == metric }

    @Test
    fun `a strong effect is a clear pattern`() {
        val effects = calculator.effects(alcoholNights, alcoholMarks, emptyMap(), allFromStart, asOf)

        val sleep = effects.of(Mark.ALCOHOL, EffectMetric.TOTAL_SLEEP)
        assertThat(sleep.label).isEqualTo(PatternLabel.CLEAR)
        assertThat(sleep.withNights).isEqualTo(10)
        assertThat(sleep.withoutNights).isEqualTo(30)
        assertThat(sleep.difference).isWithin(1e-9).of(365.0 - 450.0)
        val low = checkNotNull(sleep.low)
        val high = checkNotNull(sleep.high)
        assertThat(high).isAtMost(-15.0)
        assertThat(low).isAtMost(high)
        // Awake time is the same either way.
        assertThat(effects.of(Mark.ALCOHOL, EffectMetric.AWAKE).label).isEqualTo(PatternLabel.NO_PATTERN)
        // No feel answers at all.
        assertThat(effects.of(Mark.ALCOHOL, EffectMetric.MORNING_FEEL))
            .isEqualTo(MarkEffect(Mark.ALCOHOL, EffectMetric.MORNING_FEEL, PatternLabel.NOT_ENOUGH, 0, 0, null, null, null))
    }

    @Test
    fun `the same inputs give the same intervals`() {
        val first = calculator.effects(alcoholNights, alcoholMarks, emptyMap(), allFromStart, asOf)
        val second = MarkEffectsCalculator().effects(alcoholNights, alcoholMarks, emptyMap(), allFromStart, asOf)

        assertThat(second).isEqualTo(first)
        // Pinned: kotlin.random.Random(20261010 + 31·ALCOHOL.ordinal + TOTAL_SLEEP.ordinal), 1000 resamples.
        val sleep = first.of(Mark.ALCOHOL, EffectMetric.TOTAL_SLEEP)
        assertThat(listOf(sleep.low, sleep.high)).containsExactly(PINNED_LOW, PINNED_HIGH).inOrder()
    }

    @Test
    fun `a constant gap gives a point interval`() {
        val nights = dates.map { PatternFx.night(it, asleep = if (it in alcoholDates) 360 else 450, awake = if (it in alcoholDates) 23 else 20) }

        val effects = calculator.effects(nights, alcoholMarks, emptyMap(), allFromStart, asOf)

        assertThat(effects.of(Mark.ALCOHOL, EffectMetric.TOTAL_SLEEP))
            .isEqualTo(MarkEffect(Mark.ALCOHOL, EffectMetric.TOTAL_SLEEP, PatternLabel.CLEAR, 10, 30, -90.0, -90.0, -90.0))
        // 3 min more awake: the interval excludes 0 but stays under the 5-min clear threshold.
        assertThat(effects.of(Mark.ALCOHOL, EffectMetric.AWAKE))
            .isEqualTo(MarkEffect(Mark.ALCOHOL, EffectMetric.AWAKE, PatternLabel.POSSIBLE, 10, 30, 3.0, 3.0, 3.0))
    }

    @Test
    fun `no effect is no pattern`() {
        // Stress on every other night; both sides cycle through the same six values.
        val stressDates = dates.filterIndexed { i, _ -> i % 2 == 0 }
        val nights = dates.mapIndexed { i, date -> PatternFx.night(date, asleep = 400L + ((i / 2) % 6) * 10, awake = 20) }

        val sleep = calculator.effects(nights, PatternFx.marked(Mark.STRESS, stressDates), emptyMap(), allFromStart, asOf)
            .of(Mark.STRESS, EffectMetric.TOTAL_SLEEP)

        assertThat(sleep.label).isEqualTo(PatternLabel.NO_PATTERN)
        assertThat(checkNotNull(sleep.low)).isLessThan(0.0)
        assertThat(checkNotNull(sleep.high)).isGreaterThan(0.0)
    }

    @Test
    fun `each side needs six nights`() {
        val five = PatternFx.marked(Mark.LATE_MEAL, alcoholDates.take(5))
        val six = PatternFx.marked(Mark.LATE_MEAL, alcoholDates.take(6))

        val notEnough = calculator.effects(alcoholNights, five, emptyMap(), allFromStart, asOf).of(Mark.LATE_MEAL, EffectMetric.TOTAL_SLEEP)
        val enough = calculator.effects(alcoholNights, six, emptyMap(), allFromStart, asOf).of(Mark.LATE_MEAL, EffectMetric.TOTAL_SLEEP)

        assertThat(notEnough).isEqualTo(MarkEffect(Mark.LATE_MEAL, EffectMetric.TOTAL_SLEEP, PatternLabel.NOT_ENOUGH, 5, 35, null, null, null))
        assertThat(enough.label).isNotEqualTo(PatternLabel.NOT_ENOUGH)
        assertThat(enough.withNights).isEqualTo(6)

        // The other side counts too: a mark on all but five nights.
        val almostAlways = PatternFx.marked(Mark.LATE_MEAL, dates.drop(5))
        assertThat(calculator.effects(alcoholNights, almostAlways, emptyMap(), allFromStart, asOf).of(Mark.LATE_MEAL, EffectMetric.AWAKE).label)
            .isEqualTo(PatternLabel.NOT_ENOUGH)
    }

    @Test
    fun `nights before a mark's first date are left out`() {
        val first = dates[20]

        val effects = calculator.effects(alcoholNights, alcoholMarks, emptyMap(), mapOf(Mark.ALCOHOL to first), asOf)

        val sleep = effects.of(Mark.ALCOHOL, EffectMetric.TOTAL_SLEEP)
        assertThat(sleep.withNights + sleep.withoutNights).isEqualTo(20)
        assertThat(sleep.withNights).isEqualTo(alcoholDates.count { !it.isBefore(first) })
        // A mark with no first date can't be compared at all.
        assertThat(effects.of(Mark.STRESS, EffectMetric.AWAKE))
            .isEqualTo(MarkEffect(Mark.STRESS, EffectMetric.AWAKE, PatternLabel.NOT_ENOUGH, 0, 0, null, null, null))
    }

    @Test
    fun `only the lookback window and present nights count`() {
        val old = PatternFx.dates(10, asOf.minusDays(90)).map { PatternFx.night(it, asleep = 200) }
        val absent = Fx.night(asOf.plusDays(1)).copy(main = null)
        val firstOfAll = Mark.entries.associateWith { old.first().wakeDate }

        val sleep = calculator.effects(old + alcoholNights + absent, alcoholMarks, emptyMap(), firstOfAll, asOf)
            .of(Mark.ALCOHOL, EffectMetric.TOTAL_SLEEP)

        assertThat(sleep.withNights + sleep.withoutNights).isEqualTo(40)
    }

    @Test
    fun `feel compares only nights with a feel`() {
        // Feel answered on the last 15 mornings only; Alcohol on every other one of them (and on older nights too).
        val feelDates = dates.takeLast(15)
        val marked = feelDates.filterIndexed { i, _ -> i % 2 == 0 }
        val marks = PatternFx.marked(Mark.ALCOHOL, marked + dates.take(10))
        val feel = feelDates.associateWith { if (it in marked) MorningFeel.SLOW else MorningFeel.CLEAR }

        val effect = calculator.effects(alcoholNights, marks, feel, allFromStart, asOf).of(Mark.ALCOHOL, EffectMetric.MORNING_FEEL)

        assertThat(effect.withNights).isEqualTo(8)
        assertThat(effect.withoutNights).isEqualTo(7)
        assertThat(effect.difference).isEqualTo(-2.0)
        assertThat(effect.label).isEqualTo(PatternLabel.CLEAR)
    }

    @Test
    fun `total sleep counts extra segments but not naps`() {
        val nights = dates.map { date ->
            if (date in alcoholDates) {
                PatternFx.withNap(PatternFx.night(date, asleep = 360, extra = 60), Fx.dt(date, "15:00"), Fx.dt(date, "16:30"))
            } else {
                PatternFx.night(date, asleep = 420)
            }
        }

        val sleep = calculator.effects(nights, alcoholMarks, emptyMap(), allFromStart, asOf).of(Mark.ALCOHOL, EffectMetric.TOTAL_SLEEP)

        assertThat(sleep.difference).isEqualTo(0.0)
        assertThat(sleep.label).isEqualTo(PatternLabel.NO_PATTERN)
    }

    @Test
    fun `results run clear, possible, no pattern, not enough, larger differences first`() {
        // Late meal on six nights (30 min short and 2 min more awake), Alcohol on ten (1.5 h short).
        val lateMealDates = dates.filterIndexed { i, _ -> i % 4 == 1 && i < 24 }
        val nights = dates.map { date ->
            when (date) {
                in alcoholDates -> PatternFx.night(date, asleep = 360, awake = 20)
                in lateMealDates -> PatternFx.night(date, asleep = 420, awake = 22)
                else -> PatternFx.night(date, asleep = 450, awake = 20)
            }
        }
        val marks = PatternFx.merge(alcoholMarks, PatternFx.marked(Mark.LATE_MEAL, lateMealDates))

        val effects = calculator.effects(nights, marks, emptyMap(), allFromStart, asOf)

        assertThat(effects).hasSize(Mark.entries.size * EffectMetric.entries.size)
        assertThat(effects.map { it.label }).isInOrder()
        val clear = effects.filter { it.label == PatternLabel.CLEAR }
        assertThat(clear.map { it.mark to it.metric })
            .containsExactly(Mark.ALCOHOL to EffectMetric.TOTAL_SLEEP, Mark.LATE_MEAL to EffectMetric.TOTAL_SLEEP)
            .inOrder()
        for (label in PatternLabel.entries) {
            assertThat(effects.filter { it.label == label }.map { abs(it.difference ?: 0.0) }).isInOrder(Comparator.reverseOrder<Double>())
        }
    }

    private companion object {
        const val PINNED_LOW = -91.5
        const val PINNED_HIGH = -80.0
    }
}
