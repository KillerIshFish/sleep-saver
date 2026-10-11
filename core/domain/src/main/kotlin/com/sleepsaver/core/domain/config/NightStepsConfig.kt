package com.sleepsaver.core.domain.config

import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.nightsteps.StepTiming
import com.sleepsaver.core.domain.nightsteps.Technique
import java.time.Duration

/**
 * Night steps: placement, tracking, suggestions, guided tools (docs/PLAN.md §3.13). Example: Slow tide sits 30 min
 * before the target bedtime and lasts 10 min (`timing(Technique.SLOW_TIDE)`), so with B = 23:00 it runs 22:30–22:40.
 */
data class NightStepsConfig(
    /** Lead and duration of a custom activity (and of a technique missing from [techniqueTimings]). */
    val customLead: Duration = Duration.ofMinutes(45),
    val customDuration: Duration = Duration.ofMinutes(10),
    /** Lead and duration pickers: 0..leadMax by leadStep, 0..durationMax by durationStep. */
    val leadMax: Duration = Duration.ofMinutes(240),
    val leadStep: Duration = Duration.ofMinutes(5),
    val durationMax: Duration = Duration.ofMinutes(60),
    val durationStep: Duration = Duration.ofMinutes(1),
    /** An evening is "done" when at least this share of its steps (and at least one) are checked. */
    val doneShare: Double = 0.5,
    /** Adherence shows this many evenings; "4 of the last 7 nights" counts the most recent [recentEvenings]. */
    val historyEvenings: Int = 14,
    val recentEvenings: Int = 7,
    /** The quiet streak bridges this many missed evenings in a row; one more ends it. */
    val streakMaxMisses: Int = 1,
    val streakLookbackDays: Int = 365,
    /** The quiet streak line shows only from this many evenings. */
    val streakShowFrom: Int = 2,
    /** Default lead and duration per technique (DECISIONS A2). Custom activities use customLead / customDuration. */
    val techniqueTimings: Map<Technique, StepTiming> = mapOf(
        Technique.WARM_SHOWER to StepTiming(Duration.ofMinutes(90), Duration.ofMinutes(15)),
        Technique.SCREENS_DOWN to StepTiming(Duration.ofMinutes(60), Duration.ZERO),
        Technique.TOMORROW_LIST to StepTiming(Duration.ofMinutes(45), Duration.ofMinutes(5)),
        Technique.SLOW_TIDE to StepTiming(Duration.ofMinutes(30), Duration.ofMinutes(10)),
        Technique.BODY_SCAN to StepTiming(Duration.ofMinutes(20), Duration.ofMinutes(10)),
        Technique.MUSCLE_RELEASE to StepTiming(Duration.ofMinutes(25), Duration.ofMinutes(12)),
        Technique.READ_ON_PAPER to StepTiming(Duration.ofMinutes(50), Duration.ofMinutes(20)),
        Technique.STRETCHING to StepTiming(Duration.ofMinutes(40), Duration.ofMinutes(10)),
        Technique.JOURNALING to StepTiming(Duration.ofMinutes(35), Duration.ofMinutes(5)),
        Technique.COOL_DARK_ROOM to StepTiming(Duration.ofMinutes(15), Duration.ZERO),
        Technique.HUSH to StepTiming(Duration.ofMinutes(10), Duration.ZERO),
        Technique.TV to StepTiming(Duration.ofMinutes(120), Duration.ofMinutes(30)),
        Technique.GAMING to StepTiming(Duration.ofMinutes(120), Duration.ofMinutes(30)),
    ),

    // suggestions
    /** At most this many suggestions at once. */
    val suggestionMax: Int = 2,
    /** SLOW_TO_SETTLE: the median of the most recent [latencyNights] latencies is above this. */
    val slowToSettleLatency: Duration = Duration.ofMinutes(30),
    val latencyNights: Int = 14,
    /** SLOW_TO_SETTLE needs at least this many valid latencies. */
    val latencyMinValues: Int = 3,
    /** RESTLESS_NIGHTS: mean awake time over the last [recentNights] present nights reaches this. */
    val restlessAwake: Duration = Duration.ofMinutes(30),
    val recentNights: Int = 7,
    /** STRESSFUL_DAYS / SCREENS_IN_ROUTINE: a mark on at least this many of the last [recentNights] wake dates. */
    val markMinEvenings: Int = 3,
    /** DRIFTING_BEDTIMES: circular SD of fell-asleep over [driftingNights] (≥ [driftingMinNights]) reaches this. */
    val driftingBedtimeSd: Duration = Duration.ofMinutes(60),
    val driftingNights: Int = 14,
    val driftingMinNights: Int = 7,
    /** Any of these marks in the last recentNights wake dates → no suggestions this week. */
    val quietWeekMarks: Set<Mark> = setOf(Mark.AWAY_FROM_HOME, Mark.UNWELL),

    // guided tools
    /** Slow tide patterns (in, out); the first is the default (4 s in / 6 s out ≈ 6 breaths a minute). */
    val slowTidePatterns: List<Pair<Duration, Duration>> = listOf(
        Duration.ofSeconds(4) to Duration.ofSeconds(6),
        Duration.ofSeconds(5) to Duration.ofSeconds(5),
        Duration.ofSeconds(4) to Duration.ofSeconds(8),
    ),
    val slowTideLengths: List<Duration> = listOf(Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(15)),
    val slowTideDefaultLength: Duration = Duration.ofMinutes(10),
    /** Guided tools dim to near-black after this. */
    val dimAfter: Duration = Duration.ofSeconds(20),
    val dimBrightness: Float = 0.01f,
    /** Body scan: one prompt per step. Muscle release: tense, then release, per muscle group. */
    val bodyScanStep: Duration = Duration.ofSeconds(60),
    val muscleTense: Duration = Duration.ofSeconds(5),
    val muscleRelease: Duration = Duration.ofSeconds(15),
    /** Night reset opens with this much Slow tide. */
    val nightResetBreathing: Duration = Duration.ofMinutes(2),
    // --- B2 additions (append below this line only)
) {
    /** A technique's default placement; a technique missing from the map uses the custom defaults. */
    fun timing(technique: Technique): StepTiming = techniqueTimings[technique] ?: StepTiming(customLead, customDuration)
}
