package com.sleepsaver.core.notifications.testing

import com.sleepsaver.core.domain.model.CircadianAnchor
import com.sleepsaver.core.domain.model.DailyInsights
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.EnergyCurve
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.LastNightState
import com.sleepsaver.core.domain.model.ReminderCategory
import com.sleepsaver.core.domain.model.ScheduledHabit
import com.sleepsaver.core.domain.model.SleepDebt
import com.sleepsaver.core.domain.model.SleepNeedEstimateResult
import com.sleepsaver.core.domain.model.Streak
import com.sleepsaver.core.domain.model.TimeSpan
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Builders for tests that only need a plausible schedule/insights, all in UTC. */
object TestInsights {
    val TODAY: LocalDate = LocalDate.of(2026, 10, 6)

    fun at(date: LocalDate, time: String): Instant = date.atTime(LocalTime.parse(time)).toInstant(ZoneOffset.UTC)

    fun habit(
        type: HabitType,
        time: Instant,
        enabled: Boolean = true,
        reminder: Boolean = true,
        windowEnd: Instant? = null,
        category: ReminderCategory = categoryOf(type),
    ) = ScheduledHabit(type, time, windowEnd, enabled, reminder, category)

    fun schedule(date: LocalDate): EnergySchedule {
        val wake = date.atTime(7, 0).atZone(ZoneOffset.UTC)
        val nextWake = wake.plusDays(1)
        return EnergySchedule(
            date = date,
            zone = ZoneOffset.UTC,
            typicalWake = wake,
            actualWake = null,
            wakeZone = TimeSpan(wake.minusMinutes(30).toInstant(), wake.plusMinutes(30).toInstant()),
            tomorrowWakeZone = TimeSpan(nextWake.minusMinutes(30).toInstant(), nextWake.plusMinutes(30).toInstant()),
            targetBedtime = at(date, "22:45"),
            lastNightBedtime = at(date.minusDays(1), "22:45"),
            phases = emptyList(),
            curve = EnergyCurve(emptyList()),
            anchor = CircadianAnchor(midpointMinuteOfDay = 180.0, typicalWake = LocalTime.of(7, 0), nightsUsed = 0, isFallback = true),
        )
    }

    fun insights(now: Instant, habits: List<ScheduledHabit>, today: LocalDate = TODAY) = DailyInsights(
        today = today,
        now = now,
        zone = ZoneOffset.UTC,
        lastNight = null,
        lastNightState = LastNightState.NOT_EXPECTED_YET,
        debt = SleepDebt(today, Duration.ZERO, Duration.ZERO, emptyList(), emptyList()),
        debtChange = null,
        status = DebtStatus.LOW,
        streak = Streak(0, null),
        energyPotential = 100,
        schedule = schedule(today),
        currentPhase = null,
        upcomingPhases = emptyList(),
        habits = habits,
        nextHabit = null,
        missingNights = emptyList(),
        needEstimate = SleepNeedEstimateResult.NotEnoughData(0, 14, 0, 4),
    )

    private fun categoryOf(type: HabitType) = when (type) {
        HabitType.MORNING_LIGHT -> ReminderCategory.MORNING_LIGHT
        HabitType.CAFFEINE_CUTOFF, HabitType.LARGE_MEAL_CUTOFF, HabitType.ALCOHOL_CUTOFF, HabitType.EXERCISE_CUTOFF ->
            ReminderCategory.CUTOFFS
        HabitType.DIM_LIGHTS, HabitType.EVENING_ROUTINE -> ReminderCategory.WIND_DOWN
        HabitType.BEDTIME -> ReminderCategory.BEDTIME
        HabitType.NAP_WINDOW -> ReminderCategory.NAPS
    }
}
