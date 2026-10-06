package com.sleepsaver.feature.sleep.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.sleepsaver.feature.sleep.SleepPlaceholder
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Home tab. */
@Serializable
data object SleepRoute

fun NavGraphBuilder.sleepScreen(
    onOpenNight: (LocalDate) -> Unit,
    onOpenReview: (LocalDate) -> Unit,
    onAddNap: () -> Unit,
    onAddMissingNight: (LocalDate) -> Unit,
    onOpenHabits: () -> Unit,
    onOpenEnergy: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHealthConnectSetup: () -> Unit,
) {
    composable<SleepRoute> { SleepPlaceholder() }
}
