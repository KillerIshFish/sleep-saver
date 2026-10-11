package com.sleepsaver.app.navigation

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.DialogNavigator
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.app.R
import com.sleepsaver.core.data.hooks.AlarmIntents
import com.sleepsaver.feature.review.navigation.MorningReviewRoute
import com.sleepsaver.feature.sleep.navigation.SleepRoute
import com.sleepsaver.feature.tonight.navigation.AlarmCheckRoute
import com.sleepsaver.feature.tonight.navigation.NightRoute
import com.sleepsaver.feature.tonight.navigation.TonightRoute
import com.sleepsaver.feature.tonight.navigation.WeekPlanRoute
import com.sleepsaver.feature.winddown.navigation.NightStepsRoute
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.reflect.KClass

/**
 * The app's real graph ([appGraph], every feature's destinations) in a NavHostController without composition: the
 * five tabs and every sleepsaver:// link the notifications, the alarm and the widget send.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppNavigationTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private val navController = NavHostController(context).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
        navigatorProvider.addNavigator(DialogNavigator())
        setViewModelStore(ViewModelStore())
        graph = createGraph(startDestination = SleepRoute) { appGraph(this@apply, openPrivacyPolicy = {}) }
    }

    private fun link(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    private fun isOn(route: KClass<*>): Boolean = navController.currentDestination?.hasRoute(route) == true

    @Test
    fun `five tabs, Tonight third with the alarm icon`() {
        assertThat(TopLevel.entries.map { it.name }).containsExactly("SLEEP", "ENERGY", "TONIGHT", "PROGRESS", "LEARN").inOrder()
        assertThat(TopLevel.TONIGHT.route).isEqualTo(TonightRoute)
        assertThat(TopLevel.TONIGHT.icon).isEqualTo(Icons.Outlined.Alarm)
        assertThat(context.getString(TopLevel.TONIGHT.label)).isEqualTo(context.getString(R.string.nav_tonight))
        assertThat(context.getString(R.string.nav_tonight)).isEqualTo("Tonight")
    }

    @Test
    fun `the Tonight links open the tab, Night steps, the Night screen, the Week plan and Alarm check`() {
        assertThat(navController.openDeepLink(link(AlarmIntents.DEEP_LINK_TONIGHT))).isTrue()
        assertThat(isOn(TonightRoute::class)).isTrue()

        assertThat(navController.openDeepLink(link(AlarmIntents.DEEP_LINK_NIGHT_STEPS))).isTrue()
        assertThat(isOn(NightStepsRoute::class)).isTrue()

        assertThat(navController.openDeepLink(link(AlarmIntents.DEEP_LINK_NIGHT))).isTrue()
        assertThat(isOn(NightRoute::class)).isTrue()

        assertThat(navController.openDeepLink(link(AlarmIntents.DEEP_LINK_WEEK_PLAN))).isTrue()
        assertThat(isOn(WeekPlanRoute::class)).isTrue()

        assertThat(navController.openDeepLink(link(AlarmIntents.DEEP_LINK_ALARM_CHECK))).isTrue()
        assertThat(isOn(AlarmCheckRoute::class)).isTrue()
    }

    @Test
    fun `a review link opens that night's review`() {
        val wakeDate = LocalDate.of(2026, 10, 6)

        assertThat(navController.openDeepLink(link(AlarmIntents.reviewLink(wakeDate)))).isTrue()

        assertThat(isOn(MorningReviewRoute::class)).isTrue()
        assertThat(navController.currentBackStackEntry?.toRoute<MorningReviewRoute>()?.wakeDate).isEqualTo(wakeDate.toString())
    }

    @Test
    fun `the home link goes back to the Sleep tab`() {
        navController.openDeepLink(link(AlarmIntents.DEEP_LINK_TONIGHT))

        assertThat(navController.openDeepLink(link("sleepsaver://home"))).isTrue()

        assertThat(isOn(SleepRoute::class)).isTrue()
    }

    @Test
    fun `other links are left alone`() {
        assertThat(navController.openDeepLink(link("sleepsaver://nowhere"))).isFalse()
        assertThat(navController.openDeepLink(link("https://example.com/tonight"))).isFalse()
        assertThat(navController.openDeepLink(Intent(Intent.ACTION_MAIN))).isFalse()
        assertThat(isOn(SleepRoute::class)).isTrue()
    }
}
