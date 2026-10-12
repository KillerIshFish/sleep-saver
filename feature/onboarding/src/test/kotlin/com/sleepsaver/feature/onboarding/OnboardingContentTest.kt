package com.sleepsaver.feature.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.healthconnect.HealthConnectStatus
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.core.ui.testing.SCREENSHOT_SDK
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.feature.onboarding.connect.ConnectActions
import com.sleepsaver.feature.onboarding.connect.ConnectionUiState
import com.sleepsaver.feature.onboarding.setup.HealthConnectSetupContent
import com.sleepsaver.feature.onboarding.setup.HealthConnectSetupUiState
import com.sleepsaver.feature.onboarding.setup.SetupActions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The stateless content: the right buttons show for each state and call the right callbacks. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [SCREENSHOT_SDK], qualifiers = "w411dp-h914dp-xxhdpi")
class OnboardingContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val connectActions = ConnectActions(
        onConnect = { calls += "connect" },
        onInstall = { calls += "install" },
        onOpenHealthConnect = { calls += "open hc" },
        onOpenGarmin = { calls += "open garmin" },
        onCheckAgain = { calls += "check again" },
        onUseSource = { calls += "use $it" },
        onExploreDemo = { calls += "demo" },
    )

    private val actions = OnboardingActions(
        onStart = { calls += "start" },
        onBack = { calls += "back" },
        onOpenPrivacyPolicy = { calls += "privacy" },
        connect = connectActions,
        onContinueFromConnect = { calls += "continue connect" },
        onRetryImport = { calls += "retry" },
        onContinueFromImport = { calls += "continue import" },
        onUseSuggestion = { calls += "use suggestion" },
        onContinueFromSleepNeed = { calls += "continue need" },
        onToggleHabit = { calls += "toggle $it" },
        onRemindChange = { calls += "remind $it" },
        onFinish = { calls += "finish" },
    )

    private fun show(state: OnboardingUiState) = compose.setContent { SleepSaverTheme { OnboardingContent(state, actions) } }

    @Test
    fun `welcome starts and links the privacy policy`() {
        show(OnboardingUiState())

        compose.onNodeWithText("Sleep Saver").assertIsDisplayed()
        compose.onNodeWithText("Sleep Saver is a wellness tool, not medical advice.").assertIsDisplayed()
        compose.onNodeWithText("Privacy policy").performClick()
        compose.onNodeWithText("Get started").performClick()

        assertThat(calls).containsExactly("privacy", "start").inOrder()
    }

    @Test
    fun `back arrow and progress show after the first step`() {
        show(OnboardingUiState(step = OnboardingStep.CONNECT, connection = Previews.needsPermission))

        compose.onNodeWithContentDescription("Step 2 of 5").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()

        assertThat(calls).containsExactly("back")
    }

    @Test
    fun `a double tap on back goes back one step only`() {
        var step by mutableStateOf(OnboardingStep.HABITS)
        val backs = mutableListOf<OnboardingStep>()
        compose.setContent {
            SleepSaverTheme {
                OnboardingContent(
                    OnboardingUiState(step = step, sleepNeed = Previews.sleepNeed, habits = Previews.habits),
                    OnboardingActions(onBack = { backs += step; step = OnboardingStep.entries[step.ordinal - 1] }),
                )
            }
        }

        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Back").performClick()
        compose.mainClock.advanceTimeBy(120)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertThat(backs).containsExactly(OnboardingStep.HABITS)
        assertThat(step).isEqualTo(OnboardingStep.SLEEP_NEED)
        // Once the step has settled, back works again.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertThat(step).isEqualTo(OnboardingStep.IMPORT) }
    }

    @Test
    fun `connect asks for access and always offers demo data`() {
        show(OnboardingUiState(step = OnboardingStep.CONNECT, connection = Previews.needsPermission))

        compose.onNodeWithText("Connect Health Connect").performClick()
        compose.onNodeWithText("Explore with demo data").performClick()

        assertThat(calls).containsExactly("connect", "demo").inOrder()
    }

    @Test
    fun `missing Health Connect offers the install`() {
        show(OnboardingUiState(step = OnboardingStep.CONNECT, connection = ConnectionUiState(status = HealthConnectStatus.NOT_INSTALLED)))

        compose.onNodeWithText("Get Health Connect first").assertIsDisplayed()
        compose.onNodeWithText("Install Health Connect").performClick()

        assertThat(calls).containsExactly("install")
    }

    @Test
    fun `unsupported phone goes straight to demo`() {
        show(OnboardingUiState(step = OnboardingStep.CONNECT, connection = ConnectionUiState(status = HealthConnectStatus.NOT_SUPPORTED)))

        compose.onNodeWithText("Explore with demo data").performClick()

        assertThat(calls).containsExactly("demo")
    }

    @Test
    fun `denied offers try again and Health Connect`() {
        show(OnboardingUiState(step = OnboardingStep.CONNECT, connection = Previews.needsPermission.copy(permissionAsked = true)))

        compose.onNodeWithText("Sleep access is off").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Open Health Connect").performClick()

        assertThat(calls).containsExactly("connect", "open hc").inOrder()
    }

    @Test
    fun `connected shows what was granted and continues`() {
        show(OnboardingUiState(step = OnboardingStep.CONNECT, connection = Previews.garminMissing))

        compose.onNodeWithText("Not available on this phone. We start from the last 30 days.").assertExists()
        compose.onNodeWithText("Not allowed. New nights arrive when you open the app.").assertExists()
        compose.onNodeWithText("Continue").performClick()

        assertThat(calls).containsExactly("continue connect")
    }

    @Test
    fun `no Garmin sleep explains the fix and offers the other apps`() {
        show(OnboardingUiState(step = OnboardingStep.CONNECT, connection = Previews.garminMissing))
        val list = compose.onNode(hasScrollAction())

        // Each target is scrolled into view first: a click lands at the node's centre, which may be off screen.
        list.performScrollToNode(hasText("No Garmin sleep yet"))
        list.performScrollToNode(hasText("Open Garmin Connect"))
        compose.onNodeWithText("Open Garmin Connect").performClick()
        list.performScrollToNode(hasText("Check again"))
        compose.onNodeWithText("Check again").performClick()
        list.performScrollToNode(hasText("Use Fitbit instead"))
        compose.onNodeWithText("Use Fitbit instead").performClick()
        list.performScrollToNode(hasText("Use all sources"))
        compose.onNodeWithText("Use all sources").performClick()

        assertThat(calls).containsExactly(
            "open garmin",
            "check again",
            "use ${SourceSelection.Single("com.fitbit.FitbitMobile")}",
            "use ${SourceSelection.All}",
        ).inOrder()
    }

    @Test
    fun `continue waits for the import`() {
        show(OnboardingUiState(step = OnboardingStep.IMPORT, import = ImportUiState.Importing(0.3f)))

        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("30%").assertIsDisplayed()
    }

    @Test
    fun `found nights are counted`() {
        show(OnboardingUiState(step = OnboardingStep.IMPORT, import = ImportUiState.Done(Previews.nights)))

        compose.onNodeWithText("42").assertIsDisplayed()
        compose.onNodeWithText("nights found").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()

        assertThat(calls).containsExactly("continue import")
    }

    @Test
    fun `a failed import can be retried or skipped`() {
        show(OnboardingUiState(step = OnboardingStep.IMPORT, import = ImportUiState.Failed(null)))

        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Continue anyway").performClick()

        assertThat(calls).containsExactly("retry", "continue import").inOrder()
    }

    @Test
    fun `the suggestion can be used`() {
        show(
            OnboardingUiState(
                step = OnboardingStep.SLEEP_NEED,
                sleepNeed = Previews.sleepNeed.copy(suggestion = NeedSuggestion(515, com.sleepsaver.core.domain.model.Confidence.MEDIUM, 6, 45)),
            ),
        )

        compose.onNodeWithText("Your free nights suggest 8h 35m (medium confidence)").assertIsDisplayed()
        // The same words as the Sleep tab's card.
        compose.onNodeWithText("Based on 6 nights you woke at least 45 min later than on a usual weekday.").assertIsDisplayed()
        compose.onNodeWithText("Use it").performClick()
        compose.onNodeWithText("Continue").performClick()

        assertThat(calls).containsExactly("use suggestion", "continue need").inOrder()
    }

    @Test
    fun `habits toggle by row and finish`() {
        show(OnboardingUiState(step = OnboardingStep.HABITS, habits = Previews.habits))

        compose.onNodeWithText("Remind me").performClick()
        compose.onNodeWithText("Caffeine cutoff").performClick()
        compose.onNodeWithText("Finish").performClick()

        assertThat(calls).containsExactly("remind false", "toggle ${HabitType.CAFFEINE_CUTOFF}", "finish").inOrder()
    }

    @Test
    fun `fix-it screen shows each status with its fix`() {
        val state = HealthConnectSetupUiState(
            connection = Previews.garminMissing,
            sync = SyncState(lastSuccessAt = SampleData.now.minusSeconds(12 * 60)),
            now = SampleData.now,
            zone = SampleData.zone,
        )
        compose.setContent {
            SleepSaverTheme {
                HealthConnectSetupContent(
                    state,
                    SetupActions(onBack = { calls += "back" }, connect = connectActions, onSyncNow = { calls += "sync" }),
                )
            }
        }

        compose.onNodeWithText("Installed and up to date.").assertExists()
        compose.onNodeWithText("No Garmin sleep in the last 30 days.").assertExists()
        compose.onNodeWithText("12 minutes ago.").assertExists()
        compose.onNodeWithText("Sync now").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        assertThat(calls).containsExactly("sync", "back").inOrder()
    }
}
