package com.sleepsaver.core.domain.insights

import com.sleepsaver.core.domain.circadian.SleepTimingEstimator
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.debt.SleepDebtCalculator
import com.sleepsaver.core.domain.energy.EnergyScheduleCalculator
import com.sleepsaver.core.domain.habits.HabitCatalog
import com.sleepsaver.core.domain.habits.HabitScheduler
import com.sleepsaver.core.domain.model.DailyInsights
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.LastNightState
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.PhaseType
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
 *  - schedule = today's schedule with shifts vs yesterday's. Each day's schedule is computed only from nights with
 *    wakeDate ≤ that day and with the debt shown on that day, i.e. as the user saw it then.
 *  - Night owls after midnight: while now is before the end of *yesterday's* melatonin window (e.g. W 10:30 →
 *    bedtime 02:15, window until 02:45), tonight's wind-down and bedtime still belong to yesterday's schedule, so
 *    schedule/currentPhase/upcomingPhases/habits/nextHabit come from yesterday's schedule (with its own shifts vs
 *    the day before). Everything else (today, lastNight, lastNightState, debt, ...) stays on the calendar date.
 *    Check [DailyInsights.schedule]'s date to tell the two apart.
 *  - habits from HabitScheduler; nextHabit = first upcoming enabled one.
 *  - missingNights = SleepDebt.missingNights of the displayed debt, excluding today while WAITING/NOT_EXPECTED_YET
 *    and every date before the first present night ever (a new user has nothing to add yet).
 *  - needEstimate from SleepNeedEstimator.
 *
 * debtChange is null when the day before the displayed one has no present night in its debt window (no history yet).
 */
class InsightsEngine(private val config: SleepConfig = SleepConfig.DEFAULT) {

    private val debtCalculator = SleepDebtCalculator(config)
    private val needEstimator = SleepNeedEstimator(config)
    private val scheduleCalculator = EnergyScheduleCalculator(config)
    private val habitScheduler = HabitScheduler(HabitCatalog(config))
    private val timingEstimator = SleepTimingEstimator(config)

    fun compute(
        nights: List<Night>,
        settings: UserSleepSettings,
        habitSettings: List<HabitSettings>,
        now: Instant,
        zone: ZoneId,
    ): DailyInsights {
        // Not LocalDate.ofInstant: that needs API 34 and minSdk is 28.
        val today = now.atZone(zone).toLocalDate()
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

        fun scheduleOn(day: LocalDate): EnergySchedule = scheduleCalculator.schedule(
            date = day,
            zone = zone,
            nights = nights,
            need = need,
            debt = debtCalculator.debtAsOf(displayedDebtDate(day, nightsByDate), nightsByDate, need).debt,
            timing = settings.timing,
        )
        val todaySchedule = scheduleOn(today)
        val yesterdaySchedule = scheduleOn(yesterday)
        val yesterdayStillOn = yesterdaySchedule.phase(PhaseType.MELATONIN_WINDOW)?.let { now < it.end } == true
        val schedule = if (yesterdayStillOn) {
            scheduleCalculator.withShifts(yesterdaySchedule, scheduleOn(yesterday.minusDays(1)))
        } else {
            scheduleCalculator.withShifts(todaySchedule, yesterdaySchedule)
        }

        val lastNightState = when {
            lastNight != null -> LastNightState.AVAILABLE
            now < todaySchedule.typicalWake.toInstant() - config.waitingStartsBeforeWake -> LastNightState.NOT_EXPECTED_YET
            now.atZone(zone).toLocalTime() < config.waitingGiveUpAt -> LastNightState.WAITING
            else -> LastNightState.MISSING
        }
        val stillExpected = lastNightState == LastNightState.WAITING || lastNightState == LastNightState.NOT_EXPECTED_YET
        val firstNight = nights.filter { it.isPresent }.minOfOrNull { it.wakeDate }

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
            missingNights = debt.missingNights.filter { date ->
                firstNight != null && !date.isBefore(firstNight) && !(stillExpected && date == today)
            },
            needEstimate = needEstimator.estimate(nights, need, today),
            learnedTiming = timingEstimator.learned(nights, today),
        )
    }

    /** The day whose debt is shown on [day]: [day] once its night is in, otherwise the day before. */
    private fun displayedDebtDate(day: LocalDate, nightsByDate: Map<LocalDate, Night>): LocalDate =
        if (nightsByDate[day]?.isPresent == true) day else day.minusDays(1)

    private fun hasDebtHistory(asOf: LocalDate, nightsByDate: Map<LocalDate, Night>): Boolean =
        (0L until config.debtWindowNights).any { nightsByDate[asOf.minusDays(it)]?.isPresent == true }
}
