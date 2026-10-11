package com.sleepsaver.core.domain.alarm

import com.sleepsaver.core.domain.config.SleepConfig
import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** One 30-s slot of phone movement. [activity] null = missing (no or too few samples). */
data class MovementEpoch(val start: Instant, val activity: Double?)

/**
 * Accelerometer samples → epochs. Stateful, pure (no Android). Rate-independent: the same motion at 10, 25 or 50 Hz
 * gives the same activity.
 *
 *  - Slots are aligned to the instant: `slotStart(t) = floorDiv(t.epochMs, epochMs) · epochMs` (Long overload).
 *  - A sample whose time is ≤ the previous sample's time is dropped.
 *  - Per-axis baseline b, seeded by the first sample. For sample i: Δt_i = clamp(t_i − t_{i−1}, 0, maxSampleGap)
 *    (0 for the first); d_i = max(0, |a_i − b| − noiseFloor), computed before the update; then
 *    b ← b + α(a_i − b) with α = 1 − exp(−Δt_i / baselineTau). Δt_i and d_i·Δt_i go to sample i's own slot.
 *  - Epoch activity = (Σ d_i·Δt_i / Σ Δt_i) × activityScale; coverage = Σ Δt_i / epoch.
 *  - A sample in a later slot closes the open slot (activity if coverage ≥ epochMinCoverage, else missing), emits
 *    every skipped slot as missing and opens the new slot.
 *  - A flushed slot is never emitted twice: later samples in it only move the baseline, and the first sample in a
 *    later slot emits the slots skipped since the flush as missing.
 *
 * Example: a phone lying still on the mattress reads about 2; rolling it 30° reads in the hundreds.
 */
class MovementMeter(private val config: SleepConfig = SleepConfig.DEFAULT) {

    private val epochMs: Long = config.surfacing.epoch.toMillis()
    private val maxGapMs: Long = config.surfacing.maxSampleGap.toMillis()
    private val tauMs: Double = config.surfacing.baselineTau.toMillis().toDouble()

    private var lastTime: Instant? = null
    private var baseX = 0.0
    private var baseY = 0.0
    private var baseZ = 0.0

    /** Start (epoch ms) of the open slot; null when none is open. */
    private var openStart: Long? = null

    /** Start of the last closed slot; null before the first one closes. */
    private var lastClosedStart: Long? = null
    private var coveredMs: Long = 0
    private var weighted: Double = 0.0

    /** Feed one sample (m/s², device axes). Returns the epochs that closed (oldest first), incl. missing slots skipped over. */
    fun add(time: Instant, x: Float, y: Float, z: Float): List<MovementEpoch> {
        val previous = lastTime
        if (previous != null && !time.isAfter(previous)) return emptyList()
        val dtMs = if (previous == null) 0L else minOf(Duration.between(previous, time).toMillis(), maxGapMs)
        if (previous == null) {
            baseX = x.toDouble()
            baseY = y.toDouble()
            baseZ = z.toDouble()
        }
        val dx = x - baseX
        val dy = y - baseY
        val dz = z - baseZ
        val deviation = max(0.0, sqrt(dx * dx + dy * dy + dz * dz) - config.surfacing.noiseFloor)
        val alpha = 1 - exp(-dtMs / tauMs)
        baseX += alpha * dx
        baseY += alpha * dy
        baseZ += alpha * dz
        lastTime = time

        val slot = slotStart(time)
        val closed = ArrayList<MovementEpoch>()
        val open = openStart
        if (open == null) {
            val lastClosed = lastClosedStart
            if (lastClosed != null && slot <= lastClosed) return closed // the flushed slot stays closed
            if (lastClosed != null) closed += missingSlots(lastClosed + epochMs, slot)
            openStart = slot
        } else if (slot > open) {
            closed += close(open)
            closed += missingSlots(open + epochMs, slot)
            openStart = slot
        }
        coveredMs += dtMs
        weighted += deviation * dtMs
        return closed
    }

    /** Closes the open slot: activity when its coverage ≥ epochMinCoverage, else a missing epoch; null when nothing is open. */
    fun flush(): MovementEpoch? = openStart?.let(::close)

    private fun slotStart(time: Instant): Long = Math.floorDiv(time.toEpochMilli(), epochMs) * epochMs

    private fun close(start: Long): MovementEpoch {
        val covered = coveredMs > 0 && coveredMs >= config.surfacing.epochMinCoverage * epochMs
        val activity = if (covered) weighted / coveredMs * config.surfacing.activityScale else null
        openStart = null
        lastClosedStart = start
        coveredMs = 0
        weighted = 0.0
        return MovementEpoch(Instant.ofEpochMilli(start), activity)
    }

    /** Missing epochs for the slots starting in [from, until). */
    private fun missingSlots(from: Long, until: Long): List<MovementEpoch> {
        val missing = ArrayList<MovementEpoch>()
        var start = from
        while (start < until) {
            missing += MovementEpoch(Instant.ofEpochMilli(start), null)
            start += epochMs
        }
        return missing
    }
}
