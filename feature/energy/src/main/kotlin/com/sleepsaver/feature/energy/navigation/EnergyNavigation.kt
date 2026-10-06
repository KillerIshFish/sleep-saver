package com.sleepsaver.feature.energy.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.feature.energy.EnergyPlaceholder
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Energy tab: the 24-hour timeline. */
@Serializable
data object EnergyRoute

fun NavGraphBuilder.energyScreen(
    onEditNight: (LocalDate) -> Unit,
    onAddHabit: () -> Unit,
    onOpenHabit: (HabitType) -> Unit,
    onOpenSettings: () -> Unit,
) {
    composable<EnergyRoute> { EnergyPlaceholder() }
}
