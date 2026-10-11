package com.sleepsaver.feature.review.morning

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.alarm.LandingStage
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.debt.SleepDebtCalculator
import com.sleepsaver.core.domain.journal.FeelSource
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.journal.MorningFeelEntry
import com.sleepsaver.core.domain.journal.TomorrowList
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.core.ui.testing.PHONE_PORTRAIT_DARK
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.feature.review.common.NightSource
import com.sleepsaver.feature.review.edit.NightEditorCallbacks
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration

/** Morning feel, the odd-night prompt, the Tomorrow list, Wake landing and marks in the morning review. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_PORTRAIT_DARK)
class MorningReviewContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = SampleData.today
    private val nights = SampleData.nights()
    private val lastNight = nights.single { it.wakeDate == today }
    private val offset = SampleData.zone.rules.getOffset(SampleData.now)

    private val feels = mutableListOf<MorningFeel>()
    private val toggled = mutableListOf<Mark>()
    private var skipped = 0
    private var resets = 0

    private val actions = MorningReviewActions(
        onClose = {}, onSyncNow = {}, onLooksRight = {}, onAdjust = {}, onAdjustCancel = {}, onAdjustDone = {},
        onDone = {}, onDontShowAgain = {}, editor = NightEditorCallbacks.Noop,
        onFeel = { feels += it },
        onMarkToggle = { toggled += it },
        onOddNightDismiss = { skipped++ },
        onResetTuning = { resets++ },
    )

    private fun summary() = ReviewStep.Summary(lastNight, NightSource.of(lastNight, SampleData.zone, SampleData.now))

    private fun result(): ReviewStep.Result {
        val calculator = SleepDebtCalculator()
        val breakdown = calculator.breakdown(today, nights.associateBy { it.wakeDate }, Duration.ofHours(8))
        return ReviewStep.Result(DebtUpdate(breakdown, calculator.status(breakdown.before), calculator.status(breakdown.after)))
    }

    private fun show(state: MorningReviewUiState) {
        compose.setContent { SleepSaverTheme { MorningReviewContent(state, actions, animateResult = false) } }
    }

    private fun ringFeel(feel: MorningFeel) = MorningFeelEntry(today, feel, SampleData.now, offset, FeelSource.RING)

    // ---------------------------------------------------------------- Morning feel

    @Test
    fun `an unanswered morning asks how you feel, one tap answers`() {
        show(MorningReviewUiState(today, today, summary()))

        compose.onNodeWithText("How do you feel this morning?").assertIsDisplayed()
        compose.onNodeWithText("Clear").performClick()

        assertThat(feels).containsExactly(MorningFeel.CLEAR)
    }

    @Test
    fun `a feel from the ringing screen is a chip that opens the scale`() {
        show(MorningReviewUiState(today, today, summary(), feel = ringFeel(MorningFeel.SLOW)))

        compose.onNodeWithText("How do you feel this morning?").assertDoesNotExist()
        compose.onNodeWithText("Morning feel: Slow · Change").performClick()
        compose.onNodeWithText("How do you feel this morning?").assertIsDisplayed()
        compose.onNodeWithText("Bright").performClick()

        assertThat(feels).containsExactly(MorningFeel.BRIGHT)
    }

    @Test
    fun `after the next wake time the feel is read-only`() {
        show(MorningReviewUiState(today, today, summary(), feel = ringFeel(MorningFeel.SLOW), feelEditable = false))

        compose.onNodeWithText("Morning feel: Slow").performClick()

        compose.onNodeWithText("Morning feel: Slow · Change").assertDoesNotExist()
        compose.onNodeWithText("How do you feel this morning?").assertDoesNotExist()
        assertThat(feels).isEmpty()
    }

    @Test
    fun `an unanswered morning past its wake time shows no feel card`() {
        show(MorningReviewUiState(today, today, summary(), feelEditable = false))

        compose.onNodeWithText("How do you feel this morning?").assertDoesNotExist()
    }

    // ---------------------------------------------------------------- Odd night and Tomorrow list

    @Test
    fun `an odd night asks what was different, with marks and skip`() {
        show(MorningReviewUiState(today, today, summary(), oddNight = PlanSamples.oddNight(today)))

        compose.onNodeWithText("Anything different last night?").assertIsDisplayed()
        compose.onNodeWithText("You slept less than usual. You fell asleep later than usual.").assertExists()
        compose.onNodeWithText("Stress").performScrollTo().performClick()
        compose.onNodeWithText("Skip").performScrollTo().performClick()

        assertThat(toggled).containsExactly(Mark.STRESS)
        assertThat(skipped).isEqualTo(1)
    }

    @Test
    fun `last night's tomorrow list comes back`() {
        val list = TomorrowList(today.minusDays(1), "- call Sam\nbook the dentist", SampleData.now, offset)
        show(MorningReviewUiState(today, today, summary(), tomorrowList = list))

        compose.onNodeWithText("Your list from last night").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("call Sam").assertExists()
        compose.onNodeWithText("book the dentist").assertExists()
    }

    // ---------------------------------------------------------------- Result: Wake landing and marks

    @Test
    fun `wake landing explains the ring in the watch's words`() {
        show(MorningReviewUiState(today, today, result(), landing = PlanSamples.landing(LandingStage.LIGHT, today), night = lastNight))

        compose.onNodeWithText("Wake landing").performScrollTo()
        compose.onNodeWithText("Rang 6:27 AM · 18 min early · stopped 6:29 AM").assertExists()
        compose.onNodeWithText("It rang at a restless moment.").assertExists()
        compose.onNodeWithText("Watch estimate: light sleep when it rang.").assertExists()
        compose.onNodeWithText("Your watch scored 10% of the window as deep sleep.").assertExists()
        compose.onNodeWithText("Reset to default").assertDoesNotExist()
    }

    @Test
    fun `a ring at the wake time without stages says so`() {
        val landing = PlanSamples.landing(LandingStage.UNSCORED, today).copy(
            event = PlanSamples.alarmEvent(RingReason.DEADLINE, today),
            minutesEarly = 0,
            hasStages = false,
            windowDeepShare = null,
        )
        show(MorningReviewUiState(today, today, result(), landing = landing, night = lastNight))

        compose.onNodeWithText("Rang 6:45 AM at your wake time · stopped 6:47 AM").performScrollTo()
        compose.onNodeWithText("No restless moment came, so it rang at your wake time.").assertExists()
        compose.onNodeWithText("No stage data from your watch for this night.").assertExists()
        compose.onNodeWithText("Watch estimate: light sleep when it rang.").assertDoesNotExist()
    }

    @Test
    fun `this week's tuning is explained and can be reset`() {
        show(
            MorningReviewUiState(
                today, today, result(),
                landing = PlanSamples.landing(LandingStage.DEEP, today),
                tuningNote = PlanSamples.tuning(),
                night = lastNight,
            ),
        )

        compose.onNodeWithText("Watch estimate: deep sleep when it rang. That’s more likely after short nights.").performScrollTo()
        compose.onNodeWithText(
            "Sleep Saver now waits for a clearer restless moment, after 3 rings this week during watch-scored deep sleep.",
        ).assertExists()
        compose.onNodeWithText("Reset to default").performScrollTo().performClick()

        assertThat(resets).isEqualTo(1)
    }

    @Test
    fun `last night's marks can be ticked on the result`() {
        show(MorningReviewUiState(today, today, result(), marks = setOf(Mark.ALCOHOL)))

        compose.onNodeWithText("Last night’s marks").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Late meal").performScrollTo().performClick()

        assertThat(toggled).containsExactly(Mark.LATE_MEAL)
    }

    @Test
    fun `without a planned alarm there is no landing card`() {
        show(MorningReviewUiState(today, today, result()))

        compose.onNodeWithText("Wake landing").assertDoesNotExist()
    }
}
