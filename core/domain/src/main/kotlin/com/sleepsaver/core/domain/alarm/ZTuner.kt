package com.sleepsaver.core.domain.alarm

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.schedule.AlarmMode
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs

/** Why the restless trigger was retuned. RESET = "Reset to default": zT back to restlessZ, and landings before it no
 *  longer count (otherwise the same rings would redo the change at the next refresh). */
enum class TuningReason { RAISED_AFTER_DEEP_RINGS, LOWERED_AFTER_LATE_RINGS, RESET }

/** A tuning row (separate from the plan; "Reset to default" stores a RESET row at restlessZ). Example: zThreshold 2.75
 *  after deep rings. */
data class AlarmTuning(val zThreshold: Double, val reason: TuningReason, val changedAt: Instant, val offset: ZoneOffset)

/**
 * Bounded self-tuning of zT from Wake landings: ±restlessZStep per step, within [restlessZMin, restlessZMax], at most
 * one step per tuneCooldownDays (§2.5).
 */
class ZTuner(private val config: SleepConfig = SleepConfig.DEFAULT) {

    /** The threshold in force: the tuning row's, else the default restlessZ (2.5). */
    fun effectiveZ(tuning: AlarmTuning?): Double = tuning?.zThreshold ?: config.surfacing.restlessZ

    /**
     * The next tuning row, or null for no change. Reads the most recent tuneRecentLandings SURFACING landings with
     * stages that rang after the last change: tuneRaiseDeepRings restless rings in watch-scored deep sleep raise zT by a
     * step (stricter); otherwise tuneLowerDeadlineRings wake-time rings with ≥ tuneLowerNonDeep of non-deep window
     * lower it. Nothing changes within tuneCooldownDays of the last change, or when the bounds absorb the step.
     */
    fun next(current: AlarmTuning?, landings: List<WakeLanding>, now: Instant, offset: ZoneOffset): AlarmTuning? {
        val surfacing = config.surfacing
        // A reset starts a fresh count but no cooldown: only rings after it count anyway.
        val cooldownEnd = current?.takeIf { it.reason != TuningReason.RESET }?.changedAt
            ?.plus(Duration.ofDays(surfacing.tuneCooldownDays))
        if (cooldownEnd != null && now.isBefore(cooldownEnd)) return null
        val recent = landings
            .filter { it.event.mode == AlarmMode.SURFACING && it.hasStages }
            .filter { current == null || it.event.firstRingAt.isAfter(current.changedAt) }
            .sortedWith(compareByDescending<WakeLanding> { it.event.wakeDate }.thenByDescending { it.event.firstRingAt })
            .take(surfacing.tuneRecentLandings)
        val deepRestless = recent.count { it.event.reason == RingReason.RESTLESS && it.stageAtRing == LandingStage.DEEP }
        val nonDeepMinutes = surfacing.tuneLowerNonDeep.toMinutes()
        val lateDeadline = recent.count {
            it.event.reason == RingReason.DEADLINE && (it.windowNonDeepMinutes ?: 0) >= nonDeepMinutes
        }
        val step = surfacing.restlessZStep
        val (change, reason) = when {
            deepRestless >= surfacing.tuneRaiseDeepRings -> step to TuningReason.RAISED_AFTER_DEEP_RINGS
            lateDeadline >= surfacing.tuneLowerDeadlineRings -> -step to TuningReason.LOWERED_AFTER_LATE_RINGS
            else -> return null
        }
        val z = effectiveZ(current)
        val next = (z + change).coerceIn(surfacing.restlessZMin, surfacing.restlessZMax)
        if (abs(next - z) < Z_EPSILON) return null
        return AlarmTuning(zThreshold = next, reason = reason, changedAt = now, offset = offset)
    }

    private companion object {
        /** zT values differ by multiples of restlessZStep; anything closer than this is the same threshold. */
        const val Z_EPSILON = 1e-9
    }
}
