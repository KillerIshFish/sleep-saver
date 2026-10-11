package com.sleepsaver.core.domain.alarm

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.patterns.median
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max

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

    private val surfacing = config.surfacing
    private val epochMs: Long = surfacing.epoch.toMillis()

    fun calibration(night: SurfacingNight, epochs: List<MovementEpoch>): Calibration? {
        val end = calibrationEnd(night)
        val from = end - surfacing.sensingLeadIn
        val values = epochs.filter { !it.start.isBefore(from) && it.start.isBefore(end) }.mapNotNull { it.activity }
        if (values.size < surfacing.calibrationMinEpochs) return null
        val median = median(values)
        val mad = median(values.map { abs(it - median) })
        val sigma = max(surfacing.madToSigma * mad, surfacing.sigmaFloor)
        return Calibration(median = median, sigma = sigma, epochs = values.size)
    }

    /** Called after each epoch closes, with every epoch of the stream so far (oldest first). */
    fun decide(night: SurfacingNight, epochs: List<MovementEpoch>, now: Instant): SurfacingDecision {
        val calibration = calibration(night, epochs)
        if (!now.isBefore(night.target)) return SurfacingDecision.Done(outcome(night, epochs, calibration))
        val calibrationEnd = calibrationEnd(night)
        val triggerFrom = maxOf(night.windowStart + surfacing.minLead, calibrationEnd)
        if (calibration == null || now.isBefore(triggerFrom)) return SurfacingDecision.Wait

        // The epoch that just closed and the ones before it; only trigger-zone slots (start ≥ calibrationEnd) count.
        val byStart = epochs.associateBy { it.start }
        val latest = Math.floorDiv(now.toEpochMilli(), epochMs) * epochMs - epochMs
        val recent = (0 until surfacing.confirmEpochs).map { back ->
            Instant.ofEpochMilli(latest - back * epochMs).takeIf { !it.isBefore(calibrationEnd) }
                ?.let { byStart[it]?.activity }
                ?.let { calibration.z(it) }
        }
        val z0 = recent.firstOrNull()
        if (z0 != null && z0 >= surfacing.spikeZ) return SurfacingDecision.Fire(now, z0, FireRule.SPIKE)

        val sinceOnset = Duration.between(night.expectedOnset, now)
        val pDeep = night.prior?.pDeepAt(sinceOnset, Duration.between(now, night.target), config)
        val zEff = night.zThreshold +
            (if (pDeep != null && pDeep > surfacing.deepRiskGate) surfacing.deepRiskZBoost else 0.0) -
            (if (night.debtAware) surfacing.debtAwareZDrop else 0.0)
        val contiguous = recent.filterNotNull().takeIf { it.size == recent.size && it.isNotEmpty() }
            ?: return SurfacingDecision.Wait
        return if (contiguous.count { it >= zEff } >= surfacing.confirmNeeded) {
            SurfacingDecision.Fire(now, contiguous.max(), FireRule.TWO_OF_THREE)
        } else {
            SurfacingDecision.Wait
        }
    }

    /**
     * Highest z inside the trigger zone so far (for the event log); null without calibration. The zone holds the
     * epochs decide weighs: start in [calibrationEnd, target). null too while it holds no epoch with activity.
     */
    fun maxZ(night: SurfacingNight, epochs: List<MovementEpoch>): Double? {
        val calibration = calibration(night, epochs) ?: return null
        val calibrationEnd = calibrationEnd(night)
        return epochs.filter { !it.start.isBefore(calibrationEnd) && it.start.isBefore(night.target) }
            .mapNotNull { epoch -> epoch.activity?.let { calibration.z(it) } }
            .maxOrNull()
    }

    /** If the alarm was armed late, the window effectively starts once a full lead-in of calibration exists. */
    private fun calibrationEnd(night: SurfacingNight): Instant =
        maxOf(night.windowStart, night.sensingStart + surfacing.sensingLeadIn)

    /** How a run that reached the wake time without firing is logged. */
    private fun outcome(night: SurfacingNight, epochs: List<MovementEpoch>, calibration: Calibration?): SensingOutcome {
        if (calibration == null) return SensingOutcome.TOO_FEW_EPOCHS
        val moved = epochs.any { epoch ->
            !epoch.start.isBefore(night.sensingStart) && epoch.start.isBefore(night.target) &&
                (epoch.activity ?: 0.0) > surfacing.placementActiveActivity
        }
        return if (moved) SensingOutcome.NO_TRIGGER else SensingOutcome.FLAT
    }

    private fun Calibration.z(activity: Double): Double = (activity - median) / sigma
}
