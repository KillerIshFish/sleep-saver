package com.sleepsaver.core.domain.habits

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.HabitAnchor
import com.sleepsaver.core.domain.model.HabitDefinition
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.PhaseType
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
     * time = anchor instant + offset; windowEnd = time + definition.window (if any), except for WIND_DOWN_START habits
     * (dim lights, evening routine): time + the wind-down phase's length, which follows the evening plan.
     * Anchors: TYPICAL_WAKE = schedule.typicalWake, TARGET_BEDTIME = schedule.targetBedtime,
     * WIND_DOWN_START / MELATONIN_START / AFTERNOON_DIP_START = that phase's start.
     * EVENING_ROUTINE is still listed when the evening's Night steps are off, without a reminder.
     */
    fun schedule(schedule: EnergySchedule, settings: List<HabitSettings>): List<ScheduledHabit> {
        val byType = settings.associateBy { it.type }
        val windDownLength = schedule.phase(PhaseType.WIND_DOWN)?.let { Duration.between(it.start, it.end) }
        return catalog.all.mapNotNull { definition ->
            val setting = byType[definition.type]
                ?: HabitSettings(definition.type, definition.defaultEnabled, definition.defaultReminder, definition.defaultOffset)
            val time = anchorTime(schedule, definition.anchor)?.plus(setting.offset) ?: return@mapNotNull null
            val window = if (definition.anchor == HabitAnchor.WIND_DOWN_START) windDownLength else definition.window
            val stepsOff = definition.type == HabitType.EVENING_ROUTINE && !schedule.nightStepsOn
            ScheduledHabit(
                type = definition.type,
                time = time,
                windowEnd = window?.let { time + it },
                enabled = setting.enabled,
                reminderEnabled = setting.reminderEnabled && !stepsOff,
                category = definition.category,
            )
        }.sortedBy { it.time }
    }

    /** First enabled habit whose time (or window end, if it has a window) is after [now]. */
    fun nextUp(habits: List<ScheduledHabit>, now: Instant): ScheduledHabit? =
        habits.filter { it.enabled }.sortedBy { it.time }.firstOrNull { (it.windowEnd ?: it.time).isAfter(now) }

    private fun anchorTime(schedule: EnergySchedule, anchor: HabitAnchor): Instant? = when (anchor) {
        HabitAnchor.TYPICAL_WAKE -> schedule.typicalWake.toInstant()
        HabitAnchor.TARGET_BEDTIME -> schedule.targetBedtime
        HabitAnchor.WIND_DOWN_START -> schedule.phase(PhaseType.WIND_DOWN)?.start
        HabitAnchor.MELATONIN_START -> schedule.phase(PhaseType.MELATONIN_WINDOW)?.start
        HabitAnchor.AFTERNOON_DIP_START -> schedule.phase(PhaseType.AFTERNOON_DIP)?.start
    }
}
