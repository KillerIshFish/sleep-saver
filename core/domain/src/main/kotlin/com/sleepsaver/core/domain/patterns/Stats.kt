package com.sleepsaver.core.domain.patterns

import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

// Shared statistics for the v2 engines (Keel, Marks, odd night, prior, decider), so every engine uses the same methods.
// Minutes of day are in [0, 1440); circular methods treat 23:30 and 00:30 as one hour apart.

/** Type-7 quantile of an ascending list: linear interpolation at h = (n − 1)·p. quantile7([1, 2, 3, 4], 0.5) = 2.5. */
internal fun quantile7(sorted: List<Double>, p: Double): Double {
    require(sorted.isNotEmpty()) { "quantile of an empty list" }
    val h = (sorted.size - 1) * p.coerceIn(0.0, 1.0)
    val low = floor(h).toInt()
    val high = minOf(low + 1, sorted.size - 1)
    return sorted[low] + (h - low) * (sorted[high] - sorted[low])
}

/** Median (type 7) of unsorted values. An empty list throws. */
internal fun median(values: List<Double>): Double = quantile7(values.sorted(), 0.5)

/** Unweighted circular mean of minutes of day, in [0, 1440). An empty list throws. */
internal fun circularMeanMinutes(minutes: List<Double>): Double {
    require(minutes.isNotEmpty()) { "circular mean of an empty list" }
    var sinSum = 0.0
    var cosSum = 0.0
    for (minute in minutes) {
        val theta = 2 * PI * minute / MINUTES_PER_DAY
        sinSum += sin(theta)
        cosSum += cos(theta)
    }
    val mean = atan2(sinSum, cosSum) * MINUTES_PER_DAY / (2 * PI)
    return ((mean % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
}

/**
 * Circular standard deviation in minutes: R = |Σ e^{iθ}| / n, θ = 2π·m/1440, sd = sqrt(−2·ln R)·1440/(2π); 0 when
 * R ≥ 1. An empty list throws.
 */
internal fun circularSdMinutes(minutes: List<Double>): Double {
    require(minutes.isNotEmpty()) { "circular SD of an empty list" }
    var sinSum = 0.0
    var cosSum = 0.0
    for (minute in minutes) {
        val theta = 2 * PI * minute / MINUTES_PER_DAY
        sinSum += sin(theta)
        cosSum += cos(theta)
    }
    val r = hypot(sinSum, cosSum) / minutes.size
    if (r >= 1.0) return 0.0
    return sqrt(-2 * ln(r)) * MINUTES_PER_DAY / (2 * PI)
}

/** A difference of minutes of day wrapped into (−720, 720]: 1380 → −60, −720 → 720. */
internal fun wrapMinutes(diff: Double): Double {
    val wrapped = ((diff % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
    return if (wrapped > MINUTES_PER_DAY / 2) wrapped - MINUTES_PER_DAY else wrapped
}

/** Minutes since noon, so an evening onset and the small hours sort together: 22:00 → 600, 01:00 → 780, 11:00 → 1380. */
internal fun noonShiftedMinutes(t: LocalTime): Int = Math.floorMod(t.toSecondOfDay() / 60 - 720, 1440)

private const val MINUTES_PER_DAY = 1440.0
