package com.sleepsaver.feature.sleep

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.domain.model.Confidence
import com.sleepsaver.core.domain.model.DailyInsights
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.SleepNeedEstimate
import com.sleepsaver.core.domain.model.SleepNeedEstimateResult
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.core.ui.format.formatDuration
import com.sleepsaver.core.ui.testing.PHONE_PORTRAIT_DARK
import com.sleepsaver.core.ui.testing.SCREENSHOT_SDK
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/** Taps on the stateless content reach the right callbacks. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [SCREENSHOT_SDK], qualifiers = PHONE_PORTRAIT_DARK)
class SleepContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = SampleData.today

    private fun ready(insights: DailyInsights = SampleData.insights(), pendingReview: LocalDate? = null) =
        readyState(insights, AppSettings(onboardingDone = true), SyncState(), SampleData.habits, pendingReview, SourceLabel.Garmin)

    private fun show(state: SleepUiState, actions: SleepActions) =
        compose.setContent { SleepSaverTheme { SleepContent(state, actions) } }

    @Test
    fun `chips open last night, the nap sheet and settings`() {
        val opened = mutableListOf<Any>()
        show(
            ready(),
            SleepActions(
                onOpenNight = { opened += it },
                onAddNap = { opened += "nap" },
                onOpenSettings = { opened += "settings" },
            ),
        )
        val slept = ready().lastNightSleep!!

        compose.onNodeWithText("${formatDuration(slept)} last night").performClick()
        compose.onNodeWithText("Nap").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()

        assertThat(opened).containsExactly(today, "nap", "settings").inOrder()
    }

    @Test
    fun `the source chip opens the data-source sheet`() {
        show(ready(), SleepActions())

        compose.onNodeWithContentDescription("Data source: Garmin").performClick()

        compose.onNodeWithText("Garmin Connect").assertIsDisplayed()
        compose.onNodeWithText("Change in Settings").assertIsDisplayed()
    }

    @Test
    fun `waiting banner syncs through Garmin Connect`() {
        var taps = 0
        show(ready(SampleData.insights(nights = SampleData.nights(includeLastNight = false))), SleepActions(onSyncNowViaGarmin = { taps++ }))

        compose.onNodeWithText("Waiting for last night from Garmin\u00A0Connect").assertIsDisplayed()
        compose.onNodeWithText("Sync now").performClick()

        assertThat(taps).isEqualTo(1)
    }

    @Test
    fun `missing banner adds today's night`() {
        val added = mutableListOf<LocalDate>()
        val now = SampleData.today.atTime(LocalTime.of(15, 10)).atZone(SampleData.zone).toInstant()
        show(
            ready(SampleData.insights(now = now, nights = SampleData.nights(includeLastNight = false))),
            SleepActions(onAddMissingNight = { added += it }),
        )

        compose.onNodeWithText("Last night is missing").assertIsDisplayed()
        compose.onNodeWithText("Add it").performClick()

        assertThat(added).containsExactly(today)
    }

    @Test
    fun `review banner opens the morning review`() {
        val opened = mutableListOf<LocalDate>()
        show(ready(pendingReview = today), SleepActions(onOpenReview = { opened += it }))

        compose.onNodeWithText("Last night is in").performClick()

        assertThat(opened).containsExactly(today)
    }

    @Test
    fun `need suggestion can be accepted or put off`() {
        val calls = mutableListOf<String>()
        val insights = SampleData.insights().copy(
            needEstimate = SleepNeedEstimateResult.Available(
                SleepNeedEstimate(Duration.ofMinutes(515), 9, Duration.ofMinutes(30), Confidence.HIGH, LocalTime.of(6, 50)),
            ),
        )
        show(ready(insights), SleepActions(onUseSuggestedNeed = { calls += "use" }, onDismissNeedSuggestion = { calls += "dismiss" }))

        compose.onNodeWithText("Your free nights suggest you need about 8h\u00A035m").assertExists()
        compose.onNodeWithText("Use 8h\u00A035m").performScrollTo().performClick()
        compose.onNodeWithText("Not now").performScrollTo().performClick()

        assertThat(calls).containsExactly("use", "dismiss").inOrder()
    }

    @Test
    fun `missing night chips add that night`() {
        val missing = today.minusDays(3)
        val added = mutableListOf<LocalDate>()
        show(
            ready(SampleData.insights(nights = SampleData.nights().filterNot { it.wakeDate == missing })),
            SleepActions(onAddMissingNight = { added += it }),
        )

        compose.onNodeWithText("1 night missing from the last 14 days").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Add Friday night").performScrollTo().performClick()

        assertThat(added).containsExactly(missing)
    }

    @Test
    fun `a long list of missing nights folds into a more chip`() {
        // History before the window, then only the last 5 nights: the other 9 nights in the window are missing.
        // (Gaps before someone's first-ever night don't count as missing, hence the older nights.)
        val nights = SampleData.nights().filter { it.wakeDate > today.minusDays(5) || it.wakeDate < today.minusDays(13) }
        show(ready(SampleData.insights(nights = nights)), SleepActions())

        compose.onNodeWithText("9 nights missing from the last 14 days").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Add Fri, Sep 25").assertDoesNotExist()
        compose.onNodeWithText("4 more").performScrollTo().performClick()

        compose.onNodeWithContentDescription("Add Fri, Sep 25").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("4 more").assertDoesNotExist()
    }

    @Test
    fun `next up opens habits and its bell toggles the reminder`() {
        val changes = mutableListOf<Pair<HabitType, Boolean>>()
        var habitsOpened = 0
        val state = ready()
        show(state, SleepActions(onReminderChange = { type, on -> changes += type to on }, onOpenHabits = { habitsOpened++ }))

        val bell = compose.onNodeWithContentDescription("Reminder").performScrollTo()
        bell.assertIsOn()
        bell.performClick()
        compose.onNodeWithText("All habits").performScrollTo().performClick()

        assertThat(changes).containsExactly(state.nextHabit!!.type to false)
        assertThat(habitsOpened).isEqualTo(1)
    }

    @Test
    fun `energy row opens the energy tab`() {
        var opened = 0
        show(ready(), SleepActions(onOpenEnergy = { opened++ }))

        compose.onNodeWithText("Energy potential").performScrollTo().performClick()

        assertThat(opened).isEqualTo(1)
    }

    @Test
    fun `missing Health Connect offers install and demo`() {
        val calls = mutableListOf<String>()
        show(
            SleepUiState.Empty(today, EmptyReason.INSTALL_HEALTH_CONNECT),
            SleepActions(onInstallHealthConnect = { calls += "install" }, onTryDemo = { calls += "demo" }),
        )

        compose.onNodeWithText("Try demo data").performScrollTo().performClick()
        compose.onNode(hasText("Install Health Connect") and hasClickAction()).performScrollTo().performClick()

        assertThat(calls).containsExactly("demo", "install").inOrder()
    }

    @Test
    fun `denied permission leads to the Health Connect setup`() {
        var opened = 0
        show(SleepUiState.Empty(today, EmptyReason.PERMISSION_DENIED), SleepActions(onOpenHealthConnectSetup = { opened++ }))

        compose.onNode(hasText("Allow sleep access") and hasClickAction()).performScrollTo().performClick()

        assertThat(opened).isEqualTo(1)
    }

    @Test
    fun `no data yet offers sync, a manual night and demo data`() {
        val calls = mutableListOf<Any>()
        show(
            SleepUiState.Empty(today, EmptyReason.NO_DATA),
            SleepActions(
                onSyncNowViaGarmin = { calls += "sync" },
                onAddMissingNight = { calls += it },
                onTryDemo = { calls += "demo" },
            ),
        )

        compose.onNodeWithText("How a night gets here").assertExists()
        compose.onNodeWithText("Sync now").performScrollTo().performClick()
        compose.onNodeWithText("Add a night").performScrollTo().performClick()
        compose.onNodeWithText("Try demo data").performScrollTo().performClick()

        assertThat(calls).containsExactly("sync", today, "demo").inOrder()
    }

    @Test
    fun `reminder bell shows off when the reminder is off`() {
        val state = ready().let { it.copy(nextHabit = it.nextHabit!!.copy(reminderOn = false)) }
        show(state, SleepActions())

        compose.onNodeWithContentDescription("Reminder").performScrollTo().assertIsOff()
    }
}
