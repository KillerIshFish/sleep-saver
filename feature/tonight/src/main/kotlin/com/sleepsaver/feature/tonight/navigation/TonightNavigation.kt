package com.sleepsaver.feature.tonight.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.sleepsaver.core.domain.schedule.DayPart
import com.sleepsaver.feature.tonight.check.AlarmCheckScreen
import com.sleepsaver.feature.tonight.day.DayPlanScreen
import com.sleepsaver.feature.tonight.night.NightScreen
import com.sleepsaver.feature.tonight.tonight.TonightScreen
import com.sleepsaver.feature.tonight.week.WeekPlanScreen
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate

/** The Tonight tab. */
@Serializable
data object TonightRoute

/** The week plan: seven days, one-offs, the plan check. */
@Serializable
data object WeekPlanRoute

/** One day's plan. [day] = DayOfWeek.value (1..7); [part] = DayPart.name to scroll to. */
@Serializable
data class DayPlanRoute(val day: Int, val part: String = DayPart.MORNING.name)

/** The no-clock night screen. */
@Serializable
data object NightRoute

/** Alarm check: what can stop an alarm from ringing on this phone. */
@Serializable
data object AlarmCheckRoute

/** Where the Tonight tab can go. Cross-feature targets come from app; the rest stay inside this graph. */
internal class TonightNavigation(
    val onOpenNightSteps: () -> Unit = {},
    /** null = every evening. */
    val onOpenRoutineEditor: (DayOfWeek?) -> Unit = {},
    val onOpenNightReset: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onOpenMorningReview: (LocalDate) -> Unit = {},
    val onOpenWeekPlan: () -> Unit = {},
    val onOpenDayPlan: (DayOfWeek, DayPart) -> Unit = { _, _ -> },
    val onOpenAlarmCheck: () -> Unit = {},
)

/** Where the week plan can go. */
internal class WeekPlanNavigation(
    val onBack: () -> Unit = {},
    val onOpenDayPlan: (DayOfWeek) -> Unit = {},
    val onOpenAlarmCheck: () -> Unit = {},
)

/** Where a day plan can go. */
internal class DayPlanNavigation(
    val onBack: () -> Unit = {},
    /** "Edit steps": the day's own routine. */
    val onOpenRoutineEditor: (DayOfWeek) -> Unit = {},
)

/** Where the Night screen can go: back to Tonight, away by itself when the night ends, or to Night reset. */
internal class NightNavigation(
    val onBackToTonight: () -> Unit = {},
    val onNightOver: () -> Unit = {},
    val onOpenNightReset: () -> Unit = {},
)

/** The screens of [tonightGraph], passed in so tests can drive the real routes with stand-ins (no Hilt). */
internal class TonightScreens(
    val tonight: @Composable (TonightNavigation) -> Unit,
    val weekPlan: @Composable (WeekPlanNavigation) -> Unit,
    val dayPlan: @Composable (DayPlanNavigation) -> Unit,
    val night: @Composable (NightNavigation) -> Unit,
    val alarmCheck: @Composable (onBack: () -> Unit) -> Unit,
) {
    companion object {
        val Real = TonightScreens(
            tonight = { TonightScreen(navigation = it) },
            weekPlan = { WeekPlanScreen(navigation = it) },
            dayPlan = { DayPlanScreen(navigation = it) },
            night = { NightScreen(navigation = it) },
            alarmCheck = { AlarmCheckScreen(onBack = it) },
        )
    }
}

/**
 * The Tonight tab, the week plan, a day's plan, the Night screen and Alarm check. Taps on a screen that isn't
 * resumed (still animating in or out) are dropped, so a double tap can't open two screens or pop twice. App wires
 * the cross-feature targets (Night steps, the routine editor, Night reset, Settings, the morning review).
 */
