package com.sleepsaver.feature.review.landing

import com.sleepsaver.core.data.repository.MovementTrace
import com.sleepsaver.core.domain.alarm.WakeLanding
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.feature.review.detail.StageSegment
import com.sleepsaver.feature.review.detail.stageSegments
import java.time.Instant

/**
 * What the Wake landing card draws: the watch's stages in a [SleepConfig.surfacing] `landingSpan` (90 min) centred
 * on the first ring, the surfacing window shaded, ticks for the ring, each snooze and the stop, and the phone's
 * movement in the same span.
 *
 * Example: a 6:30 alarm with a 30-min window that rang at 6:11 spans 5:26–6:56, shades 6:00–6:30 and ticks 6:11.
 * The window always fits: it is at most 45 min and ends at or after the ring.
 */
internal data class LandingChart(
    val start: Instant,
    val end: Instant,
    /** Stages of main sleep and the extra segments, clipped to the span. */
    val segments: List<StageSegment>,
    /** The surfacing window [windowStart, windowEnd); empty for a fixed-time alarm. */
    val windowStart: Instant,
    val windowEnd: Instant,
    val ring: Instant,
    /** Snooze taps inside the span. */
    val snoozes: List<Instant>,
    /** The Stop tap, when it falls inside the span. */
    val stop: Instant?,
    /** The phone's movement epochs inside the span. */
    val movement: List<MovementBar>,
) {
    val hasWindow: Boolean get() = windowEnd.isAfter(windowStart)

    /** One movement epoch: [level] is its activity relative to the span's busiest epoch (0..1). */
    data class MovementBar(val start: Instant, val end: Instant, val level: Float)

    companion object {
        /**
         * The chart for [landing] over [night]'s stages and [traces]' epochs. Activity is scaled to the busiest epoch
         * in the span, but never below `placementActiveActivity`, so a still phone's sensor noise stays a flat line
         * instead of being blown up to full height.
         */
        fun of(
            landing: WakeLanding,
            night: Night?,
            traces: List<MovementTrace> = emptyList(),
            config: SleepConfig = SleepConfig.DEFAULT,
        ): LandingChart {
            val event = landing.event
            val half = config.surfacing.landingSpan.dividedBy(2)
            val start = event.firstRingAt - half
            val end = event.firstRingAt + half
            fun inSpan(t: Instant) = !t.isBefore(start) && t.isBefore(end)

            val blocks = listOfNotNull(night?.main) + night?.extraSegments.orEmpty()
            val segments = blocks
                .flatMap(::stageSegments)
                .filter { it.end.isAfter(start) && it.start.isBefore(end) }
                .map { it.copy(start = maxOf(it.start, start), end = minOf(it.end, end)) }
                .sortedWith(compareBy({ it.start }, { it.end }))

            val epoch = config.surfacing.epoch
            val epochs = traces
                .flatMap { it.epochs }
                .filter { it.activity != null && inSpan(it.start) }
                .distinctBy { it.start }
                .sortedBy { it.start }
            val busiest = epochs.maxOfOrNull { it.activity ?: 0.0 } ?: 0.0
            val ceiling = maxOf(busiest, config.surfacing.placementActiveActivity)
            val movement = epochs.map { e ->
                MovementBar(e.start, minOf(e.start + epoch, end), ((e.activity ?: 0.0) / ceiling).toFloat().coerceIn(0f, 1f))
            }

            return LandingChart(
                start = start,
                end = end,
                segments = segments,
                windowStart = event.windowStart,
                windowEnd = event.target,
                ring = event.firstRingAt,
                snoozes = event.snoozeTimes.filter(::inSpan),
                stop = event.stoppedAt?.takeIf(::inSpan),
                movement = movement,
            )
        }
    }
}
