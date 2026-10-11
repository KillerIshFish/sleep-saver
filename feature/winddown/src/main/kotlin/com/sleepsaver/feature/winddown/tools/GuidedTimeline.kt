package com.sleepsaver.feature.winddown.tools

import com.sleepsaver.core.domain.config.SleepConfig
import java.time.Duration

/** The haptic cue a segment starts with (see [Haptics]). */
internal enum class HapticCue { NONE, TICK, DOUBLE_TICK, PULSE }

/** One timed part of a guided tool: [cue] for [length], announced with [haptic]. Example: "Breathe in" for 4 s, one tick. */
internal data class GuidedSegment<C>(val cue: C, val length: Duration, val haptic: HapticCue)

/** Segment [index] of a timeline, running from [startMs] to [endMs] after the start. */
internal data class SegmentPosition<C>(val index: Int, val segment: GuidedSegment<C>, val startMs: Long, val endMs: Long)

/** Segments back to back. Example: in 4 s, out 6 s → 5 s after the start is segment 1 (out), 4 s into the run. */
internal class GuidedTimeline<C>(val segments: List<GuidedSegment<C>>) {
    /** Cumulative end of each segment, in ms after the start. */
    private val ends: LongArray = LongArray(segments.size).also { ends ->
        var total = 0L
        segments.forEachIndexed { index, segment ->
            total += segment.length.toMillis().coerceAtLeast(0)
            ends[index] = total
        }
    }

    val totalMs: Long get() = ends.lastOrNull() ?: 0L

    /** The segment running [elapsedMs] after the start; null once the timeline is over (or before it starts). */
    fun at(elapsedMs: Long): SegmentPosition<C>? {
        if (elapsedMs < 0 || elapsedMs >= totalMs) return null
        // The first segment whose end is after elapsedMs (zero-length segments are skipped).
        var low = 0
        var high = ends.size - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (ends[mid] > elapsedMs) high = mid else low = mid + 1
        }
        val start = if (low == 0) 0L else ends[low - 1]
        return SegmentPosition(low, segments[low], start, ends[low])
    }
}

// ---------------------------------------------------------------- Scripts

/** Slow tide's two halves of a breath. */
internal enum class BreathPhase { IN, OUT }

/** A breathing pace: "4 in · 6 out" (about six breaths a minute). */
internal data class BreathPattern(val breatheIn: Duration, val breatheOut: Duration) {
    val cycle: Duration get() = breatheIn + breatheOut
}

/** `steps.slowTidePatterns` as patterns; the first is the default. */
internal fun SleepConfig.slowTidePatterns(): List<BreathPattern> =
    steps.slowTidePatterns.map { (breatheIn, breatheOut) -> BreathPattern(breatheIn, breatheOut) }

/**
 * Slow tide: whole breaths filling at least [length] (10 min at 4 in · 6 out = 60 breaths). One tick at each
 * in-breath, two at each out-breath.
 */
internal fun slowTideScript(pattern: BreathPattern, length: Duration): List<GuidedSegment<BreathPhase>> {
    val cycleMs = pattern.cycle.toMillis().coerceAtLeast(1)
    val breaths = ((length.toMillis() + cycleMs - 1) / cycleMs).toInt().coerceAtLeast(1)
    return List(breaths * 2) { index ->
        if (index % 2 == 0) {
            GuidedSegment(BreathPhase.IN, pattern.breatheIn, HapticCue.TICK)
        } else {
            GuidedSegment(BreathPhase.OUT, pattern.breatheOut, HapticCue.DOUBLE_TICK)
        }
    }
}

/** The ten body-scan areas, feet to whole body (`winddown_scan_1..10`). */
internal const val BODY_SCAN_AREAS = 10

/** Body scan: [BODY_SCAN_AREAS] prompts of `steps.bodyScanStep` (cue = area index 0..9), each starting with a pulse. */
internal fun bodyScanScript(config: SleepConfig = SleepConfig.DEFAULT): List<GuidedSegment<Int>> =
    List(BODY_SCAN_AREAS) { area -> GuidedSegment(area, config.steps.bodyScanStep, HapticCue.PULSE) }

/** The twelve muscle groups, hands to feet. */
internal enum class MuscleGroup { HANDS, FOREARMS, UPPER_ARMS, SHOULDERS, FACE, NECK, CHEST, STOMACH, BACK, THIGHS, CALVES, FEET }

/** Muscle release: tense [group], or let it go. */
internal data class MuscleCue(val group: MuscleGroup, val tense: Boolean)

/**
 * Muscle release: each group tensed for `steps.muscleTense` (one tick), then released for `steps.muscleRelease`
 * (two ticks). 12 groups × 20 s = 4 min.
 */
internal fun muscleReleaseScript(config: SleepConfig = SleepConfig.DEFAULT): List<GuidedSegment<MuscleCue>> =
    MuscleGroup.entries.flatMap { group ->
        listOf(
            GuidedSegment(MuscleCue(group, tense = true), config.steps.muscleTense, HapticCue.TICK),
            GuidedSegment(MuscleCue(group, tense = false), config.steps.muscleRelease, HapticCue.DOUBLE_TICK),
        )
    }
