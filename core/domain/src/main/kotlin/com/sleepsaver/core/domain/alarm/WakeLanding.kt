package com.sleepsaver.core.domain.alarm

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night

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
 */
class WakeLandingCalculator(private val config: SleepConfig = SleepConfig.DEFAULT) {
    /** null unless event.kind == PLANNED and [night] is present. */
    fun land(event: AlarmEvent, night: Night?): WakeLanding? = TODO("B1: WakeLandingCalculator.land (§2.5)")
}
