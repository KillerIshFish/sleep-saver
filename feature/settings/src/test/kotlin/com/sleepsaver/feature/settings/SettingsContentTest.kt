package com.sleepsaver.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.ClockFormat
import com.sleepsaver.core.domain.model.BedtimeMode
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.ui.platform.AlarmBlocker
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/** Taps on the stateless content: which callback fires, and which dialogs ask first. */
@RunWith(AndroidJUnit4::class)
class SettingsContentTest {

    @get:Rule
    val compose = createComposeRule()

    private class Recorder : SettingsActions {
        val calls = mutableListOf<String>()
        override fun onBack() { calls += "back" }
        override fun onSleepNeedChange(minutes: Int) { calls += "need:$minutes" }
        override fun onUseSuggestedNeed() { calls += "useSuggested" }
        override fun onStreakThresholdChange(minutes: Int) { calls += "streak:$minutes" }
        override fun onDebtLevelsChange(lowMaxMinutes: Int, highMinMinutes: Int) { calls += "debt:$lowMaxMinutes-$highMinMinutes" }
        override fun onSourceSelected(source: SourceSelection) { calls += "source:$source" }
        override fun onDemoModeChange(enabled: Boolean) { calls += "demo:$enabled" }
        override fun onOpenHealthConnect() { calls += "healthConnect" }
        override fun onSyncNow() { calls += "sync" }
        override fun onExport(format: ExportFormat) { calls += "export:$format" }
        override fun onOpenPrivacyPolicy() { calls += "privacy" }
        override fun onMorningReviewChange(enabled: Boolean) { calls += "morningReview:$enabled" }
        override fun onOpenHabits() { calls += "habits" }
        override fun onOpenNotificationSettings() { calls += "notificationSettings" }
        override fun onAllowNotifications() { calls += "allowNotifications" }
        override fun onClockFormatChange(format: ClockFormat) { calls += "clock:$format" }
        override fun onResetAll() { calls += "reset" }
        override fun onMessageShown() { calls += "messageShown" }
        override fun onOpenWeekPlan() { calls += "weekPlan" }
        override fun onOpenAlarmCheck() { calls += "alarmCheck" }
        override fun onTestRing() { calls += "testRing" }
        override fun onFixAlarmBlocker(blocker: AlarmBlocker) { calls += "fix:$blocker" }
    }

    private val actions = Recorder()

