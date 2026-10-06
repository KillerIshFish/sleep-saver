package com.sleepsaver.feature.habits.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.feature.habits.HabitsPlaceholder
import kotlinx.serialization.Serializable

/** Habit list / picker (the "+" on the Habits sheet). */
@Serializable
data object HabitsRoute

/** One habit: toggle, reminder, offset. [type] = HabitType.name. */
@Serializable
data class HabitDetailRoute(val type: String)

/** Evening routine activity grid. */
@Serializable
data object EveningRoutineRoute

fun NavGraphBuilder.habitsGraph(navController: NavController) {
    composable<HabitsRoute> { HabitsPlaceholder("Habits") }
    composable<HabitDetailRoute> { HabitsPlaceholder("Habit") }
    composable<EveningRoutineRoute> { HabitsPlaceholder("Evening routine") }
}

fun NavController.navigateToHabits() = navigate(HabitsRoute)
fun NavController.navigateToHabitDetail(type: HabitType) = navigate(HabitDetailRoute(type.name))
fun NavController.navigateToEveningRoutine() = navigate(EveningRoutineRoute)
