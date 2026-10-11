package com.sleepsaver.feature.tonight.ringing

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.alarm.SnoozeOffer
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.PlanSamples.NightVariant
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.feature.tonight.TonightSamples
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** The ringing screen's lines by ring and reason, and its buttons. */
@RunWith(AndroidJUnit4::class)
class RingingContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val wakeDate: LocalDate = PlanSamples.today.plusDays(1)
    private val calls = mutableListOf<String>()
    private val actions = RingingActions(
        onSnooze = { calls += "snooze" },
        onStop = { calls += "stop" },
        onFeel = { calls += "feel:$it" },
        onDone = { calls += "done" },
        onOpenReview = { calls += "review:$it" },
    )

    private fun show(state: RingingUiState) {
        compose.setContent { SleepSaverTheme { RingingContent(state, actions) } }
    }

    @Test
    fun `a restless moment, with snooze and stop`() {
        show(TonightSamples.sounding(NightVariant.RINGING_PLANNED))

        // The big clock is a plain time.
        compose.onNodeWithText("6:27 AM").assertExists()
        compose.onNodeWithText("Good morning").assertExists()
        compose.onNodeWithText("A restless moment · 18 min before 6:45\u00A0AM").assertExists()
        compose.onNodeWithText("Last ring").assertDoesNotExist()
        compose.onNodeWithText("Snooze until 6:36\u00A0AM").performClick()
        compose.onNodeWithText("Stop").performClick()

        assertThat(calls).containsExactly("snooze", "stop").inOrder()
    }

    @Test
    fun `the last snooze says what comes next`() {
        show(TonightSamples.sounding(NightVariant.RINGING_PLANNED) { it.copy(snooze = it.snooze.copy(lastOne = true)) })
        compose.onNodeWithText("After this snooze, the next ring is the last ring.").assertExists()
    }

    @Test
    fun `no snooze left on a planned ring is the last ring`() {
        show(TonightSamples.sounding(NightVariant.RINGING_PLANNED) { it.copy(reason = RingReason.DEADLINE, snooze = SnoozeOffer(null, false)) })

        compose.onNodeWithText("Your wake time").assertExists()
        compose.onNodeWithText("Last ring").assertExists()
        compose.onNodeWithText("Snooze", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the other reasons`() {
        show(TonightSamples.sounding(NightVariant.RINGING_PLANNED) { it.copy(reason = RingReason.CATCH_UP) })
        compose.onNodeWithText("Your 6:45\u00A0AM alarm, a little late").assertExists()
    }

    @Test
    fun `a catnap`() {
        show(TonightSamples.sounding(NightVariant.RINGING_CATNAP))

        compose.onNodeWithText("Catnap over").assertExists()
        compose.onNodeWithText("Time to get up").assertExists()
        compose.onNodeWithText("Snooze until 2:30\u00A0PM").assertExists()
        compose.onNodeWithText("After this snooze, the next ring is the last ring.").assertExists()
    }

    @Test
    fun `a test ring`() {
        show(TonightSamples.sounding(NightVariant.RINGING_TEST))

        compose.onNodeWithText("Test ring").assertExists()
        compose.onNodeWithText("This is how your alarm looks and sounds.").assertExists()
        compose.onNodeWithText("Last ring").assertDoesNotExist()
    }

    @Test
    fun `after a planned alarm come feel, done and the review`() {
        show(RingingUiState.AfterStop(RingKind.PLANNED, wakeDate, feel = null, unlocked = true))

        compose.onNodeWithText("How do you feel?").assertExists()
        compose.onNodeWithText(
            "Get bright light soon: open the curtains, step outside, or switch on bright lights if it's still dark.",
        ).assertExists()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("Open morning review").performClick()
        compose.onNodeWithText("Done").performClick()

        assertThat(calls).containsExactly("feel:${MorningFeel.CLEAR}", "review:$wakeDate", "done").inOrder()
    }

    @Test
    fun `before the first unlock there is no review button`() {
        show(RingingUiState.AfterStop(RingKind.PLANNED, wakeDate, feel = MorningFeel.SLOW, unlocked = false))

        compose.onNodeWithText("How do you feel?").assertExists()
        compose.onNodeWithText("Open morning review").assertDoesNotExist()
    }

    @Test
    fun `after a test ring`() {
        show(RingingUiState.AfterStop(RingKind.TEST, null, null, unlocked = true))

        compose.onNodeWithText("Your real alarms haven't changed.").assertExists()
        compose.onNodeWithText("How do you feel?").assertDoesNotExist()
        compose.onNodeWithText("Done").performClick()
        assertThat(calls).containsExactly("done")
    }

    @Test
    fun `after a catnap`() {
        show(RingingUiState.AfterStop(RingKind.NAP, null, null, unlocked = true))
        compose.onNodeWithText("How do you feel?").assertDoesNotExist()
        compose.onNodeWithText("Open morning review").assertDoesNotExist()
        compose.onNodeWithText("Done").assertExists()
    }
}
