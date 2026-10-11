package com.sleepsaver.core.domain.alarm

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night
import java.time.Duration
import java.time.LocalDate

/**
 * How likely the watch scored deep sleep at a point of the night, from recent Garmin nights, in [bin]-wide bins
 * counted from sleep onset ([sinceOnset]) and back from the final wake ([beforeWake]). p_k = (deep + 1) / (covered + 2).
 */
data class DeepPrior(val bin: Duration, val sinceOnset: List<Double>, val beforeWake: List<Double>, val nightsUsed: Int) {
    /** Blend of the two bins (priorOnsetWeight); one side when the other is out of range or negative; null when both are. */
    fun pDeepAt(sinceOnset: Duration, beforeWake: Duration, config: SleepConfig = SleepConfig.DEFAULT): Double? =
        TODO("B1: DeepPrior.pDeepAt (§2.5)")
}

/**
 * Builds the [DeepPrior] from staged main sleeps: present nights with at least one LIGHT/DEEP/REM stage and wakeDate
 * in (asOf − priorLookbackDays, asOf], the most recent priorMaxNights; onset = start of the first LIGHT/DEEP/REM stage,
 * end = end of the last one (drops the final awakening); bin k sampled at its midpoint (§2.5).
 */
class DeepPriorEstimator(private val config: SleepConfig = SleepConfig.DEFAULT) {
    /** null with fewer than priorMinNights stage nights. The caller passes no demo nights (demo mode → no prior). */
    fun estimate(nights: List<Night>, asOf: LocalDate): DeepPrior? = TODO("B1: DeepPriorEstimator.estimate (§2.5)")
}
