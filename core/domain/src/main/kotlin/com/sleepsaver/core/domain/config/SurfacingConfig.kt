package com.sleepsaver.core.domain.config

import java.time.Duration

/**
 * Movement sensing, the restless trigger, the Garmin prior, Wake landing and tuning (docs/PLAN.md §3.12).
 * Example: for a 6:30 alarm with a 30-min window the phone senses from 5:45 ([sensingLeadIn] before the window) and
 * may ring from 6:03 ([minLead] into the window).
 */
data class SurfacingConfig(
    /** Sensing (and calibration) starts this long before the window. */
    val sensingLeadIn: Duration = Duration.ofMinutes(15),
    /** Never ring in the first minutes of the window. */
    val minLead: Duration = Duration.ofMinutes(3),
    val epoch: Duration = Duration.ofSeconds(30),
    /** Per-axis gravity/baseline time constant (EMA). */
    val baselineTau: Duration = Duration.ofSeconds(1),
    /** A sample's time weight is capped at this (sensor stalls). */
    val maxSampleGap: Duration = Duration.ofSeconds(1),
    /** m/s² of deviation ignored as sensor noise. */
    val noiseFloor: Double = 0.03,
    /** Activity unit = mean above-floor deviation in m/s² × this. */
    val activityScale: Double = 1000.0,
    /** An epoch with less than this share of its length covered by samples is missing. */
    val epochMinCoverage: Double = 0.5,
    /** Calibration (the lead-in) needs this many non-missing epochs of its 30. */
    val calibrationMinEpochs: Int = 20,
    val madToSigma: Double = 1.4826,
    /** σ never below this (activity units), so a flat calibration doesn't make every twitch a spike. */
    val sigmaFloor: Double = 5.0,
    /** A run whose every epoch stayed at or below this never moved: logged as PLACEMENT_FALLBACK. */
    val placementActiveActivity: Double = 10.0,
    /** zT, the restless threshold; tuned within [restlessZMin, restlessZMax] in restlessZStep steps. */
    val restlessZ: Double = 2.5,
    val restlessZMin: Double = 1.5,
    val restlessZMax: Double = 3.5,
    val restlessZStep: Double = 0.25,
    /** Fire when ≥ confirmNeeded of the last confirmEpochs epochs reach zT… */
    val confirmEpochs: Int = 3,
    val confirmNeeded: Int = 2,
    /** …or the latest epoch alone reaches this. */
    val spikeZ: Double = 5.0,
    /** pDeep above this needs zT + deepRiskZBoost. */
    val deepRiskGate: Double = 0.5,
    val deepRiskZBoost: Double = 1.0,
    /** Debt-aware nights use zT − this. */
    val debtAwareZDrop: Double = 0.5,
    /** Accelerometer sampling period (25 Hz) and the batching latency the sensor may use. */
    val samplingPeriod: Duration = Duration.ofMillis(40),
    val maxReportLatency: Duration = Duration.ofSeconds(30),
    /** Partial wake lock (non-wake-up accelerometer only) lasts until the wake time + this. */
    val wakeLockMargin: Duration = Duration.ofMinutes(5),
    /** The whole-night log's wake lock lasts until the armed target + this (capped by anchor + nightMaxLength). */
    val wholeNightWakeLockMargin: Duration = Duration.ofMinutes(10),
    /** A window run without a stored expected onset assumes windowStart − this. */
    val onsetFallbackBeforeWindow: Duration = Duration.ofHours(6),
    /** A window run never lasts longer than this (lead-in + max window + margin). */
    val maxRunLength: Duration = Duration.ofMinutes(70),
    /** Epochs are written to Room in batches of this many. */
    val persistEveryEpochs: Int = 10,

    // Garmin prior (pDeep)
    /** Bin width of the deep-sleep prior. */
    val priorBin: Duration = Duration.ofMinutes(10),
    /** The prior covers this much after onset and before wake (72 bins of 10 min). */
    val priorHorizon: Duration = Duration.ofHours(12),
    val priorMaxNights: Int = 60,
    val priorMinNights: Int = 7,
    val priorLookbackDays: Long = 90,
    /** pDeep = w · p(since onset) + (1 − w) · p(before wake). */
    val priorOnsetWeight: Double = 0.5,

    // Wake landing and tuning
    /** Wake landing's majority stage looks this far back from the first ring. */
    val landingLookback: Duration = Duration.ofMinutes(5),
    /** Window deep share is reported only when the watch scored at least this share of the window. */
    val landingMinWindowCoverage: Double = 0.5,
    /** Tuning reads at most this many recent landings… */
    val tuneRecentLandings: Int = 7,
    /** …raises zT after this many restless rings in deep sleep… */
    val tuneRaiseDeepRings: Int = 3,
    /** …and lowers it after this many wake-time rings with at least [tuneLowerNonDeep] of non-deep window. */
    val tuneLowerDeadlineRings: Int = 5,
    val tuneLowerNonDeep: Duration = Duration.ofMinutes(10),
    /** No further tuning step this many days after the last one. */
    val tuneCooldownDays: Long = 7,
    /** refreshTuning() reads the landings of the last this many wake dates. */
    val tuneLookbackDays: Long = 14,
    /** The Wake landing tuning note shows while the tuning row changed less than this many days ago. */
    val tuningNoteDays: Long = 7,
    /** Wake landing's mini hypnogram spans this much around the first ring. */
    val landingSpan: Duration = Duration.ofMinutes(90),
    // --- B1 additions (append below this line only)
)