fun NavGraphBuilder.tonightGraph(
    navController: NavController,
    onOpenNightSteps: () -> Unit,
    /** null = every evening. */
    onOpenRoutineEditor: (DayOfWeek?) -> Unit,
    onOpenNightReset: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMorningReview: (LocalDate) -> Unit,
) = tonightGraph(
    navController = navController,
    onOpenNightSteps = onOpenNightSteps,
    onOpenRoutineEditor = onOpenRoutineEditor,
    onOpenNightReset = onOpenNightReset,
    onOpenSettings = onOpenSettings,
    onOpenMorningReview = onOpenMorningReview,
    screens = TonightScreens.Real,
)

/** [tonightGraph] with the screens passed in (test seam, like habitsGraph). */
internal fun NavGraphBuilder.tonightGraph(
    navController: NavController,
    onOpenNightSteps: () -> Unit,
    onOpenRoutineEditor: (DayOfWeek?) -> Unit,
    onOpenNightReset: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMorningReview: (LocalDate) -> Unit,
    screens: TonightScreens,
) {
    composable<TonightRoute> { entry ->
        screens.tonight(
            TonightNavigation(
                onOpenNightSteps = dropUnlessResumed(block = onOpenNightSteps),
                onOpenRoutineEditor = { day -> if (entry.isResumed()) onOpenRoutineEditor(day) },
                onOpenNightReset = dropUnlessResumed(block = onOpenNightReset),
                onOpenSettings = dropUnlessResumed(block = onOpenSettings),
                onOpenMorningReview = { date -> if (entry.isResumed()) onOpenMorningReview(date) },
                onOpenWeekPlan = dropUnlessResumed { navController.navigateToWeekPlan() },
                onOpenDayPlan = { day, part -> if (entry.isResumed()) navController.navigateToDayPlan(day, part) },
                onOpenAlarmCheck = dropUnlessResumed { navController.navigateToAlarmCheck() },
            ),
        )
    }
    composable<WeekPlanRoute> { entry ->
        screens.weekPlan(
            WeekPlanNavigation(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenDayPlan = { day -> if (entry.isResumed()) navController.navigateToDayPlan(day) },
                onOpenAlarmCheck = dropUnlessResumed { navController.navigateToAlarmCheck() },
            ),
        )
    }
    composable<DayPlanRoute> { entry ->
        screens.dayPlan(
            DayPlanNavigation(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenRoutineEditor = { day -> if (entry.isResumed()) onOpenRoutineEditor(day) },
            ),
        )
    }
    composable<NightRoute> {
        screens.night(
            NightNavigation(
                onBackToTonight = dropUnlessResumed { navController.backToTonight() },
                // Pops only this screen, so a race with a back tap can't pop the one below too.
                onNightOver = { navController.popBackStack<NightRoute>(inclusive = true) },
                onOpenNightReset = dropUnlessResumed(block = onOpenNightReset),
            ),
        )
    }
    composable<AlarmCheckRoute> {
        screens.alarmCheck(dropUnlessResumed { navController.popBackStack() })
    }
}

private fun NavBackStackEntry.isResumed(): Boolean = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

fun NavController.navigateToWeekPlan() = navigate(WeekPlanRoute) { launchSingleTop = true }

/**
 * Opens [day]'s plan at [part]. Not launchSingleTop: that would reuse a day plan on top for another day (its
 * ViewModel would keep the old day), so an identical one already on top is the only thing skipped.
 */
fun NavController.navigateToDayPlan(day: DayOfWeek, part: DayPart = DayPart.MORNING) {
    val route = DayPlanRoute(day.value, part.name)
    val top = currentBackStackEntry
    if (top != null && top.destination.hasRoute<DayPlanRoute>() && top.toRoute<DayPlanRoute>() == route) return
    navigate(route)
}

fun NavController.navigateToNight() = navigate(NightRoute) { launchSingleTop = true }

fun NavController.navigateToAlarmCheck() = navigate(AlarmCheckRoute) { launchSingleTop = true }

/**
 * Leaves the Night screen for the Tonight tab: back to it when it is below, else to the tab itself the way the bottom
 * bar goes there (the Night screen may have been opened on top of another tab, e.g. at a cold start).
 */
private fun NavController.backToTonight() {
    if (popBackStack<TonightRoute>(inclusive = false)) return
    navigate(TonightRoute) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
