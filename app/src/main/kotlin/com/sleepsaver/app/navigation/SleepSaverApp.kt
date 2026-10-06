package com.sleepsaver.app.navigation

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoGraph
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.util.Consumer
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sleepsaver.app.PrivacyPolicyActivity
import com.sleepsaver.app.R
import com.sleepsaver.feature.energy.navigation.EnergyRoute
import com.sleepsaver.feature.energy.navigation.energyScreen
import com.sleepsaver.feature.habits.navigation.habitsGraph
import com.sleepsaver.feature.habits.navigation.navigateToHabitDetail
import com.sleepsaver.feature.habits.navigation.navigateToHabits
import com.sleepsaver.feature.onboarding.navigation.OnboardingRoute
import com.sleepsaver.feature.onboarding.navigation.navigateToHealthConnectSetup
import com.sleepsaver.feature.onboarding.navigation.onboardingGraph
import com.sleepsaver.feature.progress.navigation.ProgressRoute
import com.sleepsaver.feature.progress.navigation.progressScreen
import com.sleepsaver.feature.review.navigation.navigateToAddNap
import com.sleepsaver.feature.review.navigation.navigateToEditNight
import com.sleepsaver.feature.review.navigation.navigateToMorningReview
import com.sleepsaver.feature.review.navigation.navigateToNightDetail
import com.sleepsaver.feature.review.navigation.reviewGraph
import com.sleepsaver.feature.settings.navigation.navigateToSettings
import com.sleepsaver.feature.settings.navigation.settingsGraph
import com.sleepsaver.feature.sleep.navigation.SleepRoute
import com.sleepsaver.feature.sleep.navigation.sleepScreen
import java.time.LocalDate
import kotlin.reflect.KClass

private enum class TopLevel(val route: Any, val icon: ImageVector, @StringRes val label: Int) {
    SLEEP(SleepRoute, Icons.Outlined.Bedtime, R.string.nav_sleep),
    ENERGY(EnergyRoute, Icons.Outlined.Bolt, R.string.nav_energy),
    PROGRESS(ProgressRoute, Icons.Outlined.AutoGraph, R.string.nav_progress),
    LEARN(LearnTabRoute, Icons.Outlined.Lightbulb, R.string.nav_learn),
    ;

    val routeClass: KClass<*> get() = route::class
}

@Composable
fun SleepSaverApp(
    startWithOnboarding: Boolean,
    pendingReview: LocalDate?,
    onReviewShown: (LocalDate) -> Unit,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val currentTab = TopLevel.entries.firstOrNull { destination.isOn(it.routeClass) }

    // sleepsaver:// links from notifications and the widget.
    DeepLinkHandler(navController)

    // Offer the morning review once per session, when the user lands on the home tab.
    LaunchedEffect(pendingReview, currentTab) {
        if (pendingReview != null && currentTab == TopLevel.SLEEP) {
            onReviewShown(pendingReview)
            navController.navigateToMorningReview(pendingReview)
        }
    }

    val openPrivacyPolicy = { context.startActivity(Intent(context, PrivacyPolicyActivity::class.java)) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AnimatedVisibility(visible = currentTab != null, enter = fadeIn(), exit = fadeOut()) {
                BottomBar(currentTab = currentTab, onSelect = { navController.navigateToTab(it) })
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            NavHost(
                navController = navController,
                startDestination = if (startWithOnboarding) OnboardingRoute else SleepRoute,
            ) {
                onboardingGraph(
                    onFinished = {
                        navController.navigate(SleepRoute) {
                            popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() },
                    onOpenPrivacyPolicy = openPrivacyPolicy,
                )
                sleepScreen(
                    onOpenNight = navController::navigateToNightDetail,
                    onOpenReview = navController::navigateToMorningReview,
                    onAddNap = navController::navigateToAddNap,
                    onAddMissingNight = navController::navigateToEditNight,
                    onOpenHabits = navController::navigateToHabits,
                    onOpenEnergy = { navController.navigateToTab(TopLevel.ENERGY) },
                    onOpenSettings = navController::navigateToSettings,
                    onOpenHealthConnectSetup = navController::navigateToHealthConnectSetup,
                )
                energyScreen(
                    onEditNight = navController::navigateToEditNight,
                    onAddHabit = navController::navigateToHabits,
                    onOpenHabit = navController::navigateToHabitDetail,
                    onOpenSettings = navController::navigateToSettings,
                )
                progressScreen(
                    onOpenNight = navController::navigateToNightDetail,
                    onAddMissingNight = navController::navigateToEditNight,
                    onOpenSettings = navController::navigateToSettings,
                )
                learnTab(navController)
                reviewGraph(
                    navController = navController,
                    onOpenSettings = navController::navigateToSettings,
                )
                habitsGraph(navController)
                settingsGraph(
                    onBack = { navController.popBackStack() },
                    onOpenHabits = navController::navigateToHabits,
                    onOpenHealthConnectSetup = navController::navigateToHealthConnectSetup,
                    onOpenPrivacyPolicy = openPrivacyPolicy,
                    onResetDone = {
                        navController.navigate(OnboardingRoute) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BottomBar(currentTab: TopLevel?, onSelect: (TopLevel) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        TopLevel.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == currentTab,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.label)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

private fun NavDestination?.isOn(route: KClass<*>): Boolean =
    this?.hierarchy?.any { it.hasRoute(route) } == true

private fun NavHostController.navigateToTab(tab: TopLevel) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Handles `sleepsaver://home`, `sleepsaver://energy` and `sleepsaver://review?wakeDate=YYYY-MM-DD`
 * for the launch intent and for intents delivered while the activity is running.
 */
@Composable
private fun DeepLinkHandler(navController: NavHostController) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    LaunchedEffect(Unit) {
        activity.intent?.let { if (navController.openDeepLink(it)) activity.intent = Intent() }
    }
    DisposableEffect(activity) {
        val listener = Consumer<Intent> { navController.openDeepLink(it) }
        activity.addOnNewIntentListener(listener)
        onDispose { activity.removeOnNewIntentListener(listener) }
    }
}

private fun NavHostController.openDeepLink(intent: Intent): Boolean {
    val uri = intent.data ?: return false
    if (uri.scheme != "sleepsaver") return false
    when (uri.host) {
        "home" -> navigateToTab(TopLevel.SLEEP)
        "energy" -> navigateToTab(TopLevel.ENERGY)
        "progress" -> navigateToTab(TopLevel.PROGRESS)
        "review" -> {
            val date = uri.getQueryParameter("wakeDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            navigateToMorningReview(date ?: LocalDate.now())
        }
        else -> return false
    }
    return true
}
