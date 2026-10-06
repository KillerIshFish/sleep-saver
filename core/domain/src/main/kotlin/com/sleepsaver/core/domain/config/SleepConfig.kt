package com.sleepsaver.core.domain.config

import java.time.Duration
import java.time.LocalTime

/**
 * Every tunable number in the engine. These are product heuristics, not medical science.
 * See docs/PLAN.md §3 for where each one is used.
 */
data class SleepConfig(
    // --- Night assembly (§3.1) ---
    val minSessionDuration: Duration = Duration.ofMinutes(5),
    val nightEndCutoff: LocalTime = LocalTime.of(14, 0),
    val fragmentMergeMaxGap: Duration = Duration.ofMinutes(60),
    val napMaxDuration: Duration = Duration.ofHours(3),
    val napWindowStart: LocalTime = LocalTime.of(9, 0),
    val napWindowEnd: LocalTime = LocalTime.of(20, 0),
    val interruptionMinDuration: Duration = Duration.ofMinutes(5),
    val dedupeOverlapRatio: Double = 0.5,
    /** "All sources": a duplicate shorter than this fraction of the other one loses to it, whatever its origin. */
    val dedupeShortDuplicateRatio: Double = 0.5,
    val preferredOrigin: String = GARMIN_CONNECT_PACKAGE,

    // --- Sleep need (§3.2) ---
    val defaultSleepNeed: Duration = Duration.ofHours(8),
    val needEstimateMinNights: Int = 14,
    val needEstimateLookbackNights: Int = 60,
    val freeNightWakeDelay: Duration = Duration.ofMinutes(45),
    val shortNightMargin: Duration = Duration.ofMinutes(60),
    val needMinFreeNights: Int = 4,
    val needClampMin: Duration = Duration.ofHours(6),
    val needClampMax: Duration = Duration.ofMinutes(10 * 60 + 30),
    val needRounding: Duration = Duration.ofMinutes(5),
    val needHighConfidenceNights: Int = 8,
    val needHighConfidenceIqr: Duration = Duration.ofMinutes(45),
    val needMediumConfidenceNights: Int = 5,
    val needMediumConfidenceIqr: Duration = Duration.ofMinutes(75),
    val needReofferMinChange: Duration = Duration.ofMinutes(15),
    val needReofferAfterDays: Long = 7,

    // --- Sleep debt (§3.3) ---
    val debtWindowNights: Int = 14,
    val debtDecay: Double = 0.85,
    val maxSurplusCreditPerNight: Duration = Duration.ofHours(2),

    // --- Status, streak, energy potential (§3.4) ---
    val lowDebtMax: Duration = Duration.ofHours(2),
    val highDebtMin: Duration = Duration.ofHours(5),
    val streakThreshold: Duration = Duration.ofHours(5),
    /** A run of more missing days than this breaks the streak. */
    val streakMaxBridgedMissingDays: Int = 2,
    val streakLookbackDays: Int = 365,
    val energyPotentialPerDebtHour: Double = 5.0,
    val energyPotentialFloor: Int = 50,
    val energyPotentialCeiling: Int = 100,

    // --- Circadian anchor (§3.5) ---
    val anchorMaxNights: Int = 14,
    val anchorLookbackDays: Long = 21,
    val anchorMinNights: Int = 3,
    val anchorDecay: Double = 0.85,

    // --- Learned wake and fall-asleep times (§3.5) ---
    /** Nights older than this (before the target wake date) aren't used to learn wake and fall-asleep times. */
    val timingLookbackDays: Long = 28,
    /** With at least this many nights of the same kind (weekday / weekend), only those are used. */
    val timingMinSameKindNights: Int = 2,

    // --- Two-process model (§3.6) ---
    val sRiseTauHours: Double = 18.2,
    val sDecayTauHours: Double = 4.2,
    val sSeed: Double = 0.5,
    val sHistoryHours: Long = 72,
    val c24Amplitude: Double = 0.35,
    val c24PeakHoursAfterWake: Double = 9.5,
    val c12Amplitude: Double = 0.15,
    val c12PeakHoursAfterWake: Double = 2.0,
    val inertiaBase: Double = 0.15,
    val inertiaDebtScaleHours: Double = 4.0,
    val inertiaMax: Double = 0.45,
    val inertiaTau: Duration = Duration.ofMinutes(30),
    val inertiaEpsilon: Double = 0.02,
    val curveStep: Duration = Duration.ofMinutes(5),

    // --- Phases (§3.7), all relative to typical wake W ---
    val grogginessMin: Duration = Duration.ofMinutes(60),
    val grogginessMax: Duration = Duration.ofMinutes(120),
    val morningPeak: PhaseGuardrail = PhaseGuardrail(
        nominalStart = hours(2.0), nominalEnd = hours(5.0), searchStart = hours(1.0), searchEnd = hours(6.5),
    ),
    val afternoonDip: PhaseGuardrail = PhaseGuardrail(
        nominalStart = hours(6.5), nominalEnd = hours(9.0), searchStart = hours(5.0), searchEnd = hours(10.0),
    ),
    val eveningPeak: PhaseGuardrail = PhaseGuardrail(
        nominalStart = hours(10.0), nominalEnd = hours(13.0), searchStart = hours(9.0), searchEnd = hours(14.5),
    ),
    val phaseMaxSlide: Duration = Duration.ofMinutes(45),
    val sleepLatency: Duration = Duration.ofMinutes(15),
    val melatoninHalfWidth: Duration = Duration.ofMinutes(30),
    val windDownLength: Duration = Duration.ofMinutes(60),
    val wakeZoneHalfWidth: Duration = Duration.ofMinutes(30),
    /** A phase squeezed below this (e.g. the morning peak after long grogginess) is extended to this length. */
    val minPhaseLength: Duration = Duration.ofMinutes(30),

    // --- "Last night isn't here yet" (§3.10) ---
    /** We start expecting last night's data this long before typical wake. */
    val waitingStartsBeforeWake: Duration = Duration.ofHours(2),
    /** After this local time a still-missing last night is shown as "missing", not "waiting". */
    val waitingGiveUpAt: LocalTime = LocalTime.of(14, 0),

    // --- Habits (§3.8) ---
    val morningLightOffset: Duration = Duration.ofMinutes(15),
    val caffeineCutoffBeforeBed: Duration = Duration.ofHours(10),
    val largeMealCutoffBeforeBed: Duration = Duration.ofHours(3),
    val alcoholCutoffBeforeBed: Duration = Duration.ofHours(3),
    val exerciseCutoffBeforeBed: Duration = Duration.ofHours(2),
    val napMaxLength: Duration = Duration.ofMinutes(30),
    val napIdealLength: Duration = Duration.ofMinutes(20),
) {
    companion object {
        const val GARMIN_CONNECT_PACKAGE = "com.garmin.android.apps.connectmobile"
        const val DEMO_ORIGIN = "com.sleepsaver.demo"
        val DEFAULT = SleepConfig()
    }
}

/**
 * A phase's guardrail relative to typical wake W. The curve's extreme is searched in
 * [searchStart, searchEnd]; the nominal window then slides toward it by at most `phaseMaxSlide`.
 */
data class PhaseGuardrail(
    val nominalStart: Duration,
    val nominalEnd: Duration,
    val searchStart: Duration,
    val searchEnd: Duration,
) {
    val nominalCenter: Duration get() = nominalStart.plus(nominalEnd).dividedBy(2)
    val length: Duration get() = nominalEnd.minus(nominalStart)
}

private fun hours(h: Double): Duration = Duration.ofSeconds((h * 3600).toLong())
