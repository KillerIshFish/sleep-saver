package com.sleepsaver.core.domain.config

import java.time.Duration

/**
 * Drop anchor, latency, efficiency, the night state, debt preview (docs/PLAN.md §3.16). Example: anchor 23:05 and
 * Garmin onset 23:21 give a latency of 16 min, shown as "about 15 min" ([shownRounding]).
 */
data class AnchorConfig(
    /** "Undo" stays offered this long after Drop anchor. */
    val undoWindow: Duration = Duration.ofSeconds(10),
    /** Latency = Garmin onset − anchor; ignored outside [minLatency, maxLatency]. */
    val minLatency: Duration = Duration.ofMinutes(-30),
    val maxLatency: Duration = Duration.ofMinutes(180),
    val shownRounding: Duration = Duration.ofMinutes(5),
    /** The personal latency needs [personalMinValues] values (of the last [personalMaxValues] within [personalLookbackDays]). */
    val personalMinValues: Int = 7,
    val personalMaxValues: Int = 30,
    val personalLookbackDays: Long = 60,
    /** The personal latency is clamped to [personalMin, personalMax]. */
    val personalMin: Duration = Duration.ofMinutes(5),
    val personalMax: Duration = Duration.ofMinutes(45),
    /** The night state ends by itself this long after the anchor. */
    val nightMaxLength: Duration = Duration.ofHours(14),
    /** The debt preview reads "enough history" with this many present nights. */
    val debtPreviewMinNights: Int = 3,
    /** Predicted awake time = median awake of the last this many present nights. */
    val debtPreviewAwakeNights: Int = 14,
    /** enoughHistory counts present nights with wakeDate in (today − this, today]. */
    val debtPreviewHistoryDays: Long = 14,
    // --- B2 additions (append below this line only)
)
