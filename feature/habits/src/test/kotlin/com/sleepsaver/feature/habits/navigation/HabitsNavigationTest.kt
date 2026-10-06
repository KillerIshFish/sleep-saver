package com.sleepsaver.feature.habits.navigation

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
import com.sleepsaver.core.domain.model.HabitType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real habit routes in a NavHost, with stand-in screens (the real ones need Hilt). */
@RunWith(AndroidJUnit4::class)
class HabitsNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var navController: NavHostController

    /** Reads the habit type from the route, like HabitDetailViewModel. */
    class TypeViewModel(handle: SavedStateHandle) : ViewModel() {
        val type = handle.toRoute<HabitDetailRoute>().type
    }

    private fun setUpNavHost() {
        compose.setContent {
            navController = rememberNavController()
            NavHost(navController, startDestination = HomeRoute) {
                composable<HomeRoute> {
                    Button(onClick = { navController.navigateToHabits() }) { Text("open habits") }
                }
                habitsGraph(
                    navController = navController,
                    list = { onBack, onOpenHabit ->
                        Column {
                            Button(onClick = { onOpenHabit(HabitType.CAFFEINE_CUTOFF) }) { Text("open caffeine") }
                            Button(onClick = { onOpenHabit(HabitType.EVENING_ROUTINE) }) { Text("open routine") }
                            Button(onClick = onBack) { Text("back from list") }
                        }
                    },
                    detail = { onBack, onChooseActivities ->
                        val viewModel = viewModel { TypeViewModel(createSavedStateHandle()) }
                        Column {
                            Button(onClick = onBack) { Text("back from ${viewModel.type}") }
                            Button(onClick = onChooseActivities) { Text("choose activities") }
                        }
                    },
                    routine = { onClose, onDone ->
                        Column {
                            Button(onClick = onClose) { Text("close routine") }
                            Button(onClick = onDone) { Text("routine added") }
                        }
                    },
                )
            }
        }
        compose.onNodeWithText("open habits").performClick()
    }

    private fun currentIs(check: (NavHostController) -> Boolean) = compose.runOnIdle { assertThat(check(navController)).isTrue() }

    @Test
    fun `a habit opens with its type and back returns to the list`() {
        setUpNavHost()

        compose.onNodeWithText("open caffeine").performClick()
        compose.onNodeWithText("back from CAFFEINE_CUTOFF").performClick()

        currentIs { it.currentDestination?.hasRoute<HabitsRoute>() == true }
    }

    @Test
    fun `a quick second tap opens only one habit`() {
        setUpNavHost()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("open caffeine").performClick()
        compose.onNodeWithText("open routine").performClick()
        compose.mainClock.autoAdvance = true

        compose.onNodeWithText("back from CAFFEINE_CUTOFF").performClick()
        currentIs { it.currentDestination?.hasRoute<HabitsRoute>() == true }
    }

    @Test
    fun `a double tap on back pops only one screen`() {
        setUpNavHost()
        compose.onNodeWithText("open caffeine").performClick()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("back from CAFFEINE_CUTOFF").performClick()
        compose.onNodeWithText("back from CAFFEINE_CUTOFF").performClick()
        compose.mainClock.autoAdvance = true

        currentIs { it.currentDestination?.hasRoute<HabitsRoute>() == true }
    }

    @Test
    fun `the routine picker returns to the habit when it's done`() {
        setUpNavHost()
        compose.onNodeWithText("open routine").performClick()
        compose.onNodeWithText("choose activities").performClick()

        compose.onNodeWithText("routine added").performClick()

        currentIs { it.currentDestination?.hasRoute<HabitDetailRoute>() == true }
        compose.runOnIdle { assertThat(navController.currentBackStackEntry!!.toRoute<HabitDetailRoute>().type).isEqualTo("EVENING_ROUTINE") }
    }

    @Test
    fun `finishing the routine twice pops it only once`() {
        setUpNavHost()
        compose.onNodeWithText("open routine").performClick()
        compose.onNodeWithText("choose activities").performClick()

        compose.runOnIdle {
            navController.popBackStack<EveningRoutineRoute>(inclusive = true)
            navController.popBackStack<EveningRoutineRoute>(inclusive = true)
        }

        currentIs { it.currentDestination?.hasRoute<HabitDetailRoute>() == true }
    }

    @Test
    fun `opening habits twice doesn't stack the list`() {
        setUpNavHost()

        compose.runOnIdle { navController.navigateToHabits() }
        compose.onNodeWithText("back from list").performClick()

        currentIs { it.currentDestination?.hasRoute<HomeRoute>() == true }
    }
}

@kotlinx.serialization.Serializable
private data object HomeRoute
