package com.sleepsaver.feature.tonight.check

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.alarm.TuningReason
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.ui.platform.AlarmBlocker
import com.sleepsaver.core.ui.platform.AlarmCheck
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.feature.tonight.TonightSamples
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Alarm check's lines and taps. */
@RunWith(AndroidJUnit4::class)
class AlarmCheckContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = AlarmCheckActions(
        onBack = { calls += "back" },
        onFix = { calls += "fix:$it" },
        onTestRing = { calls += "testRing" },
        onResetTuning = { calls += "reset" },
    )

    private fun checks(vararg failing: AlarmBlocker) = AlarmBlocker.entries.map { AlarmCheck(it, passed = it !in failing) }

    private fun show(state: AlarmCheckUiState, checks: List<AlarmCheck> = checks()) {
        compose.setContent { SleepSaverTheme { AlarmCheckContent(state, checks, actions) } }
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    private fun ready(tuning: TuningReason? = null, tested: Boolean = false) = AlarmCheckUiState.Ready(
        lastTest = if (tested) PlanSamples.alarmEvent(RingReason.TEST) else null,
        tuning = tuning?.let { PlanSamples.tuning().copy(reason = it) },
        today = TonightSamples.today,
        zone = TonightSamples.zone,
    )

    @Test
    fun `every check passes`() {
        show(ready())

        listOf(
            "Things that can stop an alarm from ringing on this phone.",
            "Notifications allowed",
            "Alarm notifications on",
            "Alarms & reminders allowed",
            "Full-screen alarms allowed",
            "Battery use not restricted",
            "Alarm volume on",
            "Do Not Disturb lets alarms through",
            "Battery optimisation off",
            "Sound plays on this phone",
        ).forEach { line ->
            scrollTo(line)
            compose.onNodeWithText(line).assertExists()
        }
        scrollTo("Place the phone face down on the mattress, away from your pillow. Never put it under pillows or covers.")
        scrollTo("No test ring yet.")
        scrollTo("Standard")
        compose.onNodeWithText("Reset to default").assertDoesNotExist()
    }

    @Test
    fun `a failing check shows its fix`() {
        show(ready(), checks(AlarmBlocker.EXACT_ALARMS_OFF, AlarmBlocker.BLUETOOTH_OUTPUT))

        compose.onNodeWithText("Alarms & reminders is off").assertExists()
        compose.onNodeWithText("Without it, your alarm may ring up to 10 minutes early.").assertExists()
        compose.onNodeWithText("Allow").performClick()
        scrollTo("Sound goes to a Bluetooth device")
        compose.onNodeWithText("Your alarm may play there instead of on the phone.").assertExists()

        assertThat(calls).containsExactly("fix:${AlarmBlocker.EXACT_ALARMS_OFF}")
    }

    @Test
    fun `the last test ring and the test button`() {
        show(ready(tested = true))

        scrollTo("Last test: rang at 6:45 AM on Oct 6")
        compose.onNodeWithText("Test ring in 1 minute").performClick()

        assertThat(calls).containsExactly("testRing")
    }

    @Test
    fun `a tuned trigger can be reset`() {
        show(ready(tuning = TuningReason.RAISED_AFTER_DEEP_RINGS))

        scrollTo("Waits for a clearer restless moment (since Oct 6)")
        scrollTo("Reset to default")
        compose.onNodeWithText("Reset to default").performClick()

        assertThat(calls).containsExactly("reset")
    }

    @Test
    fun `a lowered trigger`() {
        show(ready(tuning = TuningReason.LOWERED_AFTER_LATE_RINGS))
        scrollTo("Reacts to smaller movements (since Oct 6)")
    }

    @Test
    fun `a reset trigger reads as standard`() {
        show(ready(tuning = TuningReason.RESET))
        scrollTo("Standard")
        compose.onNodeWithText("Reset to default").assertDoesNotExist()
    }
}
