package com.sleepsaver.feature.energy

import com.sleepsaver.core.domain.model.DailyInsights
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.LastNightState
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.ScheduledHabit
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface EnergyUiState {
    data object Loading : EnergyUiState

    /** No sleep at all for the active source yet. */
    data object NoNights : EnergyUiState

    data class Ready(
        val today: LocalDate,
        val now: Instant,
        val zone: ZoneId,
        val energyPotential: Int,
        val status: DebtStatus,
        val lastNightState: LastNightState,
        val schedule: EnergySchedule,
        val sleepNeed: Duration,
        /** The night that ended this morning, when it's in. */
        val lastNight: Night?,
        /** Nights around today whose blocks (naps, extra segments, a sleep already started tonight) may be drawn. */
        val nights: List<Night>,
        /** Every habit: enabled ones first, each group in time order. */
        val habits: List<ScheduledHabit>,
        /** The first enabled habit that hasn't passed yet. */
        val nextHabit: ScheduledHabit?,
    ) : EnergyUiState {
        /** Habits drawn on the timeline: only the ones in the user's plan. */
        val timelineHabits: List<ScheduledHabit> get() = habits.filter { it.enabled }
    }
}

/**
 * Builds [EnergyUiState.Ready] from the day's insights, the freshest nights from the sleep repository and the
 * current minute. Last night comes from [nights] when it's there (it may have synced after the insights were
 * computed), otherwise from the insights.
 */
fun energyReadyState(
    insights: DailyInsights,
    nights: List<Night>,
    sleepNeed: Duration,
    now: Instant,
): EnergyUiState.Ready {
    val today = insights.today
    val lastNight = nights.firstOrNull { it.wakeDate == today && it.isPresent } ?: insights.lastNight
    val nearby = nights.filter { !it.wakeDate.isBefore(today.minusDays(1)) && !it.wakeDate.isAfter(today.plusDays(1)) }
    val habits = insights.habits.sortedWith(compareBy({ !it.enabled }, { it.time }))
    return EnergyUiState.Ready(
        today = today,
        now = now,
        zone = insights.zone,
        energyPotential = insights.energyPotential,
        status = insights.status,
        lastNightState = if (lastNight != null) LastNightState.AVAILABLE else insights.lastNightState,
        schedule = insights.schedule,
        sleepNeed = sleepNeed,
        lastNight = lastNight,
        nights = nearby,
        habits = habits,
        nextHabit = habits.firstOrNull { it.enabled && (it.windowEnd ?: it.time).isAfter(now) },
    )
}
