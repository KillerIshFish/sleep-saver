package com.sleepsaver.feature.winddown.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration

/** Where a running guided tool is: segment [index] of [count], showing [cue] for [length]. */
internal data class RunPosition<C>(val index: Int, val count: Int, val cue: C, val length: Duration, val paused: Boolean = false)

/**
 * Plays a guided tool's segments in real time: reports each segment as it starts ([onPosition]), gives its haptic
 * cue, and ends with a [Haptics.pulse] and [onFinish]. Timing comes from the [clock], so a run keeps its pace when a
 * frame or a wake-up is late, and tests drive it with virtual time. It sleeps until the next segment boundary; the
 * screen animates within a segment.
 *
 * Example: 4 s in / 6 s out started at t = 0 → tick at 0 (in), double tick at 4 s (out), tick at 10 s (in) …
 */
internal class GuidedRunner<C>(
    private val scope: CoroutineScope,
    private val clock: GuidedClock,
    private val haptics: Haptics,
    private val onPosition: (RunPosition<C>) -> Unit,
    private val onFinish: suspend () -> Unit,
) {
    private var timeline: GuidedTimeline<C>? = null
    private var job: Job? = null
    private var startedAt = 0L
    private var pausedAt: Long? = null
    private var pausedMs = 0L
    private var shown = -1
    private var last: RunPosition<C>? = null

    /** A run is going (or paused). */
    val active: Boolean get() = timeline != null

    val paused: Boolean get() = pausedAt != null

    /** Starts [segments] from the top, replacing any run in progress. */
    fun start(segments: List<GuidedSegment<C>>) {
        stop()
        timeline = GuidedTimeline(segments)
        startedAt = clock.elapsedMillis()
        pausedMs = 0L
        shown = -1
        launch()
    }

    /** Holds the current segment; [resume] carries on where it stopped. */
    fun pause() {
        if (timeline == null || pausedAt != null) return
        job?.cancel()
        pausedAt = clock.elapsedMillis()
        last = last?.copy(paused = true)?.also(onPosition)
    }

    fun resume() {
        val at = pausedAt ?: return
        pausedMs += clock.elapsedMillis() - at
        pausedAt = null
        launch()
    }

    /** Ends the run without finishing it (no pulse, no [onFinish]). */
    fun stop() {
        job?.cancel()
        job = null
        timeline = null
        pausedAt = null
        last = null
    }

    private fun launch() {
        val timeline = timeline ?: return
        job = scope.launch {
            while (true) {
                val elapsed = clock.elapsedMillis() - startedAt - pausedMs
                val at = timeline.at(elapsed)
                if (at == null) {
                    this@GuidedRunner.timeline = null
                    last = null
                    haptics.pulse()
                    onFinish()
                    return@launch
                }
                val position = RunPosition(at.index, timeline.segments.size, at.segment.cue, at.segment.length)
                if (at.index != shown) {
                    shown = at.index
                    cue(at.segment.haptic)
                }
                if (position != last) {
                    last = position
                    onPosition(position)
                }
                delay((at.endMs - elapsed).coerceAtLeast(1L))
            }
        }
    }

    private fun cue(haptic: HapticCue) = when (haptic) {
        HapticCue.NONE -> Unit
        HapticCue.TICK -> haptics.tick()
        HapticCue.DOUBLE_TICK -> haptics.doubleTick()
        HapticCue.PULSE -> haptics.pulse()
    }
}
