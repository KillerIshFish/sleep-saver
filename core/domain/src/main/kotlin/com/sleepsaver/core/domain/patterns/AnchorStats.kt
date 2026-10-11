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

/**
 * Drop anchor statistics: latency, efficiency and the personal latency that replaces sleepLatency (§2.8).
 *
 * Only anchors of the night's own wake date count for it. Example: anchor 23:05, Garmin onset 23:21, woke 07:05 with
 * 7 h 20 m asleep → latency 16 min ("about 15 min"), efficiency 7 h 20 m / 8 h = 0.92.
 */
class AnchorStatsCalculator(private val config: SleepConfig = SleepConfig.DEFAULT) {

    /** The anchor that counts for [night]: the latest with at ≤ fellAsleep − minLatency (i.e. up to 30 min after onset); latest overall without onset. */
    fun choose(anchors: List<Anchor>, night: Night): Anchor? {
        val own = anchors.filter { it.wakeDate == night.wakeDate }
        val onset = night.fellAsleep ?: return own.maxByOrNull { it.at }
        val latest = onset - config.anchor.minLatency
        return own.filter { it.at <= latest }.maxByOrNull { it.at }
    }

    fun night(anchor: Anchor, night: Night?): AnchorNightStats {
        val anchorConfig = config.anchor
        val onset = night?.fellAsleep
        val latency = onset
            ?.let { Duration.between(anchor.at, it) }
            ?.takeIf { it >= anchorConfig.minLatency && it <= anchorConfig.maxLatency }
        val wokeUp = night?.wokeUp
        val efficiency = if (night != null && wokeUp != null && wokeUp > anchor.at) {
            val asleep = night.mainSleep + night.extraSleep
            (asleep.toMillis().toDouble() / Duration.between(anchor.at, wokeUp).toMillis()).coerceIn(0.0, 1.0)
        } else {
            null
        }
        return AnchorNightStats(
            wakeDate = anchor.wakeDate,
            anchor = anchor,
            latency = latency,
            latencyShown = latency?.let(::shown),
            efficiency = efficiency,
        )
    }

    /**
     * Valid latencies of the most recent personalMaxValues nights in (asOf − personalLookbackDays, asOf];
     * active when ≥ personalMinValues; median (type 7) clamped to [personalMin, personalMax], rounded to the minute.
     */
    fun personalLatency(anchors: List<Anchor>, nights: List<Night>, asOf: LocalDate): PersonalLatency {
        val anchorConfig = config.anchor
        val from = asOf.minusDays(anchorConfig.personalLookbackDays)
        val latencies = nights
            .filter { it.isPresent && it.wakeDate.isAfter(from) && !it.wakeDate.isAfter(asOf) }
            .associateBy { it.wakeDate }
            .values
            .sortedByDescending { it.wakeDate }
            .mapNotNull { night -> choose(anchors, night)?.let { night(it, night).latency } }
            .take(anchorConfig.personalMaxValues)
        if (latencies.isEmpty()) return PersonalLatency(values = 0, median = null, active = false)
        val medianMinutes = median(latencies.map { it.minutesExact() })
            .coerceIn(anchorConfig.personalMin.minutesExact(), anchorConfig.personalMax.minutesExact())
        return PersonalLatency(
            values = latencies.size,
            median = roundedMinutes(medianMinutes),
            active = latencies.size >= anchorConfig.personalMinValues,
        )
    }

    /** [latency] to the nearest shownRounding (half up), never below zero: 16 min → 15, 18 min → 20, −10 min → 0. */
    private fun shown(latency: Duration): Duration {
        val step: Long = config.anchor.shownRounding.toMillis()
        val rounded = Math.floorDiv(latency.toMillis() + step / 2, step) * step
        return Duration.ofMillis(maxOf(0L, rounded))
    }
}
