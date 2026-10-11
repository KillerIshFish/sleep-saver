package com.sleepsaver.core.domain.alarm

import com.sleepsaver.core.domain.config.SleepConfig
import java.time.Instant
import java.time.ZoneOffset

/** Why the restless trigger was retuned. */
enum class TuningReason { RAISED_AFTER_DEEP_RINGS, LOWERED_AFTER_LATE_RINGS }

/** A tuning row (separate from the plan; "Reset to default" deletes it). Example: zThreshold 2.75 after deep rings. */
data class AlarmTuning(val zThreshold: Double, val reason: TuningReason, val changedAt: Instant, val offset: ZoneOffset)

/**
 * Bounded self-tuning of zT from Wake landings: ±restlessZStep per step, within [restlessZMin, restlessZMax], at most
 * one step per tuneCooldownDays (§2.5).
 */
class ZTuner(private val config: SleepConfig = SleepConfig.DEFAULT) {

    /** The threshold in force: the tuning row's, else the default restlessZ (2.5). */
    fun effectiveZ(tuning: AlarmTuning?): Double = tuning?.zThreshold ?: config.surfacing.restlessZ

    /** The next tuning row, or null for no change. */
    fun next(current: AlarmTuning?, landings: List<WakeLanding>, now: Instant, offset: ZoneOffset): AlarmTuning? =
        TODO("B1: ZTuner.next (§2.5)")
}
