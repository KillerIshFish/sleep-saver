package com.sleepsaver.core.domain.insights

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.DailyInsights
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.UserSleepSettings
import java.time.Instant
import java.time.ZoneId

/**
 * One call that produces everything the home screen needs. Pure: same inputs → same output.
 *
 *  - today = now in [zone] as a LocalDate. lastNight = nights[wakeDate == today] if present.
 *  - lastNightState: AVAILABLE if present; else compare now with today's typical wake W (from the anchor):
 *    now < W − waitingStartsBeforeWake → NOT_EXPECTED_YET; local time < waitingGiveUpAt → WAITING; else MISSING.
 *  - debt shown = debt(today) if present else debt(today − 1); debtChange vs the day before that.
 *  - status/energyPotential from that debt; streak as of the same day.
 *  - schedule = today's schedule with shifts vs yesterday's (yesterday computed only from nights with wakeDate ≤ today − 1).
 *  - habits from HabitScheduler; nextHabit = first upcoming enabled one.
 *  - missingNights = SleepDebt.missingNights of the displayed debt, excluding today while WAITING/NOT_EXPECTED_YET.
 *  - needEstimate from SleepNeedEstimator.
 */
class InsightsEngine(private val config: SleepConfig = SleepConfig.DEFAULT) {
    fun compute(
        nights: List<Night>,
        settings: UserSleepSettings,
        habitSettings: List<HabitSettings>,
        now: Instant,
        zone: ZoneId,
    ): DailyInsights {
        TODO("M2: insights roll-up")
    }
}
