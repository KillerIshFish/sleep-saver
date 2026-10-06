package com.sleepsaver.feature.review.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.sleepsaver.feature.review.ReviewPlaceholder
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Morning review flow for the night that ended on [wakeDate] (ISO-8601). Deep link: sleepsaver://review?wakeDate=YYYY-MM-DD */
@Serializable
data class MorningReviewRoute(val wakeDate: String)

/** Night detail (from the home "last night" chip, Progress lists, the timeline). */
@Serializable
data class NightDetailRoute(val wakeDate: String)

/** Edit a night, or add a missing one when there's no data for [wakeDate]. */
@Serializable
data class EditNightRoute(val wakeDate: String)

/** "+ Nap". */
@Serializable
data object AddNapRoute

fun NavGraphBuilder.reviewGraph(
    navController: NavController,
    onOpenSettings: () -> Unit,
) {
    composable<MorningReviewRoute> { ReviewPlaceholder("Morning review") }
    composable<NightDetailRoute> { ReviewPlaceholder("Night detail") }
    composable<EditNightRoute> { ReviewPlaceholder("Edit night") }
    composable<AddNapRoute> { ReviewPlaceholder("Add nap") }
}

fun NavController.navigateToMorningReview(wakeDate: LocalDate) = navigate(MorningReviewRoute(wakeDate.toString()))
fun NavController.navigateToNightDetail(wakeDate: LocalDate) = navigate(NightDetailRoute(wakeDate.toString()))
fun NavController.navigateToEditNight(wakeDate: LocalDate) = navigate(EditNightRoute(wakeDate.toString()))
fun NavController.navigateToAddNap() = navigate(AddNapRoute)
