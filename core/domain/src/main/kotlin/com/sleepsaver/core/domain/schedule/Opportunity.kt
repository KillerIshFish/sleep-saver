package com.sleepsaver.core.domain.schedule

import java.time.Duration
import java.time.Instant

/**
 * Planned time asleep: wake − (bedtime + latency), never negative. Used by the debt preview and debt-aware windows.
 * Example: bedtime 23:00, latency 15 min, wake 06:30 → 7 h 15 min.
 */
fun sleepOpportunity(bedtime: Instant, wake: Instant, latency: Duration): Duration =
    maxOf(Duration.ZERO, Duration.between(bedtime + latency, wake))

/**
 * Expected sleep onset tonight: anchor + latency, else planned bedtime + latency, else target − need.
 * Example: Drop anchor at 23:10 with a 15-min latency → 23:25, whatever the planned bedtime.
 */
fun expectedOnset(anchorAt: Instant?, plannedBedtime: Instant?, latency: Duration, target: Instant, need: Duration): Instant =
    anchorAt?.plus(latency) ?: plannedBedtime?.plus(latency) ?: target.minus(need)
