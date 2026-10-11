package com.sleepsaver.core.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.ui.testing.PHONE_PORTRAIT_DARK
import com.sleepsaver.core.ui.testing.SCREENSHOT_SDK
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [SCREENSHOT_SDK], qualifiers = PHONE_PORTRAIT_DARK)
class LocalTimeScrubberTest {

    @get:Rule
    val rule = createComposeRule()

    private fun scrubber(initial: LocalTime, overnight: Boolean, stepMinutes: Int = 5): Pair<() -> LocalTime, List<LocalTime>> {
        var value by mutableStateOf(initial)
        val reported = mutableListOf<LocalTime>()
        rule.setContent {
            SleepSaverTheme {
                LocalTimeScrubber(
                    value = value,
                    onValueChange = { value = it; reported += it },
                    overnight = overnight,
                    stepMinutes = stepMinutes,
                    modifier = Modifier.testTag("scrubber"),
                )
            }
        }
        return { value } to reported
    }

    private fun later() = rule.onNodeWithTag("scrubber").performCustomAccessibilityActionWithLabel("5 minutes later")
    private fun earlier() = rule.onNodeWithTag("scrubber").performCustomAccessibilityActionWithLabel("5 minutes earlier")

    @Test
    fun `overnight, 12 AM comes after 11 PM`() {
        val (value, _) = scrubber(LocalTime.of(23, 55), overnight = true)

        later()
        rule.waitForIdle()
        assertThat(value()).isEqualTo(LocalTime.MIDNIGHT)
        later()
        rule.waitForIdle()
        assertThat(value()).isEqualTo(LocalTime.of(0, 5))
        rule.onNodeWithText("12:05", substring = true).assertExists()

        repeat(2) { earlier(); rule.waitForIdle() }
        assertThat(value()).isEqualTo(LocalTime.of(23, 55))
    }

    @Test
    fun `overnight runs from 4 PM to 8 AM`() {
        val (value, _) = scrubber(LocalTime.of(16, 5), overnight = true)
        repeat(3) { earlier(); rule.waitForIdle() }
        assertThat(value()).isEqualTo(LocalTime.of(16, 0))
    }

    @Test
    fun `overnight stops at 8 AM`() {
        val (value, _) = scrubber(LocalTime.of(7, 55), overnight = true)
        repeat(3) { later(); rule.waitForIdle() }
        assertThat(value()).isEqualTo(LocalTime.of(8, 0))
    }

    @Test
    fun `a day scrubber stays within one day`() {
        val (value, _) = scrubber(LocalTime.of(23, 50), overnight = false)
        repeat(3) { later(); rule.waitForIdle() }
        // The last 5-minute step before midnight: it never wraps to the morning.
        assertThat(value()).isEqualTo(LocalTime.of(23, 55))
    }

    @Test
    fun `a day scrubber stops at midnight going back`() {
        val (value, _) = scrubber(LocalTime.of(0, 5), overnight = false)
        repeat(3) { earlier(); rule.waitForIdle() }
        assertThat(value()).isEqualTo(LocalTime.MIDNIGHT)
    }

    @Test
    fun `dragging an overnight scrubber later carries on past midnight`() {
        val (value, reported) = scrubber(LocalTime.of(23, 30), overnight = true)

        rule.onNodeWithTag("scrubber").performTouchInput { swipeLeft() }
        rule.waitForIdle()

        assertThat(reported).isNotEmpty()
        assertThat(reported.all { it.minute % 5 == 0 }).isTrue()
        // It went on into the small hours instead of turning back to the evening.
        assertThat(value()).isLessThan(LocalTime.NOON)
        assertThat(value()).isAtMost(LocalTime.of(8, 0))
    }
}
