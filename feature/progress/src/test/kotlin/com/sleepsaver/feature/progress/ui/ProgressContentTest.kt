package com.sleepsaver.feature.progress.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.core.ui.testing.PHONE_PORTRAIT_DARK
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.feature.progress.NoNightsReason
import com.sleepsaver.feature.progress.ProgressFixtures
import com.sleepsaver.feature.progress.ProgressUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_PORTRAIT_DARK)
class ProgressContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = SampleData.today

    private val opened = mutableListOf<LocalDate>()
    private val added = mutableListOf<LocalDate>()
    private var settingsOpened = 0
    private var setupOpened = 0
    private var previous = 0
    private var next = 0
    private var latest = 0

    private fun show(state: ProgressUiState) {
        compose.setContent {
            SleepSaverTheme {
                ProgressContent(
                    state = state,
                    onPreviousWindow = { previous++ },
                    onNextWindow = { next++ },
                    onLatestWindow = { latest++ },
                    onOpenNight = { opened += it },
                    onAddMissingNight = { added += it },
                    onOpenSettings = { settingsOpened++ },
                    onOpenHealthConnectSetup = { setupOpened++ },
                )
            }
        }
    }

    @Test
    fun `header shows the window, next is disabled on the latest window`() {
        show(ProgressFixtures.ready())

        compose.onNodeWithText("Progress").assertIsDisplayed()
        compose.onNodeWithText("Sep 23 – Oct 6").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next two weeks").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Previous two weeks").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Settings").performClick()

        assertThat(previous).isEqualTo(1)
        assertThat(settingsOpened).isEqualTo(1)
    }

    @Test
    fun `tapping a bar opens that night, a placeholder adds it`() {
        show(ProgressFixtures.ready(ProgressFixtures.eventfulNights()))

        compose.onNodeWithContentDescription("Last night", substring = true).performClick()
        compose.onNodeWithContentDescription("Wednesday night, no data").performClick()

        assertThat(opened).containsExactly(today)
        assertThat(added).containsExactly(today.minusDays(5))
    }

    @Test
    fun `list rows open nights and add missing ones`() {
        show(ProgressFixtures.ready(ProgressFixtures.eventfulNights()))
        val list = compose.onNode(hasScrollAction())

        list.performScrollToNode(hasText("All sleep times"))
        list.performScrollToNode(hasText("Sunday night"))
        compose.onNodeWithText("Sunday night").performClick()
        // Only an afternoon nap that day: the night itself is missing.
        list.performScrollToNode(hasText("Saturday night"))
        compose.onNodeWithText("Saturday night").performClick()

        assertThat(opened).containsExactly(today.minusDays(1))
        assertThat(added).containsExactly(today.minusDays(2))
    }

    @Test
    fun `tabs switch between times, debt and quality`() {
        show(ProgressFixtures.ready())
        val list = compose.onNode(hasScrollAction())

        compose.onNodeWithText("Sleep debt").performClick()
        list.performScrollToNode(hasText("All sleep debt"))
        compose.onNodeWithText("All sleep debt").assertIsDisplayed()
        list.performScrollToNode(hasText("Today · Oct 6"))
        compose.onNodeWithText("Today · Oct 6").assertIsDisplayed()

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Sleep quality"))
        compose.onNodeWithText("Sleep quality").performClick()
        compose.onNodeWithText("Sleep stages").assertIsDisplayed()
        list.performScrollToNode(hasText("Resting heart rate"))
        compose.onNodeWithText("Resting heart rate").assertIsDisplayed()
    }

    @Test
    fun `quality without heart-rate data offers settings`() {
        show(ProgressFixtures.ready(withVitals = false))
        val list = compose.onNode(hasScrollAction())

        compose.onNodeWithText("Sleep quality").performClick()
        list.performScrollToNode(hasText("No heart-rate trends yet"))
        compose.onNodeWithText("Open settings").performClick()

        assertThat(settingsOpened).isEqualTo(1)
    }

    @Test
    fun `no nights shows the empty state with fix-it actions`() {
        show(ProgressUiState.NoNights(today))

        compose.onNodeWithText("Your trends start here").assertIsDisplayed()
        compose.onNodeWithText("Add a night").performClick()
        compose.onNodeWithText("Open settings").performClick()

        assertThat(added).containsExactly(today)
        assertThat(settingsOpened).isEqualTo(1)
    }

    @Test
    fun `without Health Connect the fix comes first`() {
        show(ProgressUiState.NoNights(today, NoNightsReason.HEALTH_CONNECT_UNAVAILABLE))

        compose.onNodeWithText("Health Connect isn't ready").assertIsDisplayed()
        compose.onNodeWithText("Set up Health Connect").performClick()
        compose.onNodeWithText("Add a night").performClick()

        assertThat(setupOpened).isEqualTo(1)
        assertThat(added).containsExactly(today)
    }

    @Test
    fun `without sleep access the empty state says so`() {
        show(ProgressUiState.NoNights(today, NoNightsReason.PERMISSION_DENIED))

        compose.onNodeWithText("Sleep access is off").assertIsDisplayed()
        compose.onNodeWithText("Set up Health Connect").performClick()

        assertThat(setupOpened).isEqualTo(1)
    }

    @Test
    fun `the selected tab survives recreation`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            SleepSaverTheme {
                ProgressContent(
                    state = ProgressFixtures.ready(),
                    onPreviousWindow = {},
                    onNextWindow = {},
                    onLatestWindow = {},
                    onOpenNight = {},
                    onAddMissingNight = {},
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithText("Sleep debt").performClick()
        compose.onNodeWithText("All sleep debt").assertExists()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("All sleep debt").assertExists()
    }

    @Test
    fun `an empty window offers to add a night or jump back`() {
        val nights = SampleData.nights()
        val state = ProgressFixtures.ready(
            listOf(nights.first().copy(wakeDate = today.minusDays(60))) + nights.filter { it.wakeDate > today.minusDays(14) },
            windowsBack = 1,
        )
        show(state)

        compose.onNodeWithText("Nothing recorded these two weeks").assertIsDisplayed()
        compose.onNodeWithText("Add a night").performClick()
        compose.onNodeWithText("Jump to latest").performClick()

        assertThat(added).containsExactly(state.window.to)
        assertThat(latest).isEqualTo(1)
    }

    @Test
    fun `loading shows the orb`() {
        show(ProgressUiState.Loading)

        compose.onNodeWithContentDescription("Gathering your nights…").assertIsDisplayed()
    }
}
