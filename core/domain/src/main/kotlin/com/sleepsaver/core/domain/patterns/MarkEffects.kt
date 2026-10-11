package com.sleepsaver.core.domain.patterns

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.model.Night
import java.time.LocalDate

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
 */
class MarkEffectsCalculator(private val config: SleepConfig = SleepConfig.DEFAULT) {
    /** [marks] includes derived marks; [firstMarkDate] = the first wake date each mark could have been logged. */
    fun effects(
        nights: List<Night>,
        marks: Map<LocalDate, Set<Mark>>,
        feel: Map<LocalDate, MorningFeel>,
        firstMarkDate: Map<Mark, LocalDate>,
        asOf: LocalDate,
    ): List<MarkEffect> = TODO("B2: MarkEffectsCalculator.effects (§2.8)")
}
