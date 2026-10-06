package com.sleepsaver.core.notifications

import androidx.annotation.StringRes
import com.sleepsaver.core.domain.model.HabitType

internal data class HabitReminderCopy(@param:StringRes val title: Int, @param:StringRes val body: Int) {
    companion object {
        fun of(type: HabitType): HabitReminderCopy = when (type) {
            HabitType.MORNING_LIGHT -> HabitReminderCopy(R.string.reminder_morning_light_title, R.string.reminder_morning_light_body)
            HabitType.CAFFEINE_CUTOFF -> HabitReminderCopy(R.string.reminder_caffeine_title, R.string.reminder_caffeine_body)
            HabitType.LARGE_MEAL_CUTOFF -> HabitReminderCopy(R.string.reminder_large_meal_title, R.string.reminder_large_meal_body)
            HabitType.ALCOHOL_CUTOFF -> HabitReminderCopy(R.string.reminder_alcohol_title, R.string.reminder_alcohol_body)
            HabitType.EXERCISE_CUTOFF -> HabitReminderCopy(R.string.reminder_exercise_title, R.string.reminder_exercise_body)
            HabitType.DIM_LIGHTS -> HabitReminderCopy(R.string.reminder_dim_lights_title, R.string.reminder_dim_lights_body)
            HabitType.EVENING_ROUTINE -> HabitReminderCopy(R.string.reminder_evening_routine_title, R.string.reminder_evening_routine_body)
            HabitType.BEDTIME -> HabitReminderCopy(R.string.reminder_bedtime_title, R.string.reminder_bedtime_body)
            HabitType.NAP_WINDOW -> HabitReminderCopy(R.string.reminder_nap_title, R.string.reminder_nap_body)
        }
    }
}
