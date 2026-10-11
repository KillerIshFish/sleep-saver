package com.sleepsaver.feature.progress

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.patterns.MarkEffect
import com.sleepsaver.core.domain.patterns.PatternLabel

/**
 * "What went with your nights" on the Quality tab, sorted out of the mark effects. Night steps done is left out
 * (Night steps shows its own comparison); the derived Hush on and Anchor dropped stay in.
 *
 * Example: Alcohol × total sleep CLEAR, Late caffeine × awake POSSIBLE and Stress with 4 nights so far give two
 * [shown] rows and [waiting] = Stress, 4 of 6.
 */
data class MarkPatterns(
    /** CLEAR and POSSIBLE rows, in the calculator's order (clear first, bigger differences first). */
    val shown: List<MarkEffect> = emptyList(),
    /** Marks with some nights but not yet [MarkProgress.needed] on each side, most nights first. */
    val waiting: List<MarkProgress> = emptyList(),
    /** Some mark already has enough nights on both sides, even if nothing stands out. */
    val anyMeasured: Boolean = false,
) {
    companion object {
        /** Marks Progress never lists. */
        val hidden: Set<Mark> = setOf(Mark.NIGHT_STEPS_DONE)

        fun of(effects: List<MarkEffect>, config: SleepConfig = SleepConfig.DEFAULT): MarkPatterns {
            val visible = effects.filter { it.mark !in hidden }
            val waiting = visible
                .groupBy { it.mark }
                .filterValues { rows -> rows.all { it.label == PatternLabel.NOT_ENOUGH } && rows.any { it.withNights > 0 } }
                .map { (mark, rows) ->
                    MarkProgress(mark, rows.maxOf { minOf(it.withNights, it.withoutNights) }, config.journal.marksMinPerSide)
                }
                .sortedWith(compareByDescending<MarkProgress> { it.nights }.thenBy { it.mark.ordinal })
            return MarkPatterns(
                shown = visible.filter { it.label == PatternLabel.CLEAR || it.label == PatternLabel.POSSIBLE },
                waiting = waiting,
                anyMeasured = visible.any { it.label != PatternLabel.NOT_ENOUGH },
            )
        }
    }
}

/** "Stress: 4 of 6 nights": the nights a mark has on its shorter side, and how many each side needs. */
data class MarkProgress(val mark: Mark, val nights: Int, val needed: Int)
