package com.sleepsaver.feature.progress.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.sleepsaver.feature.progress.ProgressPlaceholder
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Progress tab: Sleep Times / Sleep Debt / Sleep Quality over 14-day windows. */
@Serializable
data object ProgressRoute

fun NavGraphBuilder.progressScreen(
    onOpenNight: (LocalDate) -> Unit,
    onAddMissingNight: (LocalDate) -> Unit,
    onOpenSettings: () -> Unit,
) {
    composable<ProgressRoute> { ProgressPlaceholder() }
}
