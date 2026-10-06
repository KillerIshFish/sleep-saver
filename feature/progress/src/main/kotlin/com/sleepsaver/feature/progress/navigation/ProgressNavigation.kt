package com.sleepsaver.feature.progress.navigation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.sleepsaver.feature.progress.ui.ProgressScreen
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Progress tab: Sleep Times / Sleep Debt / Sleep Quality over 14-day windows. */
@Serializable
data object ProgressRoute

/**
 * Adds the Progress tab. Taps that arrive while the tab isn't resumed (still animating in or out) are dropped,
 * so a double tap can't open a night twice.
 */
fun NavGraphBuilder.progressScreen(
    onOpenNight: (LocalDate) -> Unit,
    onAddMissingNight: (LocalDate) -> Unit,
    onOpenSettings: () -> Unit,
) {
    composable<ProgressRoute> { entry ->
        fun resumed() = entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ProgressScreen(
            onOpenNight = { if (resumed()) onOpenNight(it) },
            onAddMissingNight = { if (resumed()) onAddMissingNight(it) },
            onOpenSettings = dropUnlessResumed(block = onOpenSettings),
        )
    }
}
