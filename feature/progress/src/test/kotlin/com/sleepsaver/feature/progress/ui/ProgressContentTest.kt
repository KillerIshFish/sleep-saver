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
import com.sleepsaver.core.domain.patterns.KeelResult
import com.sleepsaver.core.domain.patterns.SocialJetlag
import com.sleepsaver.core.testing.PlanSamples
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
import java.time.Duration
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
        // Sleep times: the nights of Tue Sep 22 – Mon Oct 5, the first and last columns ("TUE" … "MON").
        compose.onNodeWithText("Nights of Sep 22 – Oct 5").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next two weeks").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Previous two weeks").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Settings").performClick()

        assertThat(previous).isEqualTo(1)
        assertThat(settingsOpened).isEqualTo(1)
    }

    @Test
    fun `the header names the nights on the night tabs and the days on Sleep debt`() {
        show(ProgressFixtures.ready())

        compose.onNodeWithText("Nights of Sep 22 – Oct 5").assertIsDisplayed()
        compose.onNodeWithText("Sleep debt").performClick()
        compose.onNodeWithText("Sep 23 – Oct 6").assertIsDisplayed()
        compose.onNodeWithText("Nights of Sep 22 – Oct 5").assertDoesNotExist()
        compose.onNodeWithText("Sleep quality").performClick()
        compose.onNodeWithText("Nights of Sep 22 – Oct 5").assertIsDisplayed()
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
        // The hint sits under "What went with your nights": bring its link fully on screen before tapping it.
        list.performScrollToNode(hasText("Open settings"))
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

    // ---------------------------------------------------------------- Keel

    @Test
    fun `keel shows its score, band, spread, social jetlag and what it compares`() {
        show(ProgressFixtures.ready(keel = PlanSamples.keelReady(), socialJetlag = PlanSamples.socialJetlag()))
        val list = compose.onNode(hasScrollAction())

        list.performScrollToNode(hasText("Keel"))
        compose.onNodeWithContentDescription("Keel 84, Even keel").assertIsDisplayed()
        compose.onNodeWithText("Bedtime varies by about ±30 min, wake time by about ±15 min.").assertExists()
        compose.onNodeWithText("Social jetlag: free days run 1h 15m later").assertExists()
        compose.onNodeWithText("Keel compares when you're asleep, 24 hours apart, across the last 14 days.").assertExists()
        compose.onNodeWithText("(free days are Sat and Sun until you set alarms)").assertDoesNotExist()
    }

    @Test
    fun `keel calibrating says how many days it has`() {
        show(
            ProgressFixtures.ready(
                keel = PlanSamples.keelCalibrating(),
                socialJetlag = SocialJetlag(Duration.ofMinutes(-10), freeNights = 2, workNights = 3, freeDaysFromPlan = false),
            ),
        )
        val list = compose.onNode(hasScrollAction())

        list.performScrollToNode(hasText("Keel · Calibrating: 4 of 7 days with nights"))
        compose.onNodeWithText("Free days and alarm days line up well.").assertExists()
        compose.onNodeWithText("(free days are Sat and Sun until you set alarms)").assertExists()
    }

    @Test
    fun `keel with enough nights but none in a row asks for two in a row`() {
        show(ProgressFixtures.ready(keel = KeelResult.Calibrating(validDays = 7, needed = 7)))

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Keel · Calibrating: needs two nights in a row"))
    }

    @Test
    fun `social jetlag earlier on free days`() {
        show(
            ProgressFixtures.ready(
                keel = PlanSamples.keelReady(),
                socialJetlag = SocialJetlag(Duration.ofMinutes(-80), freeNights = 4, workNights = 10, freeDaysFromPlan = true),
            ),
        )

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Social jetlag: free days run 1h 20m earlier"))
    }

    // ---------------------------------------------------------------- What went with your nights

    @Test
    fun `marks card lists patterns with their label and the caveat`() {
        show(ProgressFixtures.ready(markEffects = PlanSamples.markEffects()))
        val list = compose.onNode(hasScrollAction())

        compose.onNodeWithText("Sleep quality").performClick()
        list.performScrollToNode(hasText("What went with your nights"))
        list.performScrollToNode(hasText("Nights after alcohol: about 40 min less sleep (9 vs 41 nights)"))
        compose.onNodeWithText("Clear pattern").assertExists()
        list.performScrollToNode(hasText("Nights after late caffeine: about 10 min more awake time (7 vs 43 nights)"))
        compose.onNodeWithText("Possible pattern").assertExists()
        list.performScrollToNode(hasText("This shows what went together, not what caused it."))
    }

    @Test
    fun `not enough nights yet opens on tap`() {
        show(ProgressFixtures.ready(markEffects = PlanSamples.markEffects()))
        val list = compose.onNode(hasScrollAction())
        compose.onNodeWithText("Sleep quality").performClick()

        list.performScrollToNode(hasText("Not enough nights yet"))
        compose.onNodeWithText("Stress: 4 of 6 nights").assertDoesNotExist()
        compose.onNodeWithText("Not enough nights yet").performClick()
        list.performScrollToNode(hasText("Stress: 4 of 6 nights"))
        compose.onNodeWithText("Stress: 4 of 6 nights").assertIsDisplayed()
    }

    @Test
    fun `without marks the card says when patterns appear`() {
        show(ProgressFixtures.ready())
        val list = compose.onNode(hasScrollAction())
        compose.onNodeWithText("Sleep quality").performClick()

        list.performScrollToNode(hasText("What went with your nights"))
        list.performScrollToNode(
            hasText("Add marks in the morning review or during Night steps. Patterns show once a mark has 6 nights with it and 6 without."),
        )
        compose.onNodeWithText("This shows what went together, not what caused it.").assertDoesNotExist()
    }
}
