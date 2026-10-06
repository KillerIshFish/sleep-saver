package com.sleepsaver.feature.learn.navigation

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
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real [learnGraph] in a NavHost, with stand-in screens (the real ones need Hilt). */
@RunWith(AndroidJUnit4::class)
class LearnNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var navController: NavHostController

    /** Reads the slug once, like ArticleViewModel. */
    class SlugViewModel(handle: SavedStateHandle) : ViewModel() {
        val slug = handle.toRoute<ArticleRoute>().slug
    }

    private fun setUpNavHost() {
        compose.setContent {
            navController = rememberNavController()
            NavHost(navController, startDestination = LearnRoute) {
                learnGraph(
                    onOpenArticle = { navController.navigateToArticle(it) },
                    onBack = { navController.popBackStack() },
                    list = { open ->
                        Column {
                            Button(onClick = { open("caffeine") }) { Text("open caffeine") }
                            Button(onClick = { open("naps") }) { Text("open naps") }
                        }
                    },
                    article = { back ->
                        val viewModel = viewModel { SlugViewModel(createSavedStateHandle()) }
                        Button(onClick = back) { Text("back from ${viewModel.slug}") }
                    },
                )
            }
        }
    }

    private fun assertOnList() {
        compose.runOnIdle { assertThat(navController.currentDestination?.hasRoute<LearnRoute>()).isTrue() }
        compose.onNodeWithText("open naps").assertExists()
    }

    @Test
    fun `tapping a card opens that article and back returns to the list`() {
        setUpNavHost()

        compose.onNodeWithText("open naps").performClick()
        compose.onNodeWithText("back from naps").performClick()

        assertOnList()
    }

    @Test
    fun `opening another article while one is showing shows the new one`() {
        setUpNavHost()

        compose.runOnIdle { navController.navigateToArticle("caffeine") }
        compose.onNodeWithText("back from caffeine").assertExists()
        compose.runOnIdle { navController.navigateToArticle("naps") }

        compose.onNodeWithText("back from naps").assertExists()
        compose.runOnIdle { assertThat(navController.currentBackStackEntry!!.toRoute<ArticleRoute>().slug).isEqualTo("naps") }
    }

    @Test
    fun `opening the article already on top doesn't stack it twice`() {
        setUpNavHost()

        compose.runOnIdle {
            navController.navigateToArticle("naps")
            navController.navigateToArticle("naps")
        }
        compose.onNodeWithText("back from naps").assertExists()
        compose.runOnIdle { navController.popBackStack() }

        assertOnList()
    }

    @Test
    fun `a quick second tap on another card is ignored`() {
        setUpNavHost()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("open caffeine").performClick()
        compose.onNodeWithText("open naps").performClick()
        compose.mainClock.autoAdvance = true

        compose.onNodeWithText("back from caffeine").assertExists()
        compose.runOnIdle { navController.popBackStack() }
        assertOnList()
    }

    @Test
    fun `a double tap on back pops only the article`() {
        setUpNavHost()
        compose.onNodeWithText("open naps").performClick()
        compose.onNodeWithText("back from naps").assertExists()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("back from naps").performClick()
        compose.onNodeWithText("back from naps").performClick()
        compose.mainClock.autoAdvance = true

        assertOnList()
    }
}
