package com.sleepsaver.core.domain.insights

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.debt.SleepDebtCalculator
import com.sleepsaver.core.domain.energy.EnergyScheduleCalculator
import com.sleepsaver.core.domain.habits.HabitCatalog
import com.sleepsaver.core.domain.habits.HabitScheduler
import com.sleepsaver.core.domain.model.DailyInsights
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.LastNightState
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.need.SleepNeedEstimator
import java.time.Instant
import java.time.LocalDate
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
 *
 * debtChange is null when the day before the displayed one has no present night in its debt window (no history yet).
 */
class InsightsEngine(private val config: SleepConfig = SleepConfig.DEFAULT) {

    private val debtCalculator = SleepDebtCalculator(config)
    private val needEstimator = SleepNeedEstimator(config)
    private val scheduleCalculator = EnergyScheduleCalculator(config)
    private val habitScheduler = HabitScheduler(HabitCatalog(config))

    fun compute(
        nights: List<Night>,
        settings: UserSleepSettings,
        habitSettings: List<HabitSettings>,
        now: Instant,
        zone: ZoneId,
    ): DailyInsights {
        val today = LocalDate.ofInstant(now, zone)
        val yesterday = today.minusDays(1)
        val need = settings.sleepNeed
        val nightsByDate = nights.associateBy { it.wakeDate }
        val lastNight = nightsByDate[today]?.takeIf { it.isPresent }

        val debtDate = displayedDebtDate(today, nightsByDate)
        val debt = debtCalculator.debtAsOf(debtDate, nightsByDate, need)
        val debtChange = if (hasDebtHistory(debtDate.minusDays(1), nightsByDate)) {
            debt.debt - debtCalculator.debtAsOf(debtDate.minusDays(1), nightsByDate, need).debt
        } else {
            null
        }

        val yesterdayNights = nights.filter { !it.wakeDate.isAfter(yesterday) }
        val yesterdayDebt = debtCalculator.debtAsOf(displayedDebtDate(yesterday, nightsByDate), nightsByDate, need).debt
        val schedule = scheduleCalculator.withShifts(
            today = scheduleCalculator.schedule(today, zone, nights, need, debt.debt, settings.typicalWake),
            yesterday = scheduleCalculator.schedule(yesterday, zone, yesterdayNights, need, yesterdayDebt, settings.typicalWake),
        )

        val lastNightState = when {
            lastNight != null -> LastNightState.AVAILABLE
            now < schedule.typicalWake.toInstant() - config.waitingStartsBeforeWake -> LastNightState.NOT_EXPECTED_YET
            now.atZone(zone).toLocalTime() < config.waitingGiveUpAt -> LastNightState.WAITING
            else -> LastNightState.MISSING
        }
        val stillExpected = lastNightState == LastNightState.WAITING || lastNightState == LastNightState.NOT_EXPECTED_YET

        val habits = habitScheduler.schedule(schedule, habitSettings)
        return DailyInsights(
            today = today,
            now = now,
            zone = zone,
            lastNight = lastNight,
            lastNightState = lastNightState,
            debt = debt,
            debtChange = debtChange,
            status = debtCalculator.status(debt.debt, settings.lowDebtMax, settings.highDebtMin),
            streak = debtCalculator.streak(debtDate, nightsByDate, need, settings.streakThreshold),
            energyPotential = debtCalculator.energyPotential(debt.debt),
            schedule = schedule,
            currentPhase = schedule.currentPhase(now),
            upcomingPhases = schedule.upcomingPhases(now),
            habits = habits,
            nextHabit = habitScheduler.nextUp(habits, now),
            missingNights = debt.missingNights.filterNot { stillExpected && it == today },
            needEstimate = needEstimator.estimate(nights, need, today),
        )
    }

    /** The day whose debt is shown on [day]: [day] once its night is in, otherwise the day before. */
    private fun displayedDebtDate(day: LocalDate, nightsByDate: Map<LocalDate, Night>): LocalDate =
        if (nightsByDate[day]?.isPresent == true) day else day.minusDays(1)

    private fun hasDebtHistory(asOf: LocalDate, nightsByDate: Map<LocalDate, Night>): Boolean =
        (0L until config.debtWindowNights).any { nightsByDate[asOf.minusDays(it)]?.isPresent == true }
}
