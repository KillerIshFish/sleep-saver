package com.sleepsaver.core.domain.patterns

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Anchor
import com.sleepsaver.core.domain.model.Night
import java.time.Duration
import java.time.LocalDate

/** A night's Drop anchor numbers: "You fell asleep about 15 min after dropping anchor." */
data class AnchorNightStats(
    val wakeDate: LocalDate,
    val anchor: Anchor,
    /** onset − anchor; null when outside [minLatency, maxLatency] or no onset. */
    val latency: Duration?,
    /** latency rounded to shownRounding, never below zero ("about 15 min"). */
    val latencyShown: Duration?,
    /** asleep (main + extra) / (wokeUp − anchor), 0..1; null when wokeUp ≤ anchor. */
    val efficiency: Double?,
)

/** The personal latency: the median of [values] valid latencies; [active] once there are personalMinValues of them. */
data class PersonalLatency(val values: Int, val median: Duration?, val active: Boolean)

/** Drop anchor statistics: latency, efficiency and the personal latency that replaces sleepLatency (§2.8). */
class AnchorStatsCalculator(private val config: SleepConfig = SleepConfig.DEFAULT) {

    /** The anchor that counts for [night]: the latest with at ≤ fellAsleep − minLatency (i.e. up to 30 min after onset); latest overall without onset. */
    fun choose(anchors: List<Anchor>, night: Night): Anchor? = TODO("B2: AnchorStatsCalculator.choose (§2.8)")

    fun night(anchor: Anchor, night: Night?): AnchorNightStats = TODO("B2: AnchorStatsCalculator.night (§2.8)")

    /**
     * Valid latencies of the most recent personalMaxValues nights in (asOf − personalLookbackDays, asOf];
     * active when ≥ personalMinValues; median (type 7) clamped to [personalMin, personalMax], rounded to the minute.
     */
    fun personalLatency(anchors: List<Anchor>, nights: List<Night>, asOf: LocalDate): PersonalLatency =
        TODO("B2: AnchorStatsCalculator.personalLatency (§2.8)")
}
