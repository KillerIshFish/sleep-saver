package com.sleepsaver.feature.tonight.oneoff

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.ui.platform.AlarmBlocker
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.feature.tonight.TonightSamples
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime

/** Taps on the one-off sheet's content. */
@RunWith(AndroidJUnit4::class)
class OneOffSheetContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = TonightSamples.today
    private val wednesday = today.plusDays(1)
    private val calls = mutableListOf<String>()

    private val actions = OneOffSheetActions(
        onDate = { calls += "date:$it" },
        onDayOff = { calls += "dayOff" },
        onTime = { calls += "time:$it" },
        onMode = { calls += "mode:$it" },
        onJustOnce = { calls += "justOnce" },
        onPauseUntil = { calls += "pauseUntil:$it" },
        onPause = { calls += "pause" },
        onFixBlocker = { calls += "fix:$it" },
    )

    private fun state(alarmOn: Boolean = true, dates: List<LocalDate> = emptyList()) = OneOffSheetState(
        date = wednesday,
        dates = dates,
        today = today,
        alarmOn = alarmOn,
        time = LocalTime.of(6, 45),
        mode = AlarmMode.SURFACING,
        pauseDates = (1L..60L).map { today.plusDays(it) },
        pauseUntil = today.plusDays(1),
    )

    private fun show(state: OneOffSheetState) {
        compose.setContent { SleepSaverTheme { OneOffSheetContent(state, actions) } }
    }

    @Test
    fun `day off, just this once and pause`() {
        show(state())

        compose.onNodeWithText("Change for one day").assertExists()
        compose.onNodeWithText("No alarm on Wed. The week plan stays as it is.").assertExists()
        compose.onNodeWithText("Take Wed off").performClick()
        compose.onNodeWithText("No alarm, just the time").performScrollTo().performClick()
        compose.onNodeWithText("Set for Wed").performScrollTo().performClick()
        // Bring the pause row on screen first: performScrollTo only scrolls the chip's own row.
        compose.onNodeWithText("Pause until Wed, Oct 7").performScrollTo()
        compose.onNodeWithText("Thu, Oct 8").performScrollTo().performClick()
        compose.onNodeWithText("Pause until Wed, Oct 7").performScrollTo().performClick()

        assertThat(calls).containsExactly("dayOff", "mode:${AlarmMode.OFF}", "justOnce", "pauseUntil:${today.plusDays(2)}", "pause").inOrder()
    }

    @Test
    fun `a blocked pause says why next to its button`() {
        show(state().copy(pauseBlocker = AlarmBlocker.RING_CHANNEL_OFF))

        compose.onNodeWithText("Alarm notifications are turned off").performScrollTo()
        compose.onNodeWithText("Open settings").performScrollTo().performClick()

        assertThat(calls).containsExactly("fix:${AlarmBlocker.RING_CHANNEL_OFF}")
    }

    @Test
    fun `just this once at the time whose alarm already ended there says so and can't be set`() {
        show(state().copy(onceEnded = true))

        compose.onNodeWithText("The 6:45 AM alarm on Wed already ended. Pick another time.").performScrollTo().assertExists()
        compose.onNodeWithText("Set for Wed").performScrollTo().assertIsNotEnabled().performClick()
        // Day off and the pause still work.
        compose.onNodeWithText("Take Wed off").performClick()

        assertThat(calls).containsExactly("dayOff")
    }

    @Test
    fun `a day without an alarm has no day off`() {
        show(state(alarmOn = false))
        compose.onNodeWithText("Take Wed off").assertDoesNotExist()
        compose.onNodeWithText("Set for Wed").assertExists()
    }

    @Test
    fun `from the week plan the day can be picked`() {
        // In the evening the dates start tomorrow: today's wake time has passed.
        show(state(dates = oneOffDates(wednesday)))

        compose.onNodeWithText("Tue, Oct 6").assertDoesNotExist()
        // The last date, a week after the first; the pause row lists it too, below the date row.
        compose.onAllNodesWithText("Wed, Oct 14")[0].performScrollTo().performClick()

        assertThat(calls).containsExactly("date:${today.plusDays(8)}")
    }

    @Test
    fun `a date a week away is named by its date`() {
        show(state(dates = oneOffDates(wednesday)).copy(date = today.plusDays(7)))

        compose.onNodeWithText("No alarm on Tue, Oct\u00A013. The week plan stays as it is.").assertExists()
        compose.onNodeWithText("Take Tue, Oct\u00A013 off").assertExists()
        compose.onNodeWithText("Set for Tue, Oct\u00A013").performScrollTo().assertExists()
    }
}
