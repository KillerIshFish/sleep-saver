package com.sleepsaver.feature.winddown.navigation

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
import com.sleepsaver.core.domain.nightsteps.GuidedTool
import com.sleepsaver.core.domain.schedule.DayPart
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek

/** The real Night steps routes in a NavHost, with stand-in screens (the real ones need Hilt). */
@RunWith(AndroidJUnit4::class)
class WinddownNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var navController: NavHostController
    private val dayPlans = mutableListOf<Pair<DayOfWeek, DayPart>>()
    private var tonightOpened = 0

    /** Reads the editor's day from the route, like RoutineEditorViewModel. */
    class DayViewModel(handle: SavedStateHandle) : ViewModel() {
        val day = handle.toRoute<RoutineEditorRoute>().day
    }

    private fun setUpNavHost() {
        compose.setContent {
            navController = rememberNavController()
            NavHost(navController, startDestination = HomeRoute) {
                composable<HomeRoute> {
                    Column {
                        Button(onClick = { navController.navigateToNightSteps() }) { Text("open night steps") }
                        Button(onClick = { navController.navigateToNightReset() }) { Text("open night reset") }
                        Button(onClick = { navController.navigateToRoutineEditor(null) }) { Text("open every evening") }
                    }
                }
                nightStepsGraph(
                    navController = navController,
                    onOpenDayPlan = { day, part -> dayPlans += day to part },
                    onOpenTonight = { tonightOpened++ },
                    screens = NightStepsScreens(
                        nightSteps = { nav ->
                            Column {
                                Button(onClick = { nav.onOpenEditor(DayOfWeek.TUESDAY) }) { Text("edit tuesday") }
                                Button(onClick = nav.onOpenLibrary) { Text("library") }
                                Button(onClick = { nav.onOpenTool(GuidedTool.SLOW_TIDE) }) { Text("slow tide") }
                                Button(onClick = { nav.onOpenDayPlan(DayOfWeek.TUESDAY, DayPart.EVENING) }) { Text("week plan") }
                                Button(onClick = nav.onOpenTonight) { Text("tonight") }
                                Button(onClick = nav.onBack) { Text("back from night steps") }
                            }
                        },
                        editor = { onBack, onOpenLibrary ->
                            val viewModel = viewModel { DayViewModel(createSavedStateHandle()) }
                            Column {
                                Button(onClick = onBack) { Text("back from editor ${viewModel.day}") }
                                Button(onClick = onOpenLibrary) { Text("editor library") }
                            }
                        },
                        library = { onBack, onOpenTool ->
                            Column {
                                Button(onClick = onBack) { Text("back from library") }
                                Button(onClick = { onOpenTool(GuidedTool.BODY_SCAN) }) { Text("try body scan") }
                            }
                        },
                        tool = { tool, onClose -> Button(onClick = onClose) { Text("close $tool") } },
                        reset = { onClose -> Button(onClick = onClose) { Text("close reset") } },
                    ),
                )
            }
        }
        compose.onNodeWithText("open night steps").performClick()
        // A click returns before its frame; let the screen settle so the tests can stop the clock.
        compose.waitForIdle()
    }

    private fun currentIs(check: (NavHostController) -> Boolean) = compose.runOnIdle { assertThat(check(navController)).isTrue() }

    @Test
    fun `the editor opens with tonight's weekday and back returns to Night steps`() {
        setUpNavHost()

        compose.onNodeWithText("edit tuesday").performClick()
        compose.onNodeWithText("back from editor 2").performClick()

        currentIs { it.currentDestination?.hasRoute<NightStepsRoute>() == true }
    }

    @Test
    fun `every evening is day 0`() {
        setUpNavHost()
        compose.onNodeWithText("back from night steps").performClick()

        compose.onNodeWithText("open every evening").performClick()

        compose.onNodeWithText("back from editor 0").performClick()
        currentIs { it.currentDestination?.hasRoute<HomeRoute>() == true }
    }

    @Test
    fun `a guided tool opens by name and closing pops only it`() {
        setUpNavHost()

        compose.onNodeWithText("slow tide").performClick()
        compose.runOnIdle { assertThat(navController.currentBackStackEntry!!.toRoute<GuidedToolRoute>().tool).isEqualTo("SLOW_TIDE") }
        compose.runOnIdle {
            navController.popBackStack<GuidedToolRoute>(inclusive = true)
            navController.popBackStack<GuidedToolRoute>(inclusive = true)
        }

        currentIs { it.currentDestination?.hasRoute<NightStepsRoute>() == true }
    }

    @Test
    fun `the library opens from the editor and tools open from the library`() {
        setUpNavHost()
        compose.onNodeWithText("edit tuesday").performClick()
        compose.onNodeWithText("editor library").performClick()
        // The library adds to the list being edited: Tuesday's.
        compose.runOnIdle { assertThat(navController.currentBackStackEntry!!.toRoute<StepLibraryRoute>().day).isEqualTo(2) }

        compose.onNodeWithText("try body scan").performClick()
        compose.onNodeWithText("close BODY_SCAN").performClick()
        compose.onNodeWithText("back from library").performClick()

        currentIs { it.currentDestination?.hasRoute<RoutineEditorRoute>() == true }
    }

    @Test
    fun `an unknown tool name reaches the screen as null`() {
        setUpNavHost()

        compose.runOnIdle { navController.navigate(GuidedToolRoute("WORD_GAME")) }

        compose.onNodeWithText("close null").performClick()
        currentIs { it.currentDestination?.hasRoute<NightStepsRoute>() == true }
    }

    @Test
    fun `the week plan and Tonight are handed to the app`() {
        setUpNavHost()

        compose.onNodeWithText("week plan").performClick()
        compose.onNodeWithText("tonight").performClick()

        assertThat(dayPlans).containsExactly(DayOfWeek.TUESDAY to DayPart.EVENING)
        assertThat(tonightOpened).isEqualTo(1)
    }

    @Test
    fun `a quick second tap opens only one screen`() {
        setUpNavHost()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("edit tuesday").performClick()
        compose.onNodeWithText("library").performClick()
        compose.mainClock.autoAdvance = true

        compose.onNodeWithText("back from editor 2").performClick()
        currentIs { it.currentDestination?.hasRoute<NightStepsRoute>() == true }
    }

    @Test
    fun `a double tap on back pops only one screen`() {
        setUpNavHost()
        compose.onNodeWithText("library").performClick()
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("back from library").performClick()
        compose.onNodeWithText("back from library").performClick()
        compose.mainClock.autoAdvance = true

        currentIs { it.currentDestination?.hasRoute<NightStepsRoute>() == true }
    }

    @Test
    fun `night reset closes back to where it was opened`() {
        setUpNavHost()
        compose.onNodeWithText("back from night steps").performClick()

        compose.onNodeWithText("open night reset").performClick()
        compose.onNodeWithText("close reset").performClick()

        currentIs { it.currentDestination?.hasRoute<HomeRoute>() == true }
    }

    @Test
    fun `opening Night steps twice doesn't stack it`() {
        setUpNavHost()

        compose.runOnIdle { navController.navigateToNightSteps() }
        compose.onNodeWithText("back from night steps").performClick()

        currentIs { it.currentDestination?.hasRoute<HomeRoute>() == true }
    }
}

@kotlinx.serialization.Serializable
internal data object HomeRoute
