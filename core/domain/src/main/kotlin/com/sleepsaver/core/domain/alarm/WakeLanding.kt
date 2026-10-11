package com.sleepsaver.core.domain.alarm

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepStage
import com.sleepsaver.core.domain.model.SleepStageType
import java.time.Duration
import java.time.Instant

/** The watch's stage at a moment, as Wake landing shows it. */
enum class LandingStage { AWAKE, LIGHT, REM, DEEP, UNSCORED }

/**
 * Where a planned alarm landed on the watch's hypnogram (DECISIONS A4). Copy stays the watch's estimate:
 * "Watch estimate: light sleep when it rang".
 */
data class WakeLanding(
    val event: AlarmEvent,
    val stageAtRing: LandingStage,
    /** Duration-weighted majority stage in [firstRingAt − landingLookback, firstRingAt); null when unscored. */
    val majorityBefore: LandingStage?,
    val minutesEarly: Int,
    /** Watch-scored deep share of [windowStart, target); null for zero-length windows or < landingMinWindowCoverage scored. */
    val windowDeepShare: Double?,
    /** Watch-scored LIGHT + REM + AWAKE minutes inside the window (floor); null like windowDeepShare. */
    val windowNonDeepMinutes: Int?,
    val hasStages: Boolean,
)

/**
 * Grades a planned ring against the night's stages (main and extra segments). stageAt(t) uses the stage with
 * start ≤ t < end (awake types → AWAKE, SLEEPING/UNKNOWN → UNSCORED; no stage: outside main → AWAKE, else UNSCORED).
 * Majority ties go AWAKE > LIGHT > REM > DEEP.
 *
 * Example: a 6:30 alarm that rang at 6:12 while the watch scored REM, after 5 min of light sleep, gives stageAtRing
 * REM, majorityBefore LIGHT and minutesEarly 18. The window share is deep time over the whole window ("Your watch
 * scored 12% of the window as deep sleep"), reported when at least landingMinWindowCoverage of it is scored.
 * hasStages = the night has at least one LIGHT/DEEP/REM stage.
 */
class WakeLandingCalculator(private val config: SleepConfig = SleepConfig.DEFAULT) {
    /** null unless event.kind == PLANNED and [night] is present. */
    fun land(event: AlarmEvent, night: Night?): WakeLanding? {
        if (event.kind != RingKind.PLANNED || night == null || !night.isPresent) return null
        val hypnogram = LandingHypnogram(night)
        val ring = event.firstRingAt
        val before = hypnogram.durations(ring - config.surfacing.landingLookback, ring)
        val window = windowStats(hypnogram, event)
        return WakeLanding(
            event = event,
            stageAtRing = hypnogram.stageAt(ring),
            majorityBefore = majority(before),
            minutesEarly = event.earlyBy.toMinutes().toInt(),
            windowDeepShare = window?.deepShare,
            windowNonDeepMinutes = window?.nonDeepMinutes,
            hasStages = hypnogram.hasStages,
        )
    }

    /** The scored stage with the most time (ties AWAKE > LIGHT > REM > DEEP); null when nothing is scored. */
    private fun majority(time: Map<LandingStage, Long>): LandingStage? {
        var best: LandingStage? = null
        var bestMs = 0L
        for (stage in MAJORITY_ORDER) {
            val ms = time[stage] ?: 0L
            if (ms > bestMs) {
                best = stage
                bestMs = ms
            }
        }
        return best
    }

    private class WindowStats(val deepShare: Double, val nonDeepMinutes: Int)

    private fun windowStats(hypnogram: LandingHypnogram, event: AlarmEvent): WindowStats? {
        val windowMs = Duration.between(event.windowStart, event.target).toMillis()
        if (windowMs <= 0) return null
        val time = hypnogram.durations(event.windowStart, event.target)
        val scoredMs = MAJORITY_ORDER.sumOf { time[it] ?: 0L }
        if (scoredMs < config.surfacing.landingMinWindowCoverage * windowMs) return null
        val deepMs = time[LandingStage.DEEP] ?: 0L
        return WindowStats(
            deepShare = deepMs.toDouble() / windowMs,
            nonDeepMinutes = ((scoredMs - deepMs) / MILLIS_PER_MINUTE).toInt(),
        )
    }

    private companion object {
        /** Scored stages in tie-break order: AWAKE > LIGHT > REM > DEEP. */
        val MAJORITY_ORDER = listOf(LandingStage.AWAKE, LandingStage.LIGHT, LandingStage.REM, LandingStage.DEEP)
        const val MILLIS_PER_MINUTE = 60_000L
    }
}

/** A night's stages (main and extra segments) as Wake landing reads them. */
private class LandingHypnogram(night: Night) {
    private val stages: List<SleepStage> =
        (night.main?.stages.orEmpty() + night.extraSegments.flatMap { it.stages }).sortedBy { it.start }
    private val sleepStart: Instant? = night.main?.start ?: night.fellAsleep
    private val sleepEnd: Instant? = night.main?.end ?: night.wokeUp

    val hasStages: Boolean = stages.any { it.type.isScoredSleep }

    fun stageAt(t: Instant): LandingStage {
        val stage = stages.firstOrNull { !t.isBefore(it.start) && t.isBefore(it.end) }
        if (stage != null) return stage.type.landing
        val start = sleepStart ?: return LandingStage.UNSCORED
        val end = sleepEnd ?: return LandingStage.UNSCORED
        return if (t.isBefore(start) || !t.isBefore(end)) LandingStage.AWAKE else LandingStage.UNSCORED
    }

    /** Milliseconds per stage in [from, to): stageAt is constant between consecutive stage and block boundaries. */
    fun durations(from: Instant, to: Instant): Map<LandingStage, Long> {
        val cuts = (stages.flatMap { listOf(it.start, it.end) } + listOfNotNull(sleepStart, sleepEnd))
            .filter { it.isAfter(from) && it.isBefore(to) }
        val points = (listOf(from, to) + cuts).distinct().sorted()
        val totals = HashMap<LandingStage, Long>()
        for (i in 0 until points.size - 1) {
            val stage = stageAt(points[i])
            totals[stage] = (totals[stage] ?: 0L) + Duration.between(points[i], points[i + 1]).toMillis()
        }
        return totals
    }

    private val SleepStageType.landing: LandingStage
        get() = when (this) {
            SleepStageType.AWAKE, SleepStageType.AWAKE_IN_BED, SleepStageType.OUT_OF_BED -> LandingStage.AWAKE
            SleepStageType.LIGHT -> LandingStage.LIGHT
            SleepStageType.DEEP -> LandingStage.DEEP
            SleepStageType.REM -> LandingStage.REM
            SleepStageType.SLEEPING, SleepStageType.UNKNOWN -> LandingStage.UNSCORED
        }
}
