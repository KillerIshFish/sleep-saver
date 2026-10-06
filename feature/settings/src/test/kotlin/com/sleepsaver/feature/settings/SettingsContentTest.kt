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
import com.sleepsaver.core.domain.model.SourceSelection
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
    }

    private val actions = Recorder()

    private fun show(state: SettingsUiState = SettingsSamples.ready) {
        compose.setContent { SleepSaverTheme { SettingsContent(state, actions) } }
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
        compose.onNodeWithText("Allow notifications").performClick()

        assertThat(actions.calls).containsExactly("allowNotifications")
    }

    @Test
    fun `notifications blocked in system settings link there`() {
        show(SettingsSamples.ready.copy(notifications = NotificationAccess.BLOCKED))
        scrollTo("Notifications are off")
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

    @Test
    fun `a message is shown and then reported as shown`() {
        show(SettingsSamples.ready.copy(message = SettingsMessage.EXPORTED_CSV))
        compose.onNodeWithText("Your nights were saved as CSV").assertIsDisplayed()

        compose.mainClock.advanceTimeBy(10_000)
        compose.waitForIdle()

        assertThat(actions.calls).containsExactly("messageShown")
    }
}
