package com.sleepsaver.feature.energy

import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.data.repository.SyncStatus
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

    /** No sleep at all for the active source yet, and why (so the empty state can say what to fix). */
    data class NoNights(val reason: EmptyReason = EmptyReason.NO_DATA) : EnergyUiState

    data class Ready(
        /** The calendar date. */
        val today: LocalDate,
        /**
         * The day the schedule is for (its wake date): [today], or yesterday while a night owl's evening runs past
         * midnight. The timeline is drawn around this day's night.
         */
        val day: LocalDate,
        val now: Instant,
        val zone: ZoneId,
        val energyPotential: Int,
        val status: DebtStatus,
        val lastNightState: LastNightState,
        val schedule: EnergySchedule,
        val sleepNeed: Duration,
        /** The night that ended on the morning of [day], when it's in. */
        val lastNight: Night?,
        /** Nights around today whose blocks (naps, extra segments, a sleep already started tonight) may be drawn. */
        val nights: List<Night>,
        /** Every habit: enabled ones first, each group in time order. */
        val habits: List<ScheduledHabit>,
        /** The first enabled habit that hasn't passed yet. */
        val nextHabit: ScheduledHabit?,
        /** A sync is running right now (the "Sync now" action shows progress instead). */
        val syncing: Boolean = false,
    ) : EnergyUiState {
        /** Habits drawn on the timeline: only the ones in the user's plan. */
        val timelineHabits: List<ScheduledHabit> get() = habits.filter { it.enabled }
    }
}

/** Why there's nothing to draw yet. */
enum class EmptyReason {
    /** Connected, but no sleep has been recorded (or the selected source has none). */
    NO_DATA,

    /** The first history import is still running. */
    IMPORTING,
    HEALTH_CONNECT_MISSING,
    HEALTH_CONNECT_UPDATE,
    PERMISSION_DENIED,
}

/** The empty-state reason the last sync points at. */
fun SyncState.emptyReason(): EmptyReason = when {
    status is SyncStatus.Importing || status == SyncStatus.Syncing -> EmptyReason.IMPORTING
    lastError == SyncError.HEALTH_CONNECT_NOT_INSTALLED -> EmptyReason.HEALTH_CONNECT_MISSING
    lastError == SyncError.HEALTH_CONNECT_UPDATE_REQUIRED -> EmptyReason.HEALTH_CONNECT_UPDATE
    lastError == SyncError.PERMISSION_DENIED -> EmptyReason.PERMISSION_DENIED
    else -> EmptyReason.NO_DATA
}

val SyncState.isSyncing: Boolean get() = status != SyncStatus.Idle

/**
 * Builds [EnergyUiState.Ready] from the day's insights, the freshest nights from the sleep repository and the
 * current minute. Last night (the one ending on the schedule's day) comes from [nights] when it's there (it may
 * have synced after the insights were computed), otherwise from the insights.
 */
fun energyReadyState(
    insights: DailyInsights,
    nights: List<Night>,
    sleepNeed: Duration,
    now: Instant,
    syncing: Boolean = false,
): EnergyUiState.Ready {
    val today = insights.today
    val day = insights.schedule.date
    val lastNight = nights.firstOrNull { it.wakeDate == day && it.isPresent }
        ?: insights.lastNight?.takeIf { it.wakeDate == day }
    val lastNightState = when {
        lastNight != null -> LastNightState.AVAILABLE
        day == today -> insights.lastNightState
        // A night owl after midnight, still on yesterday's schedule, whose night before is missing.
        else -> LastNightState.MISSING
    }
    val nearby = nights.filter { !it.wakeDate.isBefore(day.minusDays(1)) && !it.wakeDate.isAfter(today.plusDays(1)) }
    val habits = insights.habits.sortedWith(compareBy({ !it.enabled }, { it.time }))
    return EnergyUiState.Ready(
        today = today,
        day = day,
        now = now,
        zone = insights.zone,
        energyPotential = insights.energyPotential,
        status = insights.status,
        lastNightState = lastNightState,
        schedule = insights.schedule,
        sleepNeed = sleepNeed,
        lastNight = lastNight,
        nights = nearby,
        habits = habits,
        nextHabit = habits.firstOrNull { it.enabled && (it.windowEnd ?: it.time).isAfter(now) },
        syncing = syncing,
    )
}
