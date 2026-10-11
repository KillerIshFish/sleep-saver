package com.sleepsaver.core.domain.alarm

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.PhaseType
import java.time.Duration
import java.time.Instant

/** The timing hint shown with a catnap. */
enum class CatnapTiming { IN_AFTERNOON_DIP, FINE, CLOSE_TO_BEDTIME, EARLY }

/** A catnap as planned at [start]: it rings at [ringsAt] (start + time to fall asleep + [length]). */
data class CatnapPlan(
    val start: Instant,
    val ringsAt: Instant,
    val length: Duration,
    val timing: CatnapTiming,
    /** 30-min naps and longer may leave you groggy. */
    val mayFeelGroggy: Boolean,
    /** Before the caffeine cutoff and short enough for the coffee-nap hint. */
    val coffeeHint: Boolean,
)

/**
 * Plans a Catnap (DECISIONS A11). Example: a 20-min catnap at 14:00 rings at 14:25 and, inside the afternoon dip,
 * reads IN_AFTERNOON_DIP.
 *
 *  - ringsAt = now + catnap.fallAsleep + length.
 *  - timing, first match wins: ringsAt > targetBedtime − latestBeforeBedtime → CLOSE_TO_BEDTIME; the AFTERNOON_DIP
 *    phase contains now → IN_AFTERNOON_DIP; now < (actualWake ?: typicalWake) + earliestAfterWake → EARLY; else FINE.
 *    A null schedule gives FINE.
 *  - mayFeelGroggy = length ≥ groggyFrom; coffeeHint = now < caffeineCutoff and length ≤ coffeeMaxLength.
 */
class CatnapPlanner(private val config: SleepConfig = SleepConfig.DEFAULT) {

    /** [caffeineCutoff] = today's CAFFEINE_CUTOFF habit time (null = unknown → no coffee hint). */
    fun plan(now: Instant, length: Duration, schedule: EnergySchedule?, caffeineCutoff: Instant?): CatnapPlan {
        val catnap = config.catnap
        val ringsAt = now + catnap.fallAsleep + length
        val timing = when {
            schedule == null -> CatnapTiming.FINE
            ringsAt > schedule.targetBedtime - catnap.latestBeforeBedtime -> CatnapTiming.CLOSE_TO_BEDTIME
            schedule.phase(PhaseType.AFTERNOON_DIP)?.contains(now) == true -> CatnapTiming.IN_AFTERNOON_DIP
            now < (schedule.actualWake ?: schedule.typicalWake.toInstant()) + catnap.earliestAfterWake -> CatnapTiming.EARLY
            else -> CatnapTiming.FINE
        }
        return CatnapPlan(
            start = now,
            ringsAt = ringsAt,
            length = length,
            timing = timing,
            mayFeelGroggy = length >= catnap.groggyFrom,
            coffeeHint = caffeineCutoff != null && now < caffeineCutoff && length <= catnap.coffeeMaxLength,
        )
    }
}
