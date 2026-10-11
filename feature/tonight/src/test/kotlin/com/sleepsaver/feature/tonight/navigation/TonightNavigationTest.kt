package com.sleepsaver.feature.tonight.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.schedule.DayPart
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate

/** The real Tonight routes in a NavHost, with stand-in screens (the real ones need Hilt). */
@RunWith(AndroidJUnit4::class)
class TonightNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var navController: NavHostController

    /** What the cross-feature lambdas were called with. */
    private val calls = mutableListOf<String>()

    /** Reads the day plan route like DayPlanViewModel. */
    class DayViewModel(handle: SavedStateHandle) : ViewModel() {
        val route = handle.toRoute<DayPlanRoute>()
    }

    private val screens = TonightScreens(
        tonight = { nav ->
            Column {
                Text("tonight")
                Button(onClick = nav.onOpenWeekPlan) { Text("open week plan") }
                Button(onClick = { nav.onOpenDayPlan(DayOfWeek.TUESDAY, DayPart.EVENING) }) { Text("change tuesday evening") }
                Button(onClick = nav.onOpenAlarmCheck) { Text("open alarm check") }
                Button(onClick = nav.onOpenNightSteps) { Text("open night steps") }
                Button(onClick = { nav.onOpenRoutineEditor(null) }) { Text("pick steps") }
                Button(onClick = nav.onOpenSettings) { Text("open settings") }
                Button(onClick = { nav.onOpenMorningReview(LocalDate.of(2026, 10, 6)) }) { Text("open review") }
            }
        },
        weekPlan = { nav ->
            Column {
                Text("week plan")
                Button(onClick = { nav.onOpenDayPlan(DayOfWeek.SATURDAY) }) { Text("open saturday") }
                Button(onClick = { nav.onOpenDayPlan(DayOfWeek.SUNDAY) }) { Text("open sunday") }
                Button(onClick = nav.onOpenAlarmCheck) { Text("week alarm check") }
                Button(onClick = nav.onBack) { Text("back from week") }
            }
        },
        dayPlan = { nav ->
            val day = viewModel { DayViewModel(createSavedStateHandle()) }.route
            Column {
                Text("day ${day.day} ${day.part}")
                Button(onClick = { nav.onOpenRoutineEditor(DayOfWeek.of(day.day)) }) { Text("edit steps") }
                Button(onClick = nav.onBack) { Text("back from day") }
            }
        },
        night = { nav ->
            Column {
                Text("night")
                Button(onClick = nav.onBackToTonight) { Text("back from night") }
                Button(onClick = nav.onNightOver) { Text("night over") }
                Button(onClick = nav.onOpenNightReset) { Text("cant sleep") }
            }
        },
        alarmCheck = { onBack ->
            Column {
                Text("alarm check")
                Button(onClick = onBack) { Text("back from check") }
            }
        },
    )

    private fun setUpNavHost(start: Any = TonightRoute) {
        compose.setContent {
            navController = rememberNavController()
            NavHost(navController, startDestination = start) {
                composable<OtherTabRoute> { Text("other tab") }
                tonightGraph(
                    navController = navController,
                    onOpenNightSteps = { calls += "nightSteps" },
                    onOpenRoutineEditor = { calls += "routine:$it" },
                    onOpenNightReset = { calls += "nightReset" },
                    onOpenSettings = { calls += "settings" },
                    onOpenMorningReview = { calls += "review:$it" },
                    screens = screens,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun currentIs(check: (NavHostController) -> Boolean) = compose.runOnIdle { assertThat(check(navController)).isTrue() }

    @Test
    fun `week plan to a day and back`() {
        setUpNavHost()

        compose.onNodeWithText("open week plan").performClick()
        compose.onNodeWithText("open saturday").performClick()
        compose.onNodeWithText("day 6 MORNING").assertExists()

        compose.onNodeWithText("back from day").performClick()
        currentIs { it.currentDestination?.hasRoute<WeekPlanRoute>() == true }
        compose.onNodeWithText("back from week").performClick()
        currentIs { it.currentDestination?.hasRoute<TonightRoute>() == true }
    }

    @Test
    fun `the tonight card opens a day at its evening`() {
        setUpNavHost()

        compose.onNodeWithText("change tuesday evening").performClick()

        compose.onNodeWithText("day 2 EVENING").assertExists()
    }

    @Test
    fun `the same day plan twice doesn't stack, another day does`() {
        setUpNavHost()
        compose.onNodeWithText("open week plan").performClick()
        compose.onNodeWithText("open saturday").performClick()

        compose.runOnIdle { navController.navigateToDayPlan(DayOfWeek.SATURDAY) }
        compose.onNodeWithText("back from day").performClick()
        currentIs { it.currentDestination?.hasRoute<WeekPlanRoute>() == true }

        compose.onNodeWithText("open saturday").performClick()
        compose.runOnIdle { navController.navigateToDayPlan(DayOfWeek.SUNDAY) }
        compose.onNodeWithText("day 7 MORNING").assertExists()
        compose.onNodeWithText("back from day").performClick()
        compose.onNodeWithText("day 6 MORNING").assertExists()
    }

    @Test
    fun `a quick second tap opens only one week plan`() {
        setUpNavHost()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("open week plan").performClick()
        compose.onNodeWithText("open week plan").performClick()
        compose.mainClock.autoAdvance = true

        compose.onNodeWithText("back from week").performClick()
        currentIs { it.currentDestination?.hasRoute<TonightRoute>() == true }
    }

    @Test
    fun `a double tap on back pops only one screen`() {
        setUpNavHost()
        compose.onNodeWithText("open week plan").performClick()
        compose.onNodeWithText("open saturday").performClick()
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("back from day").performClick()
        compose.onNodeWithText("back from day").performClick()
        compose.mainClock.autoAdvance = true

        currentIs { it.currentDestination?.hasRoute<WeekPlanRoute>() == true }
    }

    @Test
    fun `alarm check opens once and goes back`() {
        setUpNavHost()
        compose.onNodeWithText("open week plan").performClick()
        compose.onNodeWithText("week alarm check").performClick()

        compose.runOnIdle { navController.navigateToAlarmCheck() }
        compose.onNodeWithText("back from check").performClick()

        currentIs { it.currentDestination?.hasRoute<WeekPlanRoute>() == true }
    }

    @Test
    fun `the night screen goes back to tonight, or leaves by itself`() {
        setUpNavHost()
        compose.runOnIdle { navController.navigateToNight() }
        compose.runOnIdle { navController.navigateToNight() }
        compose.onNodeWithText("back from night").performClick()
        currentIs { it.currentDestination?.hasRoute<TonightRoute>() == true }

        compose.runOnIdle { navController.navigateToNight() }
        compose.onNodeWithText("night over").performClick()
        currentIs { it.currentDestination?.hasRoute<TonightRoute>() == true }
        // Over twice (a race with back) still pops only the night screen.
        compose.runOnIdle { navController.popBackStack<NightRoute>(inclusive = true) }
        currentIs { it.currentDestination?.hasRoute<TonightRoute>() == true }
    }

    @Test
    fun `a night screen opened over another tab goes back to tonight`() {
        setUpNavHost(start = OtherTabRoute)
        compose.runOnIdle { navController.navigateToNight() }

        compose.onNodeWithText("back from night").performClick()

        currentIs { it.currentDestination?.hasRoute<TonightRoute>() == true }
    }

    @Test
    fun `other features are reached through the app's lambdas`() {
        setUpNavHost()

        compose.onNodeWithText("open night steps").performClick()
        compose.onNodeWithText("pick steps").performClick()
        compose.onNodeWithText("open settings").performClick()
        compose.onNodeWithText("open review").performClick()
        compose.onNodeWithText("change tuesday evening").performClick()
        compose.onNodeWithText("edit steps").performClick()
        compose.runOnIdle { navController.navigateToNight() }
        compose.onNodeWithText("cant sleep").performClick()

        compose.runOnIdle {
            assertThat(calls).containsExactly(
                "nightSteps",
                "routine:null",
                "settings",
                "review:2026-10-06",
                "routine:TUESDAY",
                "nightReset",
            ).inOrder()
        }
    }
}

@Serializable
internal data object OtherTabRoute
