package com.sleepsaver.core.domain.circadian

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.CircadianAnchor
import com.sleepsaver.core.domain.model.Night
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/**
 * Body-clock timing from main-sleep midpoints (docs/PLAN.md §3.5). Naps and extra segments are ignored.
 *
 *  - Candidates: present nights with a main sleep (or manual night) and wakeDate in (asOf − anchorLookbackDays, asOf],
 *    most recent first, at most `anchorMaxNights`. Effective fellAsleep/wokeUp are used (edits count).
 *  - midpoint local minute-of-day m_i uses the night's own offsets (midpoint instant shown in wokeUpOffset).
 *  - w_i = anchorDecay^(i−1), θ_i = 2π m_i / 1440, anchor = atan2(Σ w sin θ, Σ w cos θ) mapped to [0, 1440).
 *  - Fewer than `anchorMinNights` → fallback: anchor = fallbackWake − need/2, isFallback = true.
 *  - typicalWake = anchor + need/2 (mod 24 h), rounded to the minute.
 */
class CircadianAnchorEstimator(private val config: SleepConfig = SleepConfig.DEFAULT) {
    fun estimate(nights: List<Night>, asOf: LocalDate, need: Duration, fallbackWake: LocalTime): CircadianAnchor {
        TODO("M2: circadian anchor")
    }
}
