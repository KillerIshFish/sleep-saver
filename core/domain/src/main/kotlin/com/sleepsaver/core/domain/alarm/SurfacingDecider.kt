package com.sleepsaver.core.domain.alarm

import com.sleepsaver.core.domain.config.SleepConfig
import java.time.Instant

/** Tonight's surfacing run as the decider needs it. */
data class SurfacingNight(
    val occurrenceId: String,
    val target: Instant,
    /** Effective window start (debt-aware widening already applied). */
    val windowStart: Instant,
    /** When this movement stream started (the window run, or the whole-night log). */
    val sensingStart: Instant,
    val debtAware: Boolean,
    /** zT after tuning (ZTuner.effectiveZ). */
    val zThreshold: Double,
    val expectedOnset: Instant,
    /** null = uniform prior: no deep-risk gating. */
    val prior: DeepPrior?,
)

/** Which rule fired: two of the last three epochs at or above zT, or one spike. */
enum class FireRule { TWO_OF_THREE, SPIKE }

/** How a surfacing run ended, for the event log. */
enum class SensingOutcome { RESTLESS, NO_TRIGGER, FLAT, TOO_FEW_EPOCHS, UNAVAILABLE }

/** What to do after an epoch closes. */
sealed interface SurfacingDecision {
    data object Wait : SurfacingDecision

    data class Fire(val at: Instant, val z: Double, val rule: FireRule) : SurfacingDecision

    /** now ≥ target: the wake-time ring (already armed) is the ring; this says how to log it. */
    data class Done(val outcome: SensingOutcome) : SurfacingDecision
}

/** The lead-in's movement level: median and robust spread (MAD-based sigma, floored) over [epochs] epochs. */
data class Calibration(val median: Double, val sigma: Double, val epochs: Int)

/**
 * The restless trigger (DECISIONS A1, SYNTHESIS §5.1).
 *
 *  - calibrationEnd = max(windowStart, sensingStart + sensingLeadIn); calibration = non-missing epochs in
 *    [calibrationEnd − sensingLeadIn, calibrationEnd), null below calibrationMinEpochs; median, MAD (type 7),
 *    sigma = max(madToSigma · MAD, sigmaFloor); z(e) = (activity − median) / sigma.
 *  - triggerFrom = max(windowStart + minLead, calibrationEnd).
 *  - decide: now ≥ target → Done (TOO_FEW_EPOCHS without calibration, FLAT when nothing moved above
 *    placementActiveActivity, else NO_TRIGGER), never Fire there; before triggerFrom or without calibration → Wait;
 *    else SPIKE when the epoch that just closed has z ≥ spikeZ, or TWO_OF_THREE when the last three contiguous epochs
 *    (start ≥ calibrationEnd) have ≥ confirmNeeded at z ≥ zEff, where zEff = zThreshold (+ deepRiskZBoost when
 *    pDeep > deepRiskGate) (− debtAwareZDrop on debt-aware nights). Stillness never fires.
 */
class SurfacingDecider(private val config: SleepConfig = SleepConfig.DEFAULT) {

    fun calibration(night: SurfacingNight, epochs: List<MovementEpoch>): Calibration? =
        TODO("B1: SurfacingDecider.calibration (§2.5)")

    /** Called after each epoch closes, with every epoch of the stream so far (oldest first). */
    fun decide(night: SurfacingNight, epochs: List<MovementEpoch>, now: Instant): SurfacingDecision =
        TODO("B1: SurfacingDecider.decide (§2.5)")

    /** Highest z inside the trigger zone so far (for the event log); null without calibration. */
    fun maxZ(night: SurfacingNight, epochs: List<MovementEpoch>): Double? = TODO("B1: SurfacingDecider.maxZ (§2.5)")
}
