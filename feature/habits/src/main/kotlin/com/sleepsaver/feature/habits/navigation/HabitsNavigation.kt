package com.sleepsaver.feature.habits.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.feature.habits.EveningRoutineScreen
import com.sleepsaver.feature.habits.HabitDetailScreen
import com.sleepsaver.feature.habits.HabitsScreen
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

/**
 * The habit list, a habit's detail screen and the evening-routine picker. Taps on a screen that isn't
 * resumed (still animating in or out) are dropped, so a double tap can't open two screens or pop twice.
 */
fun NavGraphBuilder.habitsGraph(navController: NavController) = habitsGraph(
    navController = navController,
    list = { onBack, onOpenHabit -> HabitsScreen(onBack = onBack, onOpenHabit = onOpenHabit) },
    detail = { onBack, onChooseActivities -> HabitDetailScreen(onBack = onBack, onChooseActivities = onChooseActivities) },
    routine = { onClose, onDone -> EveningRoutineScreen(onClose = onClose, onDone = onDone) },
)

/** [habitsGraph] with the screens passed in, so tests can drive the real routes without Hilt. */
internal fun NavGraphBuilder.habitsGraph(
    navController: NavController,
    list: @Composable (onBack: () -> Unit, onOpenHabit: (HabitType) -> Unit) -> Unit,
    detail: @Composable (onBack: () -> Unit, onChooseActivities: () -> Unit) -> Unit,
    routine: @Composable (onClose: () -> Unit, onDone: () -> Unit) -> Unit,
) {
    composable<HabitsRoute> { entry ->
        list(
            dropUnlessResumed { navController.popBackStack() },
            { type -> if (entry.isResumed()) navController.navigateToHabitDetail(type) },
        )
    }
    composable<HabitDetailRoute> {
        detail(
            dropUnlessResumed { navController.popBackStack() },
            dropUnlessResumed { navController.navigateToEveningRoutine() },
        )
    }
    composable<EveningRoutineRoute> {
        routine(
            dropUnlessResumed { navController.popBackStack() },
            // Pops only this screen, so it can't also pop the one below if it races a close tap.
            { navController.popBackStack<EveningRoutineRoute>(inclusive = true) },
        )
    }
}

private fun NavBackStackEntry.isResumed(): Boolean = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

fun NavController.navigateToHabits() = navigate(HabitsRoute) { launchSingleTop = true }
fun NavController.navigateToHabitDetail(type: HabitType) = navigate(HabitDetailRoute(type.name))
fun NavController.navigateToEveningRoutine() = navigate(EveningRoutineRoute) { launchSingleTop = true }
