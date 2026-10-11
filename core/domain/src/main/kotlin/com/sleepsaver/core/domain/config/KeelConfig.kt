package com.sleepsaver.core.domain.config

import java.time.Duration

/**
 * Keel (SRI), social jetlag, planned keel (docs/PLAN.md §3.15). Example: a score of 83 over [windowDays] reads
 * "Even keel" (≥ [even]); with fewer than [minValidDays] nights it reads "Calibrating".
 */
data class KeelConfig(
    val windowDays: Int = 14,
    val minValidDays: Int = 7,
    /** Bands: ≥ even → Even keel, ≥ fair → Fair, else Choppy. */
    val even: Int = 80,
    val fair: Int = 70,
    val includeNaps: Boolean = false,
    /** Onset and wake SD need at least this many nights. */
    val sdMinNights: Int = 3,
    /** Social jetlag: nights of the last [jetlagLookbackDays], at least [jetlagMinFree] free and [jetlagMinWork] alarm days. */
    val jetlagLookbackDays: Long = 28,
    val jetlagMinFree: Int = 2,
    val jetlagMinWork: Int = 3,
    /** The planned keel looks this many wake dates ahead. */
    val plannedDays: Int = 14,
    /** Plan check warnings: free days wake later by more than this, or |planned social jetlag| reaches [jetlagWarning]. */
    val freeDayShiftWarning: Duration = Duration.ofMinutes(60),
    val jetlagWarning: Duration = Duration.ofMinutes(60),
    /** |social jetlag| below this reads "Free days and alarm days line up well." */
    val jetlagAlignedBelow: Duration = Duration.ofMinutes(15),
    // --- B2 additions (append below this line only)
)
