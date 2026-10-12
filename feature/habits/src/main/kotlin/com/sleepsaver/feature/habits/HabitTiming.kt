package com.sleepsaver.feature.habits

import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.domain.habits.HabitCatalog
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.HabitAnchor
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.PhaseType
import java.time.Duration
import java.time.Instant

/** How a habit's timing is adjusted on its detail screen. */
internal enum class TimingKind {
    /** Cutoffs: a positive number of minutes before the target bedtime. */
    BEFORE_BEDTIME,

    /** Morning light: minutes after the typical wake-up. */
    AFTER_WAKE,

    /** Wind-down habits, bedtime, nap window: a signed shift from their anchor (negative = earlier). */
    SHIFT,
}

/**
 * The stepper for one habit. The stepper's value is what the user reads ("10h" before bedtime), the stored
 * offset is signed relative to the habit's anchor (negative = before it). [min]..[max] are stepper values.
 */
internal data class TimingSpec(val kind: TimingKind, val min: Int, val max: Int, val step: Int) {

    /** Stepper value for a stored offset, kept inside [min]..[max]. */
    fun valueOf(offset: Duration): Int {
        val minutes = offset.toMinutes().toInt()
        return clamp(if (kind == TimingKind.BEFORE_BEDTIME) -minutes else minutes)
    }

    /** The stored offset for a stepper value (clamped first). */
    fun offsetOf(value: Int): Duration {
        val minutes = clamp(value)
        return Duration.ofMinutes((if (kind == TimingKind.BEFORE_BEDTIME) -minutes else minutes).toLong())
    }

    fun clamp(value: Int): Int = value.coerceIn(min, max)
}

/** Allowed ranges: cutoffs in 30-minute steps; morning light and phase-anchored habits in 15-minute steps. */
internal fun timingSpec(type: HabitType): TimingSpec = when (type) {
    HabitType.CAFFEINE_CUTOFF -> TimingSpec(TimingKind.BEFORE_BEDTIME, min = 4 * 60, max = 14 * 60, step = 30)
    HabitType.LARGE_MEAL_CUTOFF,
    HabitType.ALCOHOL_CUTOFF,
    -> TimingSpec(TimingKind.BEFORE_BEDTIME, min = 60, max = 6 * 60, step = 30)
    HabitType.EXERCISE_CUTOFF -> TimingSpec(TimingKind.BEFORE_BEDTIME, min = 60, max = 5 * 60, step = 30)
    HabitType.MORNING_LIGHT -> TimingSpec(TimingKind.AFTER_WAKE, min = 0, max = 90, step = 15)
    HabitType.DIM_LIGHTS,
    HabitType.EVENING_ROUTINE,
    HabitType.BEDTIME,
    HabitType.NAP_WINDOW,
    -> TimingSpec(TimingKind.SHIFT, min = -60, max = 60, step = 15)
}

internal val habitCatalog = HabitCatalog()

/** The instant a habit is measured from, in [schedule] (null when that phase is missing). */
internal fun anchorTime(schedule: EnergySchedule, anchor: HabitAnchor): Instant? = when (anchor) {
    HabitAnchor.TYPICAL_WAKE -> schedule.typicalWake.toInstant()
    HabitAnchor.TARGET_BEDTIME -> schedule.targetBedtime
    HabitAnchor.WIND_DOWN_START -> schedule.phase(PhaseType.WIND_DOWN)?.start
    HabitAnchor.AFTERNOON_DIP_START -> schedule.phase(PhaseType.AFTERNOON_DIP)?.start
}

/** Whether today's times can be shown. */
internal enum class TimesStatus {
    READY,

    /** Insights are still being computed. */
    LOADING,

    /** No nights yet, so there is no energy schedule to time habits against. */
    NO_NIGHTS,
}

internal fun InsightsState.timesStatus(): TimesStatus = when (this) {
    InsightsState.Loading -> TimesStatus.LOADING
    InsightsState.NoNights -> TimesStatus.NO_NIGHTS
    is InsightsState.Ready -> TimesStatus.READY
}
