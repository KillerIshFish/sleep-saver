package com.sleepsaver.core.domain.config

import java.time.Duration

/**
 * Hush (docs/PLAN.md §3.16). Example: the default play lasts [defaultTimer] (45 min); the last [fadeOut] (5 min) of it
 * fades to silence.
 */
data class HushConfig(
    val timers: List<Duration> = listOf(
        Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofMinutes(45), Duration.ofMinutes(60), Duration.ofMinutes(90),
    ),
    val defaultTimer: Duration = Duration.ofMinutes(45),
    /** The notification's "+15 min". */
    val extendStep: Duration = Duration.ofMinutes(15),
    val fadeIn: Duration = Duration.ofSeconds(10),
    val fadeOut: Duration = Duration.ofMinutes(5),
    val defaultVolume: Float = 0.25f,
    /** The HushControls volume slider runs minVolume..1. */
    val minVolume: Float = 0.05f,
    /** Media volume above this share of max shows the "turn it down" line. */
    val mediaVolumeWarning: Float = 0.5f,
    /** Slow level drift of the noise (± dB) with a period between the two bounds, so it never sounds like a loop. */
    val driftDb: Double = 1.5,
    val driftPeriodMin: Duration = Duration.ofMinutes(3),
    val driftPeriodMax: Duration = Duration.ofMinutes(7),
    /** Generated loop length and sample rate. */
    val loopLength: Duration = Duration.ofSeconds(20),
    val sampleRate: Int = 48_000,
)
