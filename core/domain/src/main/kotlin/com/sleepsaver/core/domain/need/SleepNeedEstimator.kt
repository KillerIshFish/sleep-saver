package com.sleepsaver.core.domain.need

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepNeedEstimate
import com.sleepsaver.core.domain.model.SleepNeedEstimateResult
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Estimates sleep need from "free" nights (docs/PLAN.md §3.2).
 *
 *  - Uses present nights with wakeDate ≤ today, at most `needEstimateLookbackNights` most recent.
 *  - Requires ≥ `needEstimateMinNights` present nights.
 *  - typical weekday wake = median local wake time (Night.wokeUp in wokeUpOffset) over nights whose
 *    wakeDate is Mon–Fri.
 *  - free night = wake ≥ typical weekday wake + `freeNightWakeDelay` (compare as local time of day).
 *  - drop a free night if the night before it (wakeDate − 1) is present and its total < currentNeed − `shortNightMargin`.
 *  - estimate = median totalSleep of remaining free nights, rounded to `needRounding`, clamped [needClampMin, needClampMax].
 *  - requires ≥ `needMinFreeNights`; confidence from count + IQR (see SleepConfig).
 */
class SleepNeedEstimator(private val config: SleepConfig = SleepConfig.DEFAULT) {

    fun estimate(nights: List<Night>, currentNeed: Duration, today: LocalDate): SleepNeedEstimateResult {
        TODO("M2: sleep need estimate")
    }

    /**
     * Whether to show the suggestion card. Never when the estimate is within `needReofferMinChange` of the
     * current need. After the user ignored [lastDismissedValue] at [lastDismissedAt], offer again only when
     * the estimate differs from that value by ≥ `needReofferMinChange` AND ≥ `needReofferAfterDays` have passed.
     */
    fun shouldOffer(
        estimate: SleepNeedEstimate,
        currentNeed: Duration,
        lastDismissedValue: Duration?,
        lastDismissedAt: Instant?,
        now: Instant,
    ): Boolean {
        TODO("M2: need suggestion gating")
    }
}
