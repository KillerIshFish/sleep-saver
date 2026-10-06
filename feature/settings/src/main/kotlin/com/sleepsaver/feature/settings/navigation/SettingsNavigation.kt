package com.sleepsaver.feature.settings.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.sleepsaver.feature.settings.SettingsPlaceholder
import kotlinx.serialization.Serializable

@Serializable
data object SettingsRoute

fun NavGraphBuilder.settingsGraph(
    onBack: () -> Unit,
    onOpenHabits: () -> Unit,
    onOpenHealthConnectSetup: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onResetDone: () -> Unit,
) {
    composable<SettingsRoute> { SettingsPlaceholder() }
}

fun NavController.navigateToSettings() = navigate(SettingsRoute)
