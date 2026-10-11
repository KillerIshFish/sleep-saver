package com.sleepsaver.feature.habits.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.feature.habits.HabitDetailScreen
import com.sleepsaver.feature.habits.HabitsScreen
import kotlinx.serialization.Serializable

/** Habit list / picker (the "+" on the Habits sheet). */
@Serializable
data object HabitsRoute

/** One habit: toggle, reminder, offset. [type] = HabitType.name. */
@Serializable
data class HabitDetailRoute(val type: String)

/**
 * The habit list and a habit's detail screen. Taps on a screen that isn't resumed (still animating in or out) are
 * dropped, so a double tap can't open two screens or pop twice. [onOpenRoutineEditor] is the Night steps habit's
 * "Edit Night steps": the routine editor (feature:winddown, every evening's list), wired in app.
 */
fun NavGraphBuilder.habitsGraph(navController: NavController, onOpenRoutineEditor: () -> Unit = {}) = habitsGraph(
    navController = navController,
    list = { onBack, onOpenHabit -> HabitsScreen(onBack = onBack, onOpenHabit = onOpenHabit) },
    detail = { onBack, onEditNightSteps -> HabitDetailScreen(onBack = onBack, onEditNightSteps = onEditNightSteps) },
    onOpenRoutineEditor = onOpenRoutineEditor,
)

/** [habitsGraph] with the screens passed in, so tests can drive the real routes without Hilt. */
internal fun NavGraphBuilder.habitsGraph(
    navController: NavController,
    list: @Composable (onBack: () -> Unit, onOpenHabit: (HabitType) -> Unit) -> Unit,
    detail: @Composable (onBack: () -> Unit, onEditNightSteps: () -> Unit) -> Unit,
    onOpenRoutineEditor: () -> Unit = {},
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
            dropUnlessResumed(block = onOpenRoutineEditor),
        )
    }
}

private fun NavBackStackEntry.isResumed(): Boolean = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

fun NavController.navigateToHabits() = navigate(HabitsRoute) { launchSingleTop = true }
fun NavController.navigateToHabitDetail(type: HabitType) = navigate(HabitDetailRoute(type.name))
