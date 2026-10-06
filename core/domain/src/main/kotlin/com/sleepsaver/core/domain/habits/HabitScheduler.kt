package com.sleepsaver.core.domain.habits

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.HabitAnchor
import com.sleepsaver.core.domain.model.HabitDefinition
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.ReminderCategory
import com.sleepsaver.core.domain.model.ScheduledHabit
import java.time.Duration
import java.time.Instant

/** Default habit definitions (docs/PLAN.md §3.8). Offsets are signed: negative = before the anchor. */
class HabitCatalog(config: SleepConfig = SleepConfig.DEFAULT) {
    val all: List<HabitDefinition> = listOf(
        HabitDefinition(HabitType.MORNING_LIGHT, HabitAnchor.TYPICAL_WAKE, config.morningLightOffset, Duration.ofMinutes(30), true, true, ReminderCategory.MORNING_LIGHT),
        HabitDefinition(HabitType.CAFFEINE_CUTOFF, HabitAnchor.TARGET_BEDTIME, config.caffeineCutoffBeforeBed.negated(), null, true, true, ReminderCategory.CUTOFFS),
        HabitDefinition(HabitType.LARGE_MEAL_CUTOFF, HabitAnchor.TARGET_BEDTIME, config.largeMealCutoffBeforeBed.negated(), null, false, false, ReminderCategory.CUTOFFS),
        HabitDefinition(HabitType.ALCOHOL_CUTOFF, HabitAnchor.TARGET_BEDTIME, config.alcoholCutoffBeforeBed.negated(), null, false, false, ReminderCategory.CUTOFFS),
        HabitDefinition(HabitType.EXERCISE_CUTOFF, HabitAnchor.TARGET_BEDTIME, config.exerciseCutoffBeforeBed.negated(), null, false, false, ReminderCategory.CUTOFFS),
        HabitDefinition(HabitType.DIM_LIGHTS, HabitAnchor.WIND_DOWN_START, Duration.ZERO, config.windDownLength, true, true, ReminderCategory.WIND_DOWN),
        HabitDefinition(HabitType.EVENING_ROUTINE, HabitAnchor.WIND_DOWN_START, Duration.ZERO, config.windDownLength, true, true, ReminderCategory.WIND_DOWN),
        HabitDefinition(HabitType.BEDTIME, HabitAnchor.MELATONIN_START, Duration.ZERO, config.melatoninHalfWidth.multipliedBy(2), true, true, ReminderCategory.BEDTIME),
        HabitDefinition(HabitType.NAP_WINDOW, HabitAnchor.AFTERNOON_DIP_START, Duration.ZERO, config.napMaxLength, false, false, ReminderCategory.NAPS),
    )

    fun definition(type: HabitType): HabitDefinition = all.first { it.type == type }

    fun defaultSettings(): List<HabitSettings> =
        all.map { HabitSettings(it.type, it.defaultEnabled, it.defaultReminder, it.defaultOffset) }
}

/** Turns an [EnergySchedule] + the user's habit settings into concrete times. */
class HabitScheduler(private val catalog: HabitCatalog = HabitCatalog()) {

    /**
     * One [ScheduledHabit] per HabitType, ordered by time. Missing settings fall back to the catalog defaults.
     * time = anchor instant + offset; windowEnd = time + definition.window (if any).
     * Anchors: TYPICAL_WAKE = schedule.typicalWake, TARGET_BEDTIME = schedule.targetBedtime,
     * WIND_DOWN_START / MELATONIN_START / AFTERNOON_DIP_START = that phase's start.
     */
    fun schedule(schedule: EnergySchedule, settings: List<HabitSettings>): List<ScheduledHabit> {
        TODO("M2: habit times")
    }

    /** First enabled habit whose time (or window end, if it has a window) is after [now]. */
    fun nextUp(habits: List<ScheduledHabit>, now: Instant): ScheduledHabit? {
        TODO("M2: next habit")
    }
}
