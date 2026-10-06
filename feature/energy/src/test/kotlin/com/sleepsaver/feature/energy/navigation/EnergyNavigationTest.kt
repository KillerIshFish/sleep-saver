package com.sleepsaver.feature.energy.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.HabitType
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** The real [energyScreen] destination in a NavHost, with a stand-in screen (the real one needs Hilt). */
@RunWith(AndroidJUnit4::class)
class EnergyNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    @Serializable
    data object OtherRoute

    private lateinit var navController: NavHostController
    private val edited = mutableListOf<LocalDate>()
    private val habits = mutableListOf<HabitType>()

    private fun setUpNavHost() {
        compose.setContent {
            navController = rememberNavController()
            NavHost(navController, startDestination = EnergyRoute) {
                energyScreen(
                    actions = EnergyActions(
                        onEditNight = { edited += it; navController.navigate(OtherRoute) },
                        onAddHabit = { navController.navigate(OtherRoute) },
                        onOpenHabit = { habits += it; navController.navigate(OtherRoute) },
                        onOpenSettings = { navController.navigate(OtherRoute) },
                    ),
                    screen = { actions ->
                        Column {
                            Button(onClick = { actions.onEditNight(LocalDate.of(2026, 10, 6)) }) { Text("edit") }
                            Button(onClick = { actions.onOpenHabit(HabitType.BEDTIME) }) { Text("habit") }
                            Button(onClick = actions.onOpenSettings) { Text("settings") }
                        }
                    },
                )
                composable<OtherRoute> { Text("other") }
            }
        }
    }

    @Test
    fun `callbacks reach the app with their arguments`() {
        setUpNavHost()

        compose.onNodeWithText("edit").performClick()

        compose.onNodeWithText("other").assertExists()
        assertThat(edited).containsExactly(LocalDate.of(2026, 10, 6))
    }

    @Test
    fun `a quick second tap while leaving is dropped`() {
        setUpNavHost()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("habit").performClick()
        compose.onNodeWithText("habit").performClick()
        compose.onNodeWithText("settings").performClick()
        compose.mainClock.autoAdvance = true

        compose.onNodeWithText("other").assertExists()
        assertThat(habits).containsExactly(HabitType.BEDTIME)
        compose.runOnIdle {
            assertThat(navController.currentBackStack.value.count { it.destination.hasRoute<OtherRoute>() }).isEqualTo(1)
        }
    }
}
