package com.sleepsaver.core.ui.components

import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
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

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [SCREENSHOT_SDK], qualifiers = PHONE_PORTRAIT_DARK)
class ControlsEdgeCaseTest {

    @get:Rule
    val rule = createComposeRule()

    private val zone = ZoneOffset.ofHours(2)
    private fun local(h: Int, m: Int, s: Int = 0) = LocalDateTime.of(2026, 10, 5, h, m, s).atOffset(zone).toInstant()

    private fun setScrubber(
        initial: Instant,
        min: Instant? = null,
        max: Instant? = null,
        onChange: (Instant) -> Unit = {},
    ): () -> Instant {
        var value by mutableStateOf(initial)
        rule.setContent {
            SleepSaverTheme {
                TimeScrubber(
                    value = value,
                    onValueChange = { value = it; onChange(it) },
                    zone = zone,
                    min = min,
                    max = max,
                    modifier = Modifier.width(400.dp).testTag("scrubber"),
                )
            }
        }
        return { value }
    }

    @Test
    fun `max before min does not crash a drag`() {
        val value = setScrubber(initial = local(7, 10), min = local(7, 30), max = local(7, 0))
        rule.onNodeWithTag("scrubber").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertThat(value()).isEqualTo(local(7, 30))
    }

    @Test
    fun `bounds inside one minute do not crash the accessibility actions`() {
        val value = setScrubber(initial = local(7, 10, 30), min = local(7, 10, 20), max = local(7, 10, 40))
        rule.onNodeWithTag("scrubber").performCustomAccessibilityActionWithLabel("5 minutes later")
        rule.waitForIdle()
        assertThat(Duration.between(local(7, 10), value()).abs()).isAtMost(Duration.ofMinutes(1))
    }

    @Test
    fun `accessibility actions step by 5 minutes`() {
        val value = setScrubber(initial = local(7, 15))
        rule.onNodeWithTag("scrubber").performCustomAccessibilityActionWithLabel("5 minutes later")
        rule.waitForIdle()
        assertThat(value()).isEqualTo(local(7, 20))
        rule.onNodeWithTag("scrubber").performCustomAccessibilityActionWithLabel("5 minutes earlier")
        rule.onNodeWithTag("scrubber").performCustomAccessibilityActionWithLabel("5 minutes earlier")
        rule.waitForIdle()
        assertThat(value()).isEqualTo(local(7, 10))
    }

    @Test
    fun `one-minute steps read in the singular`() {
        rule.setContent {
            SleepSaverTheme {
                TimeScrubber(local(7, 15), {}, zone, Modifier.testTag("scrubber"), stepMinutes = 1)
            }
        }
        rule.onNodeWithTag("scrubber").performCustomAccessibilityActionWithLabel("1 minute later")
    }

    @Test
    fun `minute stepper with max below min does not crash`() {
        var minutes by mutableIntStateOf(30)
        rule.setContent {
            SleepSaverTheme { MinuteStepper(minutes, onMinutesChange = { minutes = it }, min = 15, max = 0) }
        }
        rule.onNodeWithContentDescription("Decrease").performClick()
        rule.waitForIdle()
        assertThat(minutes).isEqualTo(15)
        rule.onNodeWithContentDescription("Increase").assertIsNotEnabled()
    }

    @Test
    fun `a fling glides as far as a platform fling would`() {
        val start = local(12, 0)
        val value = setScrubber(initial = start)
        val density = rule.density
        val minutePx = with(density) { 6.dp.toPx() }
        val dragPx = with(density) { 90.dp.toPx() }
        val velocity = 2500f

        rule.onNodeWithTag("scrubber").performTouchInput {
            val from = Offset(right - 40f, centerY)
            swipeWithVelocity(start = from, end = from - Offset(dragPx, 0f), endVelocity = velocity, durationMillis = 250)
        }
        rule.waitForIdle()

        // Dragging left moves forward in time: the drag itself, then the fling's coast (decay in pixels).
        val decay = splineBasedDecay<Float>(density)
        fun expectedMinutes(v: Float) = (dragPx + decay.calculateTargetValue(0f, v)) / minutePx
        val moved = Duration.between(start, value()).toMinutes()
        assertThat(moved.toFloat()).isAtLeast(expectedMinutes(velocity * 0.7f) - 5)
        assertThat(moved.toFloat()).isAtMost(expectedMinutes(velocity * 1.3f) + 5)
    }
}
