package com.sleepsaver.feature.onboarding.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.sleepsaver.feature.onboarding.OnboardingScreen
import com.sleepsaver.feature.onboarding.setup.HealthConnectSetupScreen
import kotlinx.serialization.Serializable

/** First-run flow. */
@Serializable
data object OnboardingRoute

/** Health Connect fix-it screen: not installed / update needed / permission denied / no Garmin data. */
@Serializable
data object HealthConnectSetupRoute

/**
 * Adds onboarding ([OnboardingRoute]) and the Health Connect fix-it screen ([HealthConnectSetupRoute]).
 * [onFinished] runs once onboarding has saved everything; [onBack] closes the fix-it screen. Back and the privacy
 * link are dropped while the screen isn't resumed, so a double tap can't pop twice or open two policies.
 */
fun NavGraphBuilder.onboardingGraph(
    onFinished: () -> Unit,
    onBack: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
) = onboardingGraph(
    onFinished = onFinished,
    onBack = onBack,
    onOpenPrivacyPolicy = onOpenPrivacyPolicy,
    onboarding = { finished, privacy -> OnboardingScreen(onFinished = finished, onOpenPrivacyPolicy = privacy) },
    setup = { back -> HealthConnectSetupScreen(onBack = back) },
)

/** [onboardingGraph] with the screens passed in, so tests can drive the real graph without Hilt. */
internal fun NavGraphBuilder.onboardingGraph(
    onFinished: () -> Unit,
    onBack: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onboarding: @Composable (onFinished: () -> Unit, onOpenPrivacyPolicy: () -> Unit) -> Unit,
    setup: @Composable (onBack: () -> Unit) -> Unit,
) {
    composable<OnboardingRoute> {
        onboarding(onFinished, dropUnlessResumed(block = onOpenPrivacyPolicy))
    }
    composable<HealthConnectSetupRoute> {
        setup(dropUnlessResumed(block = onBack))
    }
}

/** Opens the fix-it screen unless it's already on top (a double tap would otherwise stack two). */
fun NavController.navigateToHealthConnectSetup() {
    if (currentBackStackEntry?.destination?.hasRoute<HealthConnectSetupRoute>() == true) return
    navigate(HealthConnectSetupRoute)
}
