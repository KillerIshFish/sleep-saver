package com.sleepsaver.feature.sleep.navigation

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.sleepsaver.feature.sleep.SleepScreen
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Home tab. */
@Serializable
data object SleepRoute

/**
 * The home screen. Taps while the screen isn't resumed (still animating in or out, or already left) are dropped,
 * so a double tap can't navigate twice. [onOpenTonight] is the "Tonight" row (wire it to the Tonight tab).
 */
fun NavGraphBuilder.sleepScreen(
    onOpenNight: (LocalDate) -> Unit,
    onOpenReview: (LocalDate) -> Unit,
    onAddNap: () -> Unit,
    onAddMissingNight: (LocalDate) -> Unit,
    onOpenHabits: () -> Unit,
    onOpenEnergy: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHealthConnectSetup: () -> Unit,
    onOpenTonight: () -> Unit = {},
) {
    composable<SleepRoute> { entry ->
        SleepScreen(
            onOpenNight = { date -> entry.ifResumed { onOpenNight(date) } },
            onOpenReview = { date -> entry.ifResumed { onOpenReview(date) } },
            onAddNap = { entry.ifResumed(onAddNap) },
            onAddMissingNight = { date -> entry.ifResumed { onAddMissingNight(date) } },
            onOpenHabits = { entry.ifResumed(onOpenHabits) },
            onOpenEnergy = { entry.ifResumed(onOpenEnergy) },
            onOpenSettings = { entry.ifResumed(onOpenSettings) },
            onOpenHealthConnectSetup = { entry.ifResumed(onOpenHealthConnectSetup) },
            onOpenTonight = { entry.ifResumed(onOpenTonight) },
        )
    }
}

/** Same guard as `dropUnlessResumed`, for callbacks that take an argument. */
private inline fun NavBackStackEntry.ifResumed(block: () -> Unit) {
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) block()
}
