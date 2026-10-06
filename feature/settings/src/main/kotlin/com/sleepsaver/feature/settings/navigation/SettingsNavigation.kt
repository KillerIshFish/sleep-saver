package com.sleepsaver.feature.settings.navigation

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.sleepsaver.feature.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable
data object SettingsRoute

/**
 * Adds the settings screen. Taps that navigate are dropped while the screen isn't resumed (e.g. still
 * animating in or out), so a double tap can't pop twice or open a screen twice. [onResetDone] runs once
 * "Reset all data" has finished; wire it to onboarding with the back stack cleared.
 */
fun NavGraphBuilder.settingsGraph(
    onBack: () -> Unit,
    onOpenHabits: () -> Unit,
    onOpenHealthConnectSetup: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onResetDone: () -> Unit,
) {
    composable<SettingsRoute> {
        SettingsScreen(
            onBack = dropUnlessResumed(block = onBack),
            onOpenHabits = dropUnlessResumed(block = onOpenHabits),
            onOpenHealthConnectSetup = dropUnlessResumed(block = onOpenHealthConnectSetup),
            onOpenPrivacyPolicy = dropUnlessResumed(block = onOpenPrivacyPolicy),
            onResetDone = onResetDone,
        )
    }
}

/** Opens settings; a second tap while it's already on top doesn't stack another copy. */
fun NavController.navigateToSettings() = navigate(SettingsRoute) { launchSingleTop = true }
