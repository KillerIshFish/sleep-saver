package com.sleepsaver.feature.onboarding.navigation

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
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real [onboardingGraph] in a NavHost, with stand-in screens (the real ones need Hilt). */
@RunWith(AndroidJUnit4::class)
class OnboardingNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    @Serializable
    data object HomeRoute

    private lateinit var navController: NavHostController
    private var finished = 0
    private var privacyOpened = 0
    private var setupBack: () -> Unit = {}

    private fun setUp(start: Any) {
        compose.setContent {
            navController = rememberNavController()
            NavHost(navController, startDestination = start) {
                composable<HomeRoute> {
                    Button(onClick = { navController.navigateToHealthConnectSetup() }) { Text("open setup") }
                }
                onboardingGraph(
                    onFinished = { finished++ },
                    onBack = { navController.popBackStack() },
                    onOpenPrivacyPolicy = { privacyOpened++ },
                    onboarding = { done, privacy ->
                        Column {
                            Button(onClick = done) { Text("finish") }
                            Button(onClick = privacy) { Text("privacy") }
                        }
                    },
                    setup = { back ->
                        setupBack = back
                        Button(onClick = back) { Text("back from setup") }
                    },
                )
            }
        }
    }

    @Test
    fun `onboarding reports finish and opens the privacy policy`() {
        setUp(OnboardingRoute)

        compose.onNodeWithText("privacy").performClick()
        compose.onNodeWithText("finish").performClick()

        compose.runOnIdle {
            assertThat(privacyOpened).isEqualTo(1)
            assertThat(finished).isEqualTo(1)
        }
    }

    @Test
    fun `setup opens once and back closes it`() {
        setUp(HomeRoute)

        compose.runOnIdle {
            navController.navigateToHealthConnectSetup()
            navController.navigateToHealthConnectSetup()
        }
        compose.onNodeWithText("back from setup").performClick()

        compose.runOnIdle { assertThat(navController.currentDestination?.hasRoute<HomeRoute>()).isTrue() }
        compose.onNodeWithText("open setup").assertExists()
    }

    @Test
    fun `a double tap on back pops only the setup screen`() {
        setUp(HomeRoute)
        compose.onNodeWithText("open setup").performClick()
        compose.onNodeWithText("back from setup").assertExists()

        // Two taps before the screen goes away: the second must not pop home as well.
        compose.runOnIdle {
            setupBack()
            setupBack()
        }

        compose.runOnIdle {
            assertThat(navController.currentDestination?.hasRoute<HomeRoute>()).isTrue()
            assertThat(navController.previousBackStackEntry).isNull()
        }
    }
}
