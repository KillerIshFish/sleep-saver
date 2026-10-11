package com.sleepsaver.feature.tonight

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.hooks.AlarmIntents
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.DayPart
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.OverrideKind
import com.sleepsaver.core.domain.schedule.PlanPause
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.feature.tonight.day.DayPlanScreen
import com.sleepsaver.feature.tonight.day.DayPlanUiState
import com.sleepsaver.feature.tonight.day.DayPlanViewModel
import com.sleepsaver.feature.tonight.navigation.DayPlanNavigation
import com.sleepsaver.feature.tonight.navigation.WeekPlanNavigation
import com.sleepsaver.feature.tonight.week.WeekPlanScreen
import com.sleepsaver.feature.tonight.week.WeekPlanViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * Arming needs notifications and the "Alarm" category (SPEC_V2 §5.0), tested through the real screens and
 * ViewModels on Robolectric's notification shadows: when either is off nothing is applied and the screen says why,
 * with its fix.
 */
@RunWith(AndroidJUnit4::class)
class ArmingGateTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val activity get() = compose.activity
    private val notifications get() = activity.getSystemService(NotificationManager::class.java)
    private val fakes = TonightFakes()
    private val writer = TonightWriter(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), Dispatchers.Unconfined)

    private val saturday get() = fakes.schedule.weekState.value.day(DayOfWeek.SATURDAY).morning.alarm.mode

    @Before
    fun notificationsAllowed() {
        shadowOf(activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifications).setNotificationsEnabled(true)
    }

    private fun showWeekPlan() {
        val viewModel = WeekPlanViewModel(
            SavedStateHandle(), fakes.schedule, fakes.nightSteps, fakes.patterns, fakes.nightController, fakes.clockSource(), fakes.time, writer,
        )
        compose.setContent { SleepSaverTheme { WeekPlanScreen(WeekPlanNavigation(), viewModel = viewModel) } }
        compose.waitForIdle()
    }

    private fun showSaturdayPlan() {
        val viewModel = DayPlanViewModel(
            SavedStateHandle(mapOf("day" to DayOfWeek.SATURDAY.value, "part" to "MORNING")),
            fakes.schedule, fakes.nightSteps, fakes.patterns, fakes.nightController, fakes.time, writer,
        )
        compose.setContent { SleepSaverTheme { DayPlanScreen(DayPlanNavigation(), viewModel = viewModel) } }
        compose.waitForIdle()
    }

    private fun switchOnSaturday() {
        compose.onNodeWithContentDescription("Alarm on Saturday").performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** Notifications off and not yet asked: the prompt shows, and the user taps Don't allow. */
    private fun denyNotifications() {
        shadowOf(activity.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifications).setNotificationsEnabled(false)
    }

    private fun answerPrompt(granted: Boolean) {
        val request = shadowOf(activity).lastRequestedPermission
        assertThat(request.requestedPermissions.toList()).containsExactly(Manifest.permission.POST_NOTIFICATIONS)
        @Suppress("DEPRECATION")
        activity.onRequestPermissionsResult(
            request.requestCode,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED),
        )
        compose.waitForIdle()
    }

    private fun ringChannelOff() {
        notifications.createNotificationChannel(NotificationChannel(AlarmIntents.CHANNEL_RING, "Alarm", NotificationManager.IMPORTANCE_NONE))
    }

    // ---------------------------------------------------------------- Week plan switch

    @Test
    fun `with notifications and the alarm category on, the alarm switches on`() {
        showWeekPlan()

        switchOnSaturday()

        assertThat(saturday).isEqualTo(SleepConfig.DEFAULT.alarm.defaultMode)
    }

    @Test
    fun `notifications denied leaves the alarm off and says how to allow them`() {
        denyNotifications()
        showWeekPlan()

        switchOnSaturday()
        answerPrompt(granted = false)

        assertThat(saturday).isEqualTo(AlarmMode.OFF)
        assertThat(fakes.schedule.updatedDays).isEmpty()
        compose.onNodeWithText("Notifications are off").assertExists()
        compose.onNodeWithText("Alarms need notifications to show the Stop button.").assertExists()
        compose.onNodeWithText("Allow").assertExists()
    }

    @Test
    fun `notifications allowed at the prompt switch the alarm on`() {
        denyNotifications()
        showWeekPlan()

        switchOnSaturday()
        shadowOf(activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifications).setNotificationsEnabled(true)
        answerPrompt(granted = true)

        assertThat(saturday).isEqualTo(SleepConfig.DEFAULT.alarm.defaultMode)
        compose.onNodeWithText("Notifications are off").assertDoesNotExist()
    }

    @Test
    fun `the alarm category off leaves the alarm off and opens its settings`() {
        ringChannelOff()
        showWeekPlan()

        switchOnSaturday()

        assertThat(saturday).isEqualTo(AlarmMode.OFF)
        assertThat(fakes.schedule.updatedDays).isEmpty()
        compose.onNodeWithText("Alarm notifications are turned off").assertExists()
        compose.onNodeWithText("Open settings").performScrollTo().performClick()
        val opened = shadowOf(activity).nextStartedActivity
        assertThat(opened?.action).isEqualTo(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        assertThat(opened?.getStringExtra(Settings.EXTRA_CHANNEL_ID)).isEqualTo(AlarmIntents.CHANNEL_RING)
    }

    @Test
    fun `switching an alarm off never asks`() {
        ringChannelOff()
        showWeekPlan()

        compose.onNodeWithContentDescription("Alarm on Monday").performScrollTo().performClick()
        compose.waitForIdle()

        assertThat(fakes.schedule.weekState.value.day(DayOfWeek.MONDAY).morning.alarm.mode).isEqualTo(AlarmMode.OFF)
        compose.onNodeWithText("Alarm notifications are turned off").assertDoesNotExist()
    }

    // ---------------------------------------------------------------- Day plan mode

    @Test
    fun `the day plan's alarm mode waits for notifications`() {
        denyNotifications()
        showSaturdayPlan()

        compose.onNodeWithText("Surfacing").performClick()
        answerPrompt(granted = false)

        assertThat(saturday).isEqualTo(AlarmMode.OFF)
        assertThat(fakes.schedule.updatedDays).isEmpty()
        compose.onNodeWithText("Notifications are off").assertExists()
    }

    @Test
    fun `the day plan's alarm mode waits for the alarm category`() {
        ringChannelOff()
        showSaturdayPlan()

        compose.onNodeWithText("At wake time").performClick()
        compose.waitForIdle()

        assertThat(saturday).isEqualTo(AlarmMode.OFF)
        assertThat(fakes.schedule.updatedDays).isEmpty()
        compose.onNodeWithText("Alarm notifications are turned off").assertExists()
        compose.onNodeWithText("Open settings").assertExists()
    }

    @Test
    fun `the day plan's alarm mode applies when both are on`() {
        showSaturdayPlan()

        compose.onNodeWithText("At wake time").performClick()
        compose.waitForIdle()

        assertThat(saturday).isEqualTo(AlarmMode.FIXED)
    }

    // ---------------------------------------------------------------- Actions that bring an alarm back

    private fun showWeekPlanWith(oneOffs: OneOffs) {
        fakes.schedule.oneOffsState.value = oneOffs
        showWeekPlan()
    }

    @Test
    fun `undoing a day off waits for notifications`() {
        val wednesday = TonightSamples.today.plusDays(1)
        denyNotifications()
        showWeekPlanWith(PlanSamples.oneOffs(dayOff = wednesday))

        compose.onNodeWithText("Undo").performScrollTo().performClick()
        answerPrompt(granted = false)

        assertThat(fakes.schedule.clearedOverrides).isEmpty()
        compose.onNodeWithText("Notifications are off").assertExists()
    }

    @Test
    fun `undoing a one-off alarm on a free day never asks`() {
        val saturday = TonightSamples.today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
        ringChannelOff()
        showWeekPlanWith(
            OneOffs(
                listOf(
                    DateOverride(
                        saturday,
                        OverrideKind.JUST_ONCE,
                        LocalTime.of(8, 0),
                        AlarmMode.FIXED,
                    ),
                ),
            ),
        )

        compose.onNodeWithText("Undo").performScrollTo().performClick()
        compose.waitForIdle()

        assertThat(fakes.schedule.clearedOverrides).containsExactly(saturday)
        compose.onNodeWithText("Alarm notifications are turned off").assertDoesNotExist()
    }

    @Test
    fun `resuming paused alarms waits for the alarm category`() {
        ringChannelOff()
        showWeekPlanWith(
            OneOffs(pause = PlanPause(TonightSamples.today, TonightSamples.today.plusDays(4))),
        )

        compose.onNodeWithText("Resume").performScrollTo().performClick()
        compose.waitForIdle()

        assertThat(fakes.schedule.oneOffsState.value.pause).isNotNull()
        compose.onNodeWithText("Alarm notifications are turned off").assertExists()
    }

    @Test
    fun `copying the alarm onto days without one is arming, evenings or alarm days are not`() {
        val monday = DayPlanViewModel(
            SavedStateHandle(mapOf("day" to DayOfWeek.MONDAY.value, "part" to "MORNING")),
            fakes.schedule, fakes.nightSteps, fakes.patterns, fakes.nightController, fakes.time, writer,
        )
        compose.setContent { SleepSaverTheme { DayPlanScreen(DayPlanNavigation(), viewModel = monday) } }
        compose.waitForIdle()
        val ready = monday.uiState.value as DayPlanUiState.Ready

        assertThat(ready.copyArms(setOf(DayOfWeek.SATURDAY), setOf(DayPart.MORNING))).isTrue()
        assertThat(ready.copyArms(setOf(DayOfWeek.TUESDAY), setOf(DayPart.MORNING))).isFalse()
        assertThat(ready.copyArms(setOf(DayOfWeek.SATURDAY), setOf(DayPart.EVENING))).isFalse()
    }
}
