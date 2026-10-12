package com.sleepsaver.app.navigation

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
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
import androidx.compose.material.icons.outlined.Alarm
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.util.Consumer
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
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
import com.sleepsaver.feature.learn.navigation.LearnRoute
import com.sleepsaver.feature.learn.navigation.learnGraph
import com.sleepsaver.feature.learn.navigation.navigateToArticle
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
import com.sleepsaver.feature.tonight.navigation.TonightRoute
import com.sleepsaver.feature.tonight.navigation.navigateToAlarmCheck
import com.sleepsaver.feature.tonight.navigation.navigateToDayPlan
import com.sleepsaver.feature.tonight.navigation.navigateToNight
import com.sleepsaver.feature.tonight.navigation.navigateToWeekPlan
import com.sleepsaver.feature.tonight.navigation.tonightGraph
import com.sleepsaver.feature.winddown.navigation.navigateToNightReset
import com.sleepsaver.feature.winddown.navigation.navigateToNightSteps
import com.sleepsaver.feature.winddown.navigation.navigateToRoutineEditor
import com.sleepsaver.feature.winddown.navigation.nightStepsGraph
import java.time.LocalDate
import kotlin.reflect.KClass

internal enum class TopLevel(val route: Any, val icon: ImageVector, @StringRes val label: Int) {
    SLEEP(SleepRoute, Icons.Outlined.Bedtime, R.string.nav_sleep),
    ENERGY(EnergyRoute, Icons.Outlined.Bolt, R.string.nav_energy),

    /** Alarm, not NightsStay: that one is too close to Sleep's Bedtime icon. */
    TONIGHT(TonightRoute, Icons.Outlined.Alarm, R.string.nav_tonight),
    PROGRESS(ProgressRoute, Icons.Outlined.AutoGraph, R.string.nav_progress),
    LEARN(LearnRoute, Icons.Outlined.Lightbulb, R.string.nav_learn),
    ;

    val routeClass: KClass<*> get() = route::class
}

/**
 * The app: five tabs (Sleep, Energy, Tonight, Progress, Learn) over one NavHost. Feature modules never know each other;
 * every cross-feature step is a lambda wired here. [startInNight]: a cold launch during the night state (after Drop
 * anchor) opens the no-clock Night screen once, over the home tab.
 */
@Composable
fun SleepSaverApp(
    startWithOnboarding: Boolean,
    pendingReview: LocalDate?,
    onReviewShown: (LocalDate) -> Unit,
    startInNight: Boolean = false,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val currentTab = TopLevel.entries.firstOrNull { destination.isOn(it.routeClass) }

    // sleepsaver:// links from notifications, the alarm and the widget.
    DeepLinkHandler(navController)

    // Opened at night: the Night screen, once (not again after rotation).
    var nightShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startInNight) {
        if (startInNight && !startWithOnboarding && !nightShown) {
            nightShown = true
            navController.navigateToNight()
        }
    }

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
                appGraph(navController, openPrivacyPolicy)
            }
        }
    }
}

/**
 * Every feature's destinations and the cross-feature steps between them: Sleep → the Tonight tab; Tonight → Night
 * steps, the routine editor (a day's or every evening's), Night reset, Settings and the morning review; Night steps →
 * a day's plan and the Tonight tab; Settings → the Week plan and Alarm check; the Evening routine habit → the routine
 * editor (every evening); the Bedtime habit → Settings and the Week plan.
 */
