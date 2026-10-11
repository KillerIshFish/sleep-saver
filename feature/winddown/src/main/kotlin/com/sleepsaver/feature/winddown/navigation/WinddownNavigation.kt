package com.sleepsaver.feature.winddown.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.sleepsaver.core.domain.nightsteps.GuidedTool
import com.sleepsaver.core.domain.schedule.DayPart
import com.sleepsaver.feature.winddown.editor.RoutineEditorScreen
import com.sleepsaver.feature.winddown.library.StepLibraryScreen
import com.sleepsaver.feature.winddown.reset.NightResetScreen
import com.sleepsaver.feature.winddown.steps.NightStepsScreen
import com.sleepsaver.feature.winddown.tools.GuidedToolScreen
import com.sleepsaver.feature.winddown.tools.screenToolOf
import kotlinx.serialization.Serializable
import java.time.DayOfWeek

/** Tonight's Night steps. */
@Serializable
data object NightStepsRoute

/** The routine editor. [day] = DayOfWeek.value, 0 = every evening. */
@Serializable
data class RoutineEditorRoute(val day: Int = 0)

/**
 * The step library. [day] = the weekday whose routine editor opened it (DayOfWeek.value), 0 = every evening: on a day
 * with its own list, "Add" puts the step on that list only.
 */
@Serializable
data class StepLibraryRoute(val day: Int = 0)

/** A guided tool. [tool] = GuidedTool.name (SLOW_TIDE, BODY_SCAN, MUSCLE_RELEASE, TOMORROW_LIST). */
@Serializable
data class GuidedToolRoute(val tool: String)

/** Night reset, the can't-sleep flow (no clock). */
@Serializable
data object NightResetRoute

/** Where Night steps can go from its main screen. */
internal class NightStepsNavigation(
    val onBack: () -> Unit,
    /** null = the every-evening list. */
    val onOpenEditor: (DayOfWeek?) -> Unit,
    val onOpenLibrary: () -> Unit,
    val onOpenTool: (GuidedTool) -> Unit,
    val onOpenDayPlan: (DayOfWeek, DayPart) -> Unit,
    val onOpenTonight: () -> Unit,
)

/** The graph's screens, so tests can drive the real routes with stand-ins (the real screens need Hilt). */
internal class NightStepsScreens(
    val nightSteps: @Composable (NightStepsNavigation) -> Unit,
    val editor: @Composable (onBack: () -> Unit, onOpenLibrary: () -> Unit) -> Unit,
    val library: @Composable (onBack: () -> Unit, onOpenTool: (GuidedTool) -> Unit) -> Unit,
    /** [tool] null = the route named a tool without a screen. */
    val tool: @Composable (tool: GuidedTool?, onClose: () -> Unit) -> Unit,
    val reset: @Composable (onClose: () -> Unit) -> Unit,
) {
    companion object {
        val Default = NightStepsScreens(
            nightSteps = { nav ->
                NightStepsScreen(
                    onBack = nav.onBack,
                    onOpenEditor = nav.onOpenEditor,
                    onOpenLibrary = nav.onOpenLibrary,
                    onOpenTool = nav.onOpenTool,
                    onOpenDayPlan = nav.onOpenDayPlan,
                    onOpenTonight = nav.onOpenTonight,
                )
            },
            editor = { onBack, onOpenLibrary -> RoutineEditorScreen(onBack = onBack, onOpenLibrary = onOpenLibrary) },
            library = { onBack, onOpenTool -> StepLibraryScreen(onBack = onBack, onOpenTool = onOpenTool) },
            tool = { tool, onClose -> GuidedToolScreen(tool = tool, onClose = onClose) },
            reset = { onClose -> NightResetScreen(onClose = onClose) },
        )
    }
}

/**
 * Night steps, the routine editor (the app's only one), the step library, the guided tools and Night reset. Taps on a
 * screen that isn't resumed (still animating in or out) are dropped, so a double tap can't open two screens or pop
 * twice. [onOpenDayPlan] opens the week plan's day (feature:tonight); [onOpenTonight] goes to the Tonight tab after
 * Drop anchor.
 */
fun NavGraphBuilder.nightStepsGraph(
    navController: NavController,
    onOpenDayPlan: (DayOfWeek, DayPart) -> Unit,
    onOpenTonight: () -> Unit,
) = nightStepsGraph(navController, onOpenDayPlan, onOpenTonight, NightStepsScreens.Default)

/** [nightStepsGraph] with the screens passed in. */
internal fun NavGraphBuilder.nightStepsGraph(
    navController: NavController,
    onOpenDayPlan: (DayOfWeek, DayPart) -> Unit,
    onOpenTonight: () -> Unit,
    screens: NightStepsScreens,
) {
    composable<NightStepsRoute> { entry ->
        screens.nightSteps(
            NightStepsNavigation(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenEditor = { day -> if (entry.isResumed()) navController.navigateToRoutineEditor(day) },
                onOpenLibrary = dropUnlessResumed { navController.navigateToStepLibrary() },
                onOpenTool = { tool -> if (entry.isResumed()) navController.navigateToGuidedTool(tool) },
                onOpenDayPlan = { day, part -> if (entry.isResumed()) onOpenDayPlan(day, part) },
                onOpenTonight = dropUnlessResumed { onOpenTonight() },
            ),
        )
    }
    composable<RoutineEditorRoute> { entry ->
        val day = entry.toRoute<RoutineEditorRoute>().day.takeIf { it in 1..7 }?.let(DayOfWeek::of)
        screens.editor(
            dropUnlessResumed { navController.popBackStack() },
            // The library adds to the list being edited: this day's own list, or every evening.
            dropUnlessResumed { navController.navigateToStepLibrary(day) },
        )
    }
    composable<StepLibraryRoute> { entry ->
        screens.library(
            dropUnlessResumed { navController.popBackStack() },
            { tool -> if (entry.isResumed()) navController.navigateToGuidedTool(tool) },
        )
    }
    composable<GuidedToolRoute> { entry ->
        screens.tool(
            screenToolOf(entry.toRoute<GuidedToolRoute>().tool),
            // Pops only this screen, so a quick second tap (Done, then close) can't pop the one below.
            { navController.popBackStack<GuidedToolRoute>(inclusive = true) },
        )
    }
    composable<NightResetRoute> {
        screens.reset { navController.popBackStack<NightResetRoute>(inclusive = true) }
    }
}

private fun NavBackStackEntry.isResumed(): Boolean = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

fun NavController.navigateToNightSteps() = navigate(NightStepsRoute) { launchSingleTop = true }

/** null = every evening. */
fun NavController.navigateToRoutineEditor(day: DayOfWeek?) = navigate(RoutineEditorRoute(day?.value ?: 0)) { launchSingleTop = true }

/** null = every evening; else the weekday whose routine is being edited. */
fun NavController.navigateToStepLibrary(day: DayOfWeek? = null) = navigate(StepLibraryRoute(day?.value ?: 0)) { launchSingleTop = true }

fun NavController.navigateToGuidedTool(tool: GuidedTool) = navigate(GuidedToolRoute(tool.name)) { launchSingleTop = true }

fun NavController.navigateToNightReset() = navigate(NightResetRoute) { launchSingleTop = true }
