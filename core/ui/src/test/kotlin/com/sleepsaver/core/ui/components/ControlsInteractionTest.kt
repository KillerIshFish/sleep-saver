package com.sleepsaver.core.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.ui.testing.PHONE_PORTRAIT_DARK
import com.sleepsaver.core.ui.testing.SCREENSHOT_SDK
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [SCREENSHOT_SDK], qualifiers = PHONE_PORTRAIT_DARK)
class ControlsInteractionTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `minute stepper steps and stops at its bounds`() {
        var minutes by mutableIntStateOf(10)
        rule.setContent { SleepSaverTheme { MinuteStepper(minutes, onMinutesChange = { minutes = it }, min = 0, max = 20) } }

        rule.onNodeWithContentDescription("Increase").performClick()
        rule.waitForIdle()
        assertThat(minutes).isEqualTo(15)
        rule.onNodeWithContentDescription("Increase").performClick()
        rule.onNodeWithContentDescription("Increase").assertIsNotEnabled()
        assertThat(minutes).isEqualTo(20)

        repeat(4) { rule.onNodeWithContentDescription("Decrease").performClick() }
        rule.onNodeWithContentDescription("Decrease").assertIsNotEnabled()
        assertThat(minutes).isEqualTo(0)
    }

    @Test
    fun `segmented control selects the tapped option`() {
        var selected by mutableIntStateOf(0)
        rule.setContent {
            SleepSaverTheme { SegmentedControl(listOf("Times", "Debt", "Quality"), selected, onSelectedChange = { selected = it }) }
        }
        rule.onNodeWithText("Quality").performClick()
        rule.waitForIdle()
        assertThat(selected).isEqualTo(2)
        rule.onNodeWithText("Quality").assertIsSelected()
    }

    private val zone = ZoneOffset.ofHours(2)
    private fun local(h: Int, m: Int, day: Int = 5) = LocalDateTime.of(2026, 10, day, h, m).atOffset(zone).toInstant()
    private fun Instant.localMinute() = atOffset(zone).minute

    @Test
    fun `time scrubber snaps to 5 minutes and stops at max`() {
        val start = local(23, 47)
        val max = local(0, 10, day = 6)
        var value by mutableStateOf(start)
        val reported = mutableListOf<Instant>()
        rule.setContent {
            SleepSaverTheme {
                TimeScrubber(
                    value = value,
                    onValueChange = { value = it; reported += it },
                    zone = zone,
                    max = max,
                    modifier = Modifier.testTag("scrubber"),
                )
            }
        }

        // Dragging left moves the ruler forward in time; a long fling runs into max.
        rule.onNodeWithTag("scrubber").performTouchInput { swipeLeft() }
        rule.waitForIdle()

        assertThat(reported).isNotEmpty()
        assertThat(reported.all { it.localMinute() % 5 == 0 }).isTrue()
        assertThat(reported.all { !it.isAfter(max) }).isTrue()
        assertThat(value).isEqualTo(max)
        rule.onNodeWithText("12:10 AM", substring = true).assertExists()
    }

    @Test
    fun `time scrubber drags back in time`() {
        var value by mutableStateOf(local(7, 2))
        rule.setContent {
            SleepSaverTheme {
                TimeScrubber(value, onValueChange = { value = it }, zone = zone, modifier = Modifier.testTag("scrubber"))
            }
        }
        rule.onNodeWithTag("scrubber").performTouchInput { swipeRight(durationMillis = 2_000) }
        rule.waitForIdle()

        assertThat(value).isLessThan(local(7, 0))
        assertThat(value.localMinute() % 5).isEqualTo(0)
    }

    @Test
    fun `debt gauge describes itself`() {
        rule.setContent { SleepSaverTheme { DebtGauge(Duration.ofMinutes(222), DebtStatus.MODERATE) } }
        rule.onNodeWithContentDescription("3.7 hours of sleep debt, Moderate").assertExists()
    }
}
