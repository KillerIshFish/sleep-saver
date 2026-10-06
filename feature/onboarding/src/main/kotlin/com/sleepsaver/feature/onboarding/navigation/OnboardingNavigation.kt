package com.sleepsaver.feature.onboarding.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.sleepsaver.feature.onboarding.OnboardingPlaceholder
import kotlinx.serialization.Serializable

/** First-run flow. */
@Serializable
data object OnboardingRoute

/** Health Connect fix-it screen: not installed / update needed / permission denied / no Garmin data. */
@Serializable
data object HealthConnectSetupRoute

fun NavGraphBuilder.onboardingGraph(
    onFinished: () -> Unit,
    onBack: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
) {
    composable<OnboardingRoute> { OnboardingPlaceholder("Onboarding") }
    composable<HealthConnectSetupRoute> { OnboardingPlaceholder("Health Connect setup") }
}

fun NavController.navigateToHealthConnectSetup() = navigate(HealthConnectSetupRoute)
