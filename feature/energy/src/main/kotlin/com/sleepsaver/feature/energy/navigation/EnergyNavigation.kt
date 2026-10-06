package com.sleepsaver.feature.energy.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.feature.energy.EnergyScreen
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Energy tab: the 24-hour timeline. */
@Serializable
data object EnergyRoute

/** The callbacks the Energy tab navigates with. */
internal class EnergyActions(
    val onEditNight: (LocalDate) -> Unit,
    val onAddHabit: () -> Unit,
    val onOpenHabit: (HabitType) -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenHealthConnectSetup: () -> Unit = onOpenSettings,
)

/**
 * Adds the Energy tab. Taps while the screen isn't resumed (still animating in or out) are dropped, so a double
 * tap can't open a destination twice.
 */
fun NavGraphBuilder.energyScreen(
    onEditNight: (LocalDate) -> Unit,
    onAddHabit: () -> Unit,
    onOpenHabit: (HabitType) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHealthConnectSetup: () -> Unit = onOpenSettings,
) = energyScreen(
    actions = EnergyActions(onEditNight, onAddHabit, onOpenHabit, onOpenSettings, onOpenHealthConnectSetup),
    screen = { actions ->
        EnergyScreen(
            onEditNight = actions.onEditNight,
            onAddHabit = actions.onAddHabit,
            onOpenHabit = actions.onOpenHabit,
            onOpenSettings = actions.onOpenSettings,
            onOpenHealthConnectSetup = actions.onOpenHealthConnectSetup,
        )
    },
)

/** [energyScreen] with the screen passed in, so tests can drive the real destination without Hilt. */
internal fun NavGraphBuilder.energyScreen(actions: EnergyActions, screen: @Composable (EnergyActions) -> Unit) {
    composable<EnergyRoute> { entry ->
        screen(
            EnergyActions(
                onEditNight = { date -> if (entry.isResumed()) actions.onEditNight(date) },
                onAddHabit = { if (entry.isResumed()) actions.onAddHabit() },
                onOpenHabit = { type -> if (entry.isResumed()) actions.onOpenHabit(type) },
                onOpenSettings = { if (entry.isResumed()) actions.onOpenSettings() },
                onOpenHealthConnectSetup = { if (entry.isResumed()) actions.onOpenHealthConnectSetup() },
            ),
        )
    }
}

private fun NavBackStackEntry.isResumed(): Boolean = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
