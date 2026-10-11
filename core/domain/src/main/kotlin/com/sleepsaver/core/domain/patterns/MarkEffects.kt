package com.sleepsaver.core.domain.patterns

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.model.Night
import java.time.LocalDate
import kotlin.math.abs
import kotlin.random.Random

/** What a mark is compared on. */
enum class EffectMetric { TOTAL_SLEEP, AWAKE, MORNING_FEEL }

/** "Clear pattern" / "Possible pattern" / no pattern / "Not enough nights (4 of 6)". */
enum class PatternLabel { CLEAR, POSSIBLE, NO_PATTERN, NOT_ENOUGH }

/** One mark × metric of "What went with your nights". Every insight ends with "This shows what went together, not what caused it." */
data class MarkEffect(
    val mark: Mark,
    val metric: EffectMetric,
    val label: PatternLabel,
    val withNights: Int,
    val withoutNights: Int,
    /** median(with) − median(without): minutes for sleep/awake, feel levels for MORNING_FEEL; null when NOT_ENOUGH. */
    val difference: Double?,
    val low: Double?,
    val high: Double?,
)

/**
 * Marks statistics: median difference with a seeded 90 % bootstrap interval (bootstrapResamples, deterministic),
 * at least marksMinPerSide nights per side, labelled by whether the interval excludes 0 (§2.8).
 *
 *  - One result per [Mark] × [EffectMetric]. Considered nights: present, wakeDate in (asOf − marksLookbackDays, asOf]
 *    and ≥ the mark's first date (no first date → NOT_ENOUGH 0/0). Values: TOTAL_SLEEP = main + extra minutes (naps
 *    excluded), AWAKE = awakeInMain minutes, MORNING_FEEL = the feel level (nights with a feel only).
 *  - with = nights carrying the mark, without = the rest; fewer than marksMinPerSide on either side → NOT_ENOUGH.
 *  - Bootstrap: Random(bootstrapSeed + 31·mark.ordinal + metric.ordinal); each resample draws both groups with
 *    replacement (with first) and takes median(with*) − median(without*); low/high = type-7 quantiles
 *    bootstrapLow/bootstrapHigh of the sorted statistics.
 *  - CLEAR when the interval excludes 0 and its bound nearest 0 is at least clearSleep / clearAwake / clearFeel away;
 *    POSSIBLE when it excludes 0 by less; else NO_PATTERN.
 *  - Sorted CLEAR, POSSIBLE, NO_PATTERN, NOT_ENOUGH; within a label by |difference| descending.
 *
 * Example: nights with Alcohol sleep a median 6 h 10 m against 7 h 25 m without, interval −95..−55 min → CLEAR.
 */
class MarkEffectsCalculator(private val config: SleepConfig = SleepConfig.DEFAULT) {

    /** [marks] includes derived marks; [firstMarkDate] = the first wake date each mark could have been logged. */
    fun effects(
        nights: List<Night>,
        marks: Map<LocalDate, Set<Mark>>,
        feel: Map<LocalDate, MorningFeel>,
        firstMarkDate: Map<Mark, LocalDate>,
        asOf: LocalDate,
    ): List<MarkEffect> {
        val from = asOf.minusDays(config.journal.marksLookbackDays)
        val window = nights
            .filter { it.isPresent && it.wakeDate.isAfter(from) && !it.wakeDate.isAfter(asOf) }
            .associateBy { it.wakeDate }
            .values
            .sortedBy { it.wakeDate }
        val effects = Mark.entries.flatMap { mark ->
            val first = firstMarkDate[mark]
            EffectMetric.entries.map { metric ->
                if (first == null) {
                    notEnough(mark, metric, 0, 0)
                } else {
                    val withValues = ArrayList<Double>()
                    val withoutValues = ArrayList<Double>()
                    for (night in window) {
                        if (night.wakeDate.isBefore(first)) continue
                        val value = valueOf(night, metric, feel) ?: continue
                        if (marks[night.wakeDate]?.contains(mark) == true) withValues += value else withoutValues += value
                    }
                    effect(mark, metric, withValues, withoutValues)
                }
            }
        }
        return effects.sortedWith(compareBy<MarkEffect> { it.label.ordinal }.thenByDescending { abs(it.difference ?: 0.0) })
    }

    private fun valueOf(night: Night, metric: EffectMetric, feel: Map<LocalDate, MorningFeel>): Double? = when (metric) {
        EffectMetric.TOTAL_SLEEP -> (night.mainSleep + night.extraSleep).minutesExact()
        EffectMetric.AWAKE -> night.awakeInMain.minutesExact()
        EffectMetric.MORNING_FEEL -> feel[night.wakeDate]?.level?.toDouble()
    }

    private fun effect(mark: Mark, metric: EffectMetric, with: List<Double>, without: List<Double>): MarkEffect {
        val journal = config.journal
        if (minOf(with.size, without.size) < journal.marksMinPerSide) return notEnough(mark, metric, with.size, without.size)

        val random = Random(journal.bootstrapSeed + SEED_STRIDE * mark.ordinal + metric.ordinal)
        val withSample = DoubleArray(with.size)
        val withoutSample = DoubleArray(without.size)
        val statistics = DoubleArray(journal.bootstrapResamples)
        for (r in statistics.indices) {
            for (i in withSample.indices) withSample[i] = with[random.nextInt(with.size)]
            for (i in withoutSample.indices) withoutSample[i] = without[random.nextInt(without.size)]
            statistics[r] = sortedMedian(withSample) - sortedMedian(withoutSample)
        }
        statistics.sort()
        val sorted = statistics.asList()
        val low = quantile7(sorted, journal.bootstrapLow)
        val high = quantile7(sorted, journal.bootstrapHigh)
        val clear = when (metric) {
            EffectMetric.TOTAL_SLEEP -> journal.clearSleep.minutesExact()
            EffectMetric.AWAKE -> journal.clearAwake.minutesExact()
            EffectMetric.MORNING_FEEL -> journal.clearFeel
        }
        val nearBound = when {
            low > 0 -> low
            high < 0 -> -high
            else -> null
        }
        val label = when {
            nearBound == null -> PatternLabel.NO_PATTERN
            nearBound >= clear -> PatternLabel.CLEAR
            else -> PatternLabel.POSSIBLE
        }
        return MarkEffect(
            mark = mark,
            metric = metric,
            label = label,
            withNights = with.size,
            withoutNights = without.size,
            difference = median(with) - median(without),
            low = low,
            high = high,
        )
    }

    private fun notEnough(mark: Mark, metric: EffectMetric, with: Int, without: Int) =
        MarkEffect(mark, metric, PatternLabel.NOT_ENOUGH, with, without, difference = null, low = null, high = null)

    /** Sorts [values] in place and returns their type-7 median. */
    private fun sortedMedian(values: DoubleArray): Double {
        values.sort()
        return quantile7(values.asList(), 0.5)
    }

    private companion object {
        /** Seed = bootstrapSeed + 31·mark.ordinal + metric.ordinal, so every mark × metric has its own stream. */
        const val SEED_STRIDE: Long = 31L
    }
}
