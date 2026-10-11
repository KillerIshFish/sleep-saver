package com.sleepsaver.core.alarm

/**
 * Platform plumbing numbers (SPEC_V2 §2.1 exemptions): no product formula, default or UI range uses them, so they
 * live here as named constants instead of in SleepConfig. core:alarm's tests pin them.
 */
internal object AlarmPlatform {
    /** The partial wake lock AlarmReceiver takes on WINDOW, so NightService can start before the CPU sleeps again. */
    const val WINDOW_WAKE_LOCK_MS = 10_000L

    /** The partial wake lock AlarmReceiver takes on RING; AlarmRingService releases it once it sounds. */
    const val RING_WAKE_LOCK_MS = 60_000L

    /** computeAlarmPlan gets this long; on timeout the armed alarms stay as they are. */
    const val PLAN_TIMEOUT_MS = 8_000L

    /** The optional reads after the plan (tuning, anchor, prior, clock format) get this long, then use defaults. */
    const val PLAN_EXTRAS_TIMEOUT_MS = 3_000L

    /** goAsync work in the boot receiver stays under the 10-s broadcast limit. */
    const val RECEIVER_BUDGET_MS = 9_000L

    /** The ring's swell (player gain) and the Hush fades are updated this often. */
    const val GAIN_STEP_MS = 250L

    /** Alarm vibration: off 0 ms, on 600 ms, off 900 ms, repeating from index 0. */
    val VIBRATION_WAVEFORM = longArrayOf(0, 600, 900)

    /** Wake-lock tags, named so they are recognisable in battery stats. */
    const val SURFACING_WAKE_LOCK_TAG = "SleepSaver:surfacing"
    const val RING_WAKE_LOCK_TAG = "SleepSaver:ring"
    const val WINDOW_WAKE_LOCK_TAG = "SleepSaver:window"

    // ---------------------------------------------------------------- Synthesis (§4.8)

    const val SAMPLE_RATE = 48_000

    /** Every synthesized tone stays at or below this peak. */
    const val TONE_PEAK = 0.9f

    /** Soft chimes: E5, G5, C6 rising, 0.6 s apart, a 2.4-s cycle. */
    val SOFT_CHIME_NOTES_HZ = doubleArrayOf(659.25, 783.99, 1046.50)
    const val SOFT_CHIME_SPACING_S = 0.6
    const val SOFT_CHIME_CYCLE_S = 2.4

    /** Warm rise: a slow A3/E4 pad with an amplitude swell, a 4-s cycle. */
    val WARM_RISE_NOTES_HZ = doubleArrayOf(220.0, 329.63)
    const val WARM_RISE_CYCLE_S = 4.0

    /** Bright bells: partials 1, 2.76 and 5.4 × C6, a 1.2-s cycle. */
    const val BRIGHT_BELL_HZ = 1046.50
    val BRIGHT_BELL_PARTIALS = doubleArrayOf(1.0, 2.76, 5.4)
    const val BRIGHT_BELL_CYCLE_S = 1.2

    /** Hush loops are joined with an equal-power crossfade of this length. */
    const val NOISE_SEAM_MS = 250

    /** Brown noise: leaky integrator leak per sample, then a DC-blocking high-pass at this corner. */
    const val BROWN_LEAK = 0.02
    const val BROWN_HIGH_PASS_HZ = 20.0

    /** Noise is normalised to this RMS before the player gain. */
    const val NOISE_RMS = 0.2

    /** Seed of the pre-rendered Hush loops (deterministic, so tests and devices hear the same loop). */
    const val NOISE_SEED = 20_261_011L
}
