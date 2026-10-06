package com.sleepsaver.feature.review.navigation

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.sleepsaver.feature.review.detail.NightDetailScreen
import com.sleepsaver.feature.review.edit.EditNightScreen
import com.sleepsaver.feature.review.morning.MorningReviewScreen
import com.sleepsaver.feature.review.nap.AddNapScreen
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

/**
 * The review screens: morning review, night detail, edit night and add nap. Each closes by popping itself;
 * close/back taps on a screen that isn't resumed (still animating in or out) are dropped, so a double tap can't
 * pop twice. [onOpenSettings] backs the "Change data source" link in the "How we measure sleep" sheet.
 */
fun NavGraphBuilder.reviewGraph(
    navController: NavController,
    onOpenSettings: () -> Unit,
) {
    composable<MorningReviewRoute> {
        MorningReviewScreen(
            onClose = dropUnlessResumed { navController.popBackStack() },
            onOpenSettings = dropUnlessResumed(block = onOpenSettings),
        )
    }
    composable<NightDetailRoute> { entry ->
        val wakeDate = entry.toRoute<NightDetailRoute>().wakeDate
        NightDetailScreen(
            onBack = dropUnlessResumed { navController.popBackStack() },
            onAdjust = dropUnlessResumed { navController.navigate(EditNightRoute(wakeDate)) },
            onOpenSettings = dropUnlessResumed(block = onOpenSettings),
        )
    }
    composable<EditNightRoute> {
        EditNightScreen(
            onClose = dropUnlessResumed { navController.popBackStack() },
            onOpenSettings = dropUnlessResumed(block = onOpenSettings),
        )
    }
    composable<AddNapRoute> {
        AddNapScreen(onClose = dropUnlessResumed { navController.popBackStack() })
    }
}

/** Opens the review for [wakeDate] unless it's already on top (the app and a notification can both ask). */
fun NavController.navigateToMorningReview(wakeDate: LocalDate) {
    if (isOnTop<MorningReviewRoute> { it.wakeDate == wakeDate.toString() }) return
    navigate(MorningReviewRoute(wakeDate.toString()))
}

fun NavController.navigateToNightDetail(wakeDate: LocalDate) {
    if (isOnTop<NightDetailRoute> { it.wakeDate == wakeDate.toString() }) return
    navigate(NightDetailRoute(wakeDate.toString()))
}

fun NavController.navigateToEditNight(wakeDate: LocalDate) {
    if (isOnTop<EditNightRoute> { it.wakeDate == wakeDate.toString() }) return
    navigate(EditNightRoute(wakeDate.toString()))
}

fun NavController.navigateToAddNap() {
    if (isOnTop<AddNapRoute> { true }) return
    navigate(AddNapRoute)
}

/** True when the top entry is a [T] matching [match]: a double tap mustn't stack the same screen twice. */
private inline fun <reified T : Any> NavController.isOnTop(match: (T) -> Boolean): Boolean {
    val top = currentBackStackEntry ?: return false
    return top.destination.hasRoute<T>() && runCatching { match(top.toRoute<T>()) }.getOrDefault(false)
}
