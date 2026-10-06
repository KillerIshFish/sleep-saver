package com.sleepsaver.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

// Temporary until :feature:learn's navigation is merged; then this becomes LearnRoute + learnGraph.
@Serializable
internal data object LearnTabRoute

internal fun NavGraphBuilder.learnTab(navController: NavHostController) {
    composable<LearnTabRoute> {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Learn") }
    }
}