internal fun NavGraphBuilder.appGraph(navController: NavHostController, openPrivacyPolicy: () -> Unit) {
    val openTonight = { navController.navigateToTab(TopLevel.TONIGHT) }
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
        onOpenTonight = openTonight,
    )
    energyScreen(
        onEditNight = navController::navigateToEditNight,
        onAddHabit = navController::navigateToHabits,
        onOpenHabit = navController::navigateToHabitDetail,
        onOpenSettings = navController::navigateToSettings,
        onOpenHealthConnectSetup = navController::navigateToHealthConnectSetup,
    )
    tonightGraph(
        navController = navController,
        onOpenNightSteps = navController::navigateToNightSteps,
        onOpenRoutineEditor = navController::navigateToRoutineEditor,
        onOpenNightReset = navController::navigateToNightReset,
        onOpenSettings = navController::navigateToSettings,
        onOpenMorningReview = navController::navigateToMorningReview,
    )
    nightStepsGraph(
        navController = navController,
        onOpenDayPlan = navController::navigateToDayPlan,
        // After Drop anchor: the Tonight screen itself, not the tab's saved stack (which has Night steps on top).
        onOpenTonight = navController::showTonight,
    )
    progressScreen(
        onOpenNight = navController::navigateToNightDetail,
        onAddMissingNight = navController::navigateToEditNight,
        onOpenSettings = navController::navigateToSettings,
        onOpenHealthConnectSetup = navController::navigateToHealthConnectSetup,
    )
    learnGraph(
        onOpenArticle = navController::navigateToArticle,
        onBack = { navController.popBackStack() },
    )
    reviewGraph(
        navController = navController,
        onOpenSettings = navController::navigateToSettings,
        onOpenHealthConnectSetup = navController::navigateToHealthConnectSetup,
    )
    // "Edit Night steps" on the Evening routine habit opens the one routine editor (every evening); Bedtime's timing
    // opens where the target bedtime is set: Settings or the week plan.
    habitsGraph(
        navController,
        onOpenRoutineEditor = { navController.navigateToRoutineEditor(null) },
        onOpenSettings = navController::navigateToSettings,
        onOpenWeekPlan = navController::navigateToWeekPlan,
    )
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
        onOpenWeekPlan = navController::navigateToWeekPlan,
        onOpenAlarmCheck = navController::navigateToAlarmCheck,
    )
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

internal fun NavHostController.navigateToTab(tab: TopLevel) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * The Tonight screen itself, with nothing on top: back to it when it is below (Night steps opened from the Tonight
 * tab), else the tab the way the bottom bar opens it, then down to the tab's root if its saved stack came back with a
 * screen on top. [navigateToTab] alone would restore the tab's stack, Night steps included, and stay put.
 */
internal fun NavHostController.showTonight() {
    if (currentDestination?.hasRoute<TonightRoute>() == true || popBackStack<TonightRoute>(inclusive = false)) return
    navigateToTab(TopLevel.TONIGHT)
    if (currentDestination?.hasRoute<TonightRoute>() != true) popBackStack<TonightRoute>(inclusive = false)
}

/** sleepsaver:// links ([openDeepLink]) for the launch intent and for intents delivered while the activity runs. */
@Composable
private fun DeepLinkHandler(navController: NavHostController) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    LaunchedEffect(Unit) {
        activity.intent?.let { if (navController.openDeepLink(it)) activity.intent = Intent() }
    }
    DisposableEffect(activity) {
        val listener = Consumer<Intent> { navController.openDeepLink(it) }
        activity.addOnNewIntentListener(listener)
        onDispose { activity.removeOnNewIntentListener(listener) }
    }
}

/**
 * Opens a `sleepsaver://` link; false when [intent] isn't one this app knows.
 *
 * - `home`, `energy`, `progress`, `tonight`: that tab (the widget opens `home`; habit reminders `energy`; the alarm's
 *   "show" intent and the bedtime reminder `tonight`).
 * - `nightsteps`: tonight's Night steps (the wind-down and evening-routine reminders).
 * - `night`: the no-clock Night screen (the night-state notification).
 * - `weekplan`: the Week plan.
 * - `alarmcheck`: Alarm check.
 * - `review?wakeDate=YYYY-MM-DD`: that night's morning review (today without a valid date).
 */
internal fun NavHostController.openDeepLink(intent: Intent): Boolean {
    val uri = intent.data ?: return false
    if (uri.scheme != "sleepsaver") return false
    when (uri.host) {
        "home" -> navigateToTab(TopLevel.SLEEP)
        "energy" -> navigateToTab(TopLevel.ENERGY)
        "progress" -> navigateToTab(TopLevel.PROGRESS)
        "tonight" -> navigateToTab(TopLevel.TONIGHT)
        "nightsteps" -> navigateToNightSteps()
        "night" -> navigateToNight()
        "weekplan" -> navigateToWeekPlan()
        "alarmcheck" -> navigateToAlarmCheck()
        "review" -> {
            val date = uri.getQueryParameter("wakeDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            navigateToMorningReview(date ?: LocalDate.now())
        }
        else -> return false
    }
    return true
}
