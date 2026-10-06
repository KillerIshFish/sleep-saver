package com.sleepsaver.core.ui.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Bed
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.WineBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.ui.R

val HabitType.icon: ImageVector
    get() = when (this) {
        HabitType.MORNING_LIGHT -> Icons.Rounded.LightMode
        HabitType.CAFFEINE_CUTOFF -> Icons.Rounded.Coffee
        HabitType.LARGE_MEAL_CUTOFF -> Icons.Rounded.Restaurant
        HabitType.ALCOHOL_CUTOFF -> Icons.Rounded.WineBar
        HabitType.EXERCISE_CUTOFF -> Icons.Rounded.FitnessCenter
        HabitType.DIM_LIGHTS -> Icons.Rounded.Lightbulb
        HabitType.EVENING_ROUTINE -> Icons.Rounded.AutoStories
        HabitType.BEDTIME -> Icons.Rounded.Bed
        HabitType.NAP_WINDOW -> Icons.Rounded.Snooze
    }

@get:StringRes
val HabitType.titleRes: Int
    get() = when (this) {
        HabitType.MORNING_LIGHT -> R.string.ss_habit_morning_light
        HabitType.CAFFEINE_CUTOFF -> R.string.ss_habit_caffeine_cutoff
        HabitType.LARGE_MEAL_CUTOFF -> R.string.ss_habit_large_meal_cutoff
        HabitType.ALCOHOL_CUTOFF -> R.string.ss_habit_alcohol_cutoff
        HabitType.EXERCISE_CUTOFF -> R.string.ss_habit_exercise_cutoff
        HabitType.DIM_LIGHTS -> R.string.ss_habit_dim_lights
        HabitType.EVENING_ROUTINE -> R.string.ss_habit_evening_routine
        HabitType.BEDTIME -> R.string.ss_habit_bedtime
        HabitType.NAP_WINDOW -> R.string.ss_habit_nap_window
    }

/** One plain-language line on why the habit helps. */
@get:StringRes
val HabitType.descriptionRes: Int
    get() = when (this) {
        HabitType.MORNING_LIGHT -> R.string.ss_habit_morning_light_body
        HabitType.CAFFEINE_CUTOFF -> R.string.ss_habit_caffeine_cutoff_body
        HabitType.LARGE_MEAL_CUTOFF -> R.string.ss_habit_large_meal_cutoff_body
        HabitType.ALCOHOL_CUTOFF -> R.string.ss_habit_alcohol_cutoff_body
        HabitType.EXERCISE_CUTOFF -> R.string.ss_habit_exercise_cutoff_body
        HabitType.DIM_LIGHTS -> R.string.ss_habit_dim_lights_body
        HabitType.EVENING_ROUTINE -> R.string.ss_habit_evening_routine_body
        HabitType.BEDTIME -> R.string.ss_habit_bedtime_body
        HabitType.NAP_WINDOW -> R.string.ss_habit_nap_window_body
    }

@Composable
@ReadOnlyComposable
fun HabitType.title(): String = stringResource(titleRes)

@Composable
@ReadOnlyComposable
fun HabitType.description(): String = stringResource(descriptionRes)
