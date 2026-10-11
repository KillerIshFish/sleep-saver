package com.sleepsaver.core.domain.config

import java.time.Duration

/**
 * Marks statistics, Morning feel, the odd-night prompt (docs/PLAN.md §3.14). Example: "Alcohol" needs at least
 * [marksMinPerSide] nights with it and without it before any pattern is shown.
 * (The tuning note's 7 days live in SurfacingConfig.tuningNoteDays, next to the tuning they describe.)
 */
data class JournalConfig(
    /** Marks can be set for wake dates in [today − (markEditDays − 1), today + 1]. */
    val markEditDays: Long = 7,
    val marksLookbackDays: Long = 90,
    val marksMinPerSide: Int = 6,
    /** Seeded bootstrap of the median difference: resamples, interval quantiles and seed (deterministic). */
    val bootstrapResamples: Int = 1000,
    val bootstrapLow: Double = 0.05,
    val bootstrapHigh: Double = 0.95,
    val bootstrapSeed: Long = 20_261_010L,
    /** "Clear pattern" when the interval's bound nearest zero is at least this far from zero. */
    val clearSleep: Duration = Duration.ofMinutes(15),
    val clearAwake: Duration = Duration.ofMinutes(5),
    val clearFeel: Double = 0.5,
    /** Odd night: compared with the median of the last [oddLookbackDays] (at least [oddMinNights] nights). */
    val oddLookbackDays: Long = 28,
    val oddMinNights: Int = 7,
    val oddTotal: Duration = Duration.ofMinutes(60),
    val oddAwake: Duration = Duration.ofMinutes(20),
    val oddOnset: Duration = Duration.ofMinutes(60),
    // --- B2 additions (append below this line only)
)
