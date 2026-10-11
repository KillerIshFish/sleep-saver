package com.sleepsaver.core.domain.alarm

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepStage
import com.sleepsaver.core.domain.model.SleepStageType
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * How likely the watch scored deep sleep at a point of the night, from recent Garmin nights, in [bin]-wide bins
 * counted from sleep onset ([sinceOnset]) and back from the final wake ([beforeWake]). p_k = (deep + 1) / (covered + 2).
 */
data class DeepPrior(val bin: Duration, val sinceOnset: List<Double>, val beforeWake: List<Double>, val nightsUsed: Int) {
    /** Blend of the two bins (priorOnsetWeight); one side when the other is out of range or negative; null when both are. */
    fun pDeepAt(sinceOnset: Duration, beforeWake: Duration, config: SleepConfig = SleepConfig.DEFAULT): Double? {
        val fromOnset = binValue(this.sinceOnset, sinceOnset)
        val toWake = binValue(this.beforeWake, beforeWake)
        val weight = config.surfacing.priorOnsetWeight
        if (fromOnset == null || toWake == null) return fromOnset ?: toWake
        return weight * fromOnset + (1 - weight) * toWake
    }

    /** bins[floor(offset / bin)]; null when [offset] is negative or past the last bin. */
    private fun binValue(bins: List<Double>, offset: Duration): Double? {
        val binMs = bin.toMillis()
        if (offset.isNegative || binMs <= 0) return null
        val index = offset.toMillis() / binMs
        return if (index < bins.size) bins[index.toInt()] else null
    }
}

/**
 * Builds the [DeepPrior] from staged main sleeps: present nights with at least one LIGHT/DEEP/REM stage and wakeDate
 * in (asOf − priorLookbackDays, asOf], the most recent priorMaxNights; onset = start of the first LIGHT/DEEP/REM stage,
 * end = end of the last one (drops the final awakening); bin k sampled at its midpoint (§2.5).
 *
 * A midpoint counts when a main-sleep stage covers it (start ≤ m < end) and its type is LIGHT, DEEP, REM or awake
 * (covered++, and deep++ for DEEP); SLEEPING, UNKNOWN and uncovered midpoints are skipped.
 */
class DeepPriorEstimator(private val config: SleepConfig = SleepConfig.DEFAULT) {
    /** null with fewer than priorMinNights stage nights. The caller passes no demo nights (demo mode → no prior). */
    fun estimate(nights: List<Night>, asOf: LocalDate): DeepPrior? {
        val surfacing = config.surfacing
        val oldest = asOf.minusDays(surfacing.priorLookbackDays)
        val staged = nights
            .filter { it.isPresent && it.wakeDate.isAfter(oldest) && !it.wakeDate.isAfter(asOf) }
            .sortedByDescending { it.wakeDate }
            .mapNotNull { night -> night.main?.stages?.let { StagedSleep.of(it) } }
            .take(surfacing.priorMaxNights)
        if (staged.size < surfacing.priorMinNights) return null

        val binMs = surfacing.priorBin.toMillis()
        val bins = (surfacing.priorHorizon.toMillis() / binMs).toInt()
        val fromOnset = BinCounts(bins)
        val beforeWake = BinCounts(bins)
        for (sleep in staged) {
            for (k in 0 until bins) {
                val midpoint = k * binMs + binMs / 2
                val afterOnset = sleep.onset.plusMillis(midpoint)
                if (afterOnset.isBefore(sleep.end)) fromOnset.add(k, sleep.typeAt(afterOnset))
                val beforeEnd = sleep.end.minusMillis(midpoint)
                if (!beforeEnd.isBefore(sleep.onset)) beforeWake.add(k, sleep.typeAt(beforeEnd))
            }
        }
        return DeepPrior(surfacing.priorBin, fromOnset.probabilities(), beforeWake.probabilities(), staged.size)
    }
}

/** A main sleep's stages between its first and last LIGHT/DEEP/REM stage. */
private class StagedSleep(val stages: List<SleepStage>, val onset: Instant, val end: Instant) {
    fun typeAt(t: Instant): SleepStageType? = stages.firstOrNull { !t.isBefore(it.start) && t.isBefore(it.end) }?.type

    companion object {
        /** null without a LIGHT/DEEP/REM stage. */
        fun of(stages: List<SleepStage>): StagedSleep? {
            val sleep = stages.filter { it.type.isScoredSleep }
            if (sleep.isEmpty()) return null
            return StagedSleep(stages.sortedBy { it.start }, sleep.minOf { it.start }, sleep.maxOf { it.end })
        }
    }
}

/** Per-bin counts of covered and deep midpoints. */
private class BinCounts(size: Int) {
    private val covered = IntArray(size)
    private val deep = IntArray(size)

    fun add(bin: Int, type: SleepStageType?) {
        if (type == null || !(type.isScoredSleep || type.isAwake)) return
        covered[bin]++
        if (type == SleepStageType.DEEP) deep[bin]++
    }

    /** Laplace-smoothed: (deep + 1) / (covered + 2). */
    fun probabilities(): List<Double> = covered.indices.map { (deep[it] + 1.0) / (covered[it] + 2.0) }
}

/** LIGHT, DEEP or REM: sleep the watch scored by stage. */
internal val SleepStageType.isScoredSleep: Boolean
    get() = this == SleepStageType.LIGHT || this == SleepStageType.DEEP || this == SleepStageType.REM
