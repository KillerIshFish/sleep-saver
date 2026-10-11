package com.sleepsaver.core.domain.alarm

import com.sleepsaver.core.domain.config.SleepConfig
import java.time.Instant

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
 */
class MovementMeter(private val config: SleepConfig = SleepConfig.DEFAULT) {

    /** Feed one sample (m/s², device axes). Returns the epochs that closed (oldest first), incl. missing slots skipped over. */
    fun add(time: Instant, x: Float, y: Float, z: Float): List<MovementEpoch> =
        TODO("B1: MovementMeter.add (rate-independent epochs, §2.5)")

    /** Closes the open slot: activity when its coverage ≥ epochMinCoverage, else a missing epoch; null when nothing is open. */
    fun flush(): MovementEpoch? = TODO("B1: MovementMeter.flush (§2.5)")
}