    private fun show(state: SettingsUiState = SettingsSamples.ready, alarms: AlarmsStatus = AlarmsStatus()) {
        compose.setContent { SleepSaverTheme { SettingsContent(state, actions, alarms = alarms) } }
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    @Test
    fun `back arrow goes back`() {
        show()
        compose.onNodeWithContentDescription("Back").performClick()
        assertThat(actions.calls).containsExactly("back")
    }

    @Test
    fun `sleep need is edited in a dialog and saved`() {
        show()
        compose.onNodeWithText("Sleep need").performClick()
        compose.onAllNodesWithContentDescription("Increase")[0].performClick()
        compose.onNodeWithText("Save").performClick()

        assertThat(actions.calls).containsExactly("need:485")
        compose.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun `the suggestion can be applied from the list`() {
        show()
        compose.onNodeWithText("Suggested: 8h 35m · medium confidence").assertIsDisplayed()
        compose.onNodeWithText("Use").performClick()
        assertThat(actions.calls).containsExactly("useSuggested")
    }

    @Test
    fun `cancel leaves the setting alone`() {
        show()
        scrollTo("Streak threshold")
        compose.onNodeWithText("Streak threshold").performClick()
        compose.onNodeWithContentDescription("Increase").performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertThat(actions.calls).isEmpty()
    }

    @Test
    fun `debt levels keep low below high`() {
        show(SettingsSamples.ready.copy(lowDebtMax = Duration.ofMinutes(210), highDebtMin = Duration.ofMinutes(240)))
        scrollTo("Debt levels")
        compose.onNodeWithText("Debt levels").performClick()

        // Low is 3h 30m and High 4h: Low can't go up and High can't go down.
        compose.onAllNodesWithContentDescription("Increase")[0].assertIsNotEnabled()
        compose.onAllNodesWithContentDescription("Decrease")[1].assertIsNotEnabled()
        compose.onAllNodesWithContentDescription("Increase")[1].performClick()
        compose.onAllNodesWithContentDescription("Increase")[0].performClick()
        compose.onNodeWithText("Save").performClick()

        assertThat(actions.calls).containsExactly("debt:240-270")
    }

    @Test
    fun `a source is picked from the list`() {
        show()
        scrollTo("Data source")
        compose.onNodeWithText("Data source").performClick()
        compose.onNodeWithText("Oura").performClick()
        compose.onNodeWithText("Save").performClick()

        assertThat(actions.calls).containsExactly("source:${SourceSelection.Single(SettingsSamples.OURA)}")
    }

    @Test
    fun `all sources can be picked`() {
        show()
        scrollTo("Data source")
        compose.onNodeWithText("Data source").performClick()
        compose.onNodeWithText("All sources (duplicates removed)").performClick()
        compose.onNodeWithText("Save").performClick()

        assertThat(actions.calls).containsExactly("source:${SourceSelection.All}")
    }

    @Test
    fun `turning demo data on needs no confirmation`() {
        show()
        scrollTo("Use demo data")
        compose.onNodeWithText("Use demo data").performClick()
        assertThat(actions.calls).containsExactly("demo:true")
    }

    @Test
    fun `turning demo data off asks first`() {
        show(SettingsSamples.ready.copy(demoMode = true))
        scrollTo("Use demo data")
        compose.onNodeWithText("Use demo data").performClick()

        compose.onNodeWithText("Turn off demo data?").assertIsDisplayed()
        assertThat(actions.calls).isEmpty()
        compose.onNodeWithText("Keep demo").performClick()
        assertThat(actions.calls).isEmpty()

        compose.onNodeWithText("Use demo data").performClick()
        compose.onNodeWithText("Turn off").performClick()
        assertThat(actions.calls).containsExactly("demo:false")
    }

    @Test
    fun `data rows reach their actions`() {
        show()
        scrollTo("Health Connect")
        compose.onNodeWithText("Health Connect").performClick()
        scrollTo("Sync now")
        compose.onNodeWithText("Sync now").performClick()
        scrollTo("Export as CSV")
        compose.onNodeWithText("Export as CSV").performClick()
        scrollTo("Export as JSON")
        compose.onNodeWithText("Export as JSON").performClick()
        scrollTo("Privacy policy")
        compose.onNodeWithText("Privacy policy").performClick()

        assertThat(actions.calls).containsExactly("healthConnect", "sync", "export:CSV", "export:JSON", "privacy").inOrder()
    }

    @Test
    fun `health connect that needs attention offers a fix`() {
        show(SettingsSamples.fresh)
        scrollTo("Health Connect")
        compose.onNodeWithText("Sleep access is off").assertIsDisplayed()
        compose.onNodeWithText("Allow").performClick()

        assertThat(actions.calls).containsExactly("healthConnect")
    }

    @Test
    fun `nothing to sync while demo data is on`() {
        show(SettingsSamples.ready.copy(demoMode = true))
        scrollTo("Health Connect")
        compose.onNodeWithText("Sync now").assertDoesNotExist()
    }

    @Test
    fun `notifications that were never allowed can be requested`() {
        show(SettingsSamples.ready.copy(notifications = NotificationAccess.PERMISSION_NEEDED))
        scrollTo("Notifications are off")
        scrollTo("Allow notifications")
        compose.onNodeWithText("Allow notifications").performClick()

        assertThat(actions.calls).containsExactly("allowNotifications")
    }

    @Test
    fun `notifications blocked in system settings link there`() {
        show(SettingsSamples.ready.copy(notifications = NotificationAccess.BLOCKED))
        scrollTo("Notifications are off")
        scrollTo("Open settings")
        compose.onNodeWithText("Open settings").performClick()

        assertThat(actions.calls).containsExactly("notificationSettings")
    }

    @Test
    fun `no warning while notifications are allowed`() {
        show()
        scrollTo("Morning review")
        compose.onNodeWithText("Notifications are off").assertDoesNotExist()
    }

    @Test
    fun `notification rows reach their actions`() {
        show()
        scrollTo("Morning review")
        compose.onNodeWithText("Morning review").performClick()
        scrollTo("Habit reminders")
        compose.onNodeWithText("Habit reminders").performClick()
        scrollTo("System notification settings")
        compose.onNodeWithText("System notification settings").performClick()

        assertThat(actions.calls).containsExactly("morningReview:false", "habits", "notificationSettings").inOrder()
    }

    @Test
    fun `time format switches`() {
        show()
        scrollTo("24-hour")
        compose.onNodeWithText("24-hour").performClick()
        assertThat(actions.calls).containsExactly("clock:H24")
    }

    @Test
    fun `reset asks first and explains what goes`() {
        show()
        scrollTo("Reset all data")
        compose.onNodeWithText("Reset all data").performClick()

        compose.onNodeWithText("Reset all data?").assertIsDisplayed()
        compose.onNodeWithText("Nights, naps and every edit you made").assertIsDisplayed()
        compose.onNodeWithText("Alarms, Night steps history, marks and Morning feel").assertIsDisplayed()
        assertThat(actions.calls).isEmpty()

        compose.onNodeWithText("Erase everything").performClick()
        assertThat(actions.calls).containsExactly("reset")
    }

    @Test
    fun `about shows the version and the privacy promise`() {
        show()
        scrollTo("Version 1.0.0 (1)")
        compose.onNodeWithText("Version 1.0.0 (1)").assertIsDisplayed()
        scrollTo("All data stays on this phone. No account, no servers, no analytics.")
        compose.onNodeWithText("All data stays on this phone. No account, no servers, no analytics.").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- Week plan and alarms

    @Test
    fun `the week plan row sums up the plan and opens it`() {
        show()
        scrollTo("Week plan")
        compose.onNodeWithText("Weekdays · 6:30", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Week plan").performClick()

        assertThat(actions.calls).containsExactly("weekPlan")
    }

    @Test
    fun `the week plan row says when times differ or nothing is set`() {
        show(SettingsSamples.ready.copy(weekPlan = WeekPlanSummary.Varied))
        scrollTo("Week plan")
        compose.onNodeWithText("Different times through the week").assertIsDisplayed()
    }

    @Test
    fun `wake time and bedtime show the usual times and say what they're for`() {
        show()
        scrollTo("Used on days without their own time.")
        compose.onNodeWithText("10:55", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a sleep-need bedtime speaks of the usual wake time it comes from, not tomorrow's`() {
        // The row shows the plan-free usual bedtime: tomorrow may have its own, earlier time in the week plan.
        show(SettingsSamples.ready.copy(bedtimeMode = BedtimeMode.SLEEP_NEED))
        scrollTo("Used on days without their own time.")
        compose.onNodeWithText("Leaves room for your 8h before your usual wake time").assertIsDisplayed()
    }

    @Test
    fun `the alarms section opens Alarm check and sets a test ring`() {
        show()
        scrollTo("Alarm check")
        compose.onNodeWithText("Everything looks ready").assertIsDisplayed()
        compose.onNodeWithText("Alarm check").performClick()
        scrollTo("Test ring in 1 minute")
        compose.onNodeWithText("Test ring in 1 minute").performClick()
        scrollTo("Alarm sounds and Hush are made on this phone. Nothing is downloaded.")

        assertThat(actions.calls).containsExactly("alarmCheck", "testRing").inOrder()
    }

    @Test
    fun `alarm check counts what needs fixing`() {
        show(alarms = AlarmsStatus(issues = 2))
        scrollTo("Alarm check")
        compose.onNodeWithText("2 things to fix").assertIsDisplayed()
    }

    @Test
    fun `a test ring stopped by blocked notifications shows how to allow them`() {
        show(alarms = AlarmsStatus(issues = 1, testRingBlocker = AlarmBlocker.NOTIFICATIONS_OFF))
        scrollTo("Alarms need notifications to show the Stop button.")
        compose.onNodeWithText("Allow").performClick()

        assertThat(actions.calls).containsExactly("fix:NOTIFICATIONS_OFF")
    }

    @Test
    fun `a pending test ring says when without a clock time`() {
        show(SettingsSamples.testRingSet.copy(message = SettingsMessage.TEST_RING_SET))
        compose.onNodeWithText("Test ring in about a minute").assertIsDisplayed()
        scrollTo("Test ring in 1 minute")
        compose.onNodeWithText("Set · rings in about a minute").assertIsDisplayed()
        compose.onNodeWithText("10:21", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a message is shown and then reported as shown`() {
        show(SettingsSamples.ready.copy(message = SettingsMessage.EXPORTED_CSV))
        compose.onNodeWithText("Your nights were saved as CSV").assertIsDisplayed()

        compose.mainClock.advanceTimeBy(10_000)
        compose.waitForIdle()

        assertThat(actions.calls).containsExactly("messageShown")
    }
}
