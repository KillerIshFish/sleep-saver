package com.sleepsaver.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.ui.testing.PHONE_PORTRAIT_DARK
import com.sleepsaver.core.ui.testing.SCREENSHOT_SDK
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Duration

/** Taps and screen-reader semantics of the v2 choice controls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [SCREENSHOT_SDK], qualifiers = PHONE_PORTRAIT_DARK)
class ChoiceControlsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    // ---------------------------------------------------------------- Morning feel

    @Test
    fun `morning feel picks one word as a radio button`() {
        var feel by mutableStateOf<MorningFeel?>(null)
        rule.setContent { SleepSaverTheme { MorningFeelScale(value = feel, onValueChange = { feel = it }) } }

        MorningFeel.entries.forEach { rule.onNodeWithText(it.name.lowercase().replaceFirstChar(Char::titlecase)).assertIsNotSelected() }
        rule.onNodeWithText("Clear").assert(hasRole(Role.RadioButton)).performClick()
        rule.waitForIdle()

        assertThat(feel).isEqualTo(MorningFeel.CLEAR)
        rule.onNodeWithText("Clear").assertIsSelected()
        rule.onNodeWithText("Bright").assertIsNotSelected()

        rule.onNodeWithText("Foggy").performClick()
        rule.waitForIdle()
        assertThat(feel).isEqualTo(MorningFeel.FOGGY)
        rule.onNodeWithText("Clear").assertIsNotSelected()
    }

    @Test
    fun `morning feel words sit in one selectable group`() {
        rule.setContent { SleepSaverTheme { MorningFeelScale(value = MorningFeel.OKAY, onValueChange = {}) } }
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertCountEquals(1)
        rule.onAllNodes(hasRole(Role.RadioButton)).assertCountEquals(MorningFeel.entries.size)
    }

    @Test
    fun `morning feel stacks from a 150 percent font`() {
        var scale by mutableStateOf(1f)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = scale)) {
                SleepSaverTheme { MorningFeelScale(value = null, onValueChange = {}) }
            }
        }
        val foggy = rule.onNodeWithText("Foggy").getUnclippedBoundsInRoot()
        val slow = rule.onNodeWithText("Slow").getUnclippedBoundsInRoot()
        assertThat(slow.top).isEqualTo(foggy.top)
        assertThat(slow.left).isGreaterThan(foggy.left)

        scale = 1.5f
        rule.waitForIdle()
        val foggyStacked = rule.onNodeWithText("Foggy").getUnclippedBoundsInRoot()
        val slowStacked = rule.onNodeWithText("Slow").getUnclippedBoundsInRoot()
        assertThat(slowStacked.top).isGreaterThan(foggyStacked.top)
        assertThat(slowStacked.left).isEqualTo(foggyStacked.left)
    }

    // ---------------------------------------------------------------- Days

    @Test
    fun `day chips are checkboxes named after the whole day`() {
        var days by mutableStateOf(setOf(DayOfWeek.MONDAY))
        rule.setContent {
            SleepSaverTheme { DayChipRow(selected = days, onToggle = { days = if (it in days) days - it else days + it }) }
        }

        rule.onNodeWithContentDescription("Monday").assert(hasRole(Role.Checkbox)).assertIsOn()
        rule.onNodeWithContentDescription("Thursday").assertIsOff().performClick()
        rule.waitForIdle()
        assertThat(days).containsExactly(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)
        rule.onNodeWithContentDescription("Thursday").assertIsOn()

        rule.onNodeWithContentDescription("Monday").performClick()
        rule.waitForIdle()
        assertThat(days).containsExactly(DayOfWeek.THURSDAY)
        // The letters are for the eye only: "T" twice would mean nothing to a screen reader.
        rule.onAllNodesWithText("T").assertCountEquals(0)
    }

    @Test
    fun `disabled day chips ignore taps`() {
        val toggled = mutableListOf<DayOfWeek>()
        rule.setContent { SleepSaverTheme { DayChipRow(selected = emptySet(), onToggle = { toggled += it }, enabled = false) } }
        rule.onNodeWithContentDescription("Friday").assertIsNotEnabled().performClick()
        assertThat(toggled).isEmpty()
    }

    @Test
    fun `day tabs select a single day`() {
        var day by mutableStateOf(DayOfWeek.MONDAY)
        rule.setContent {
            SleepSaverTheme { DayTabs(selected = day, onSelect = { day = it }, marked = setOf(DayOfWeek.FRIDAY)) }
        }
        rule.onNodeWithContentDescription("Monday").assert(hasRole(Role.Tab)).assertIsSelected()
        rule.onNodeWithContentDescription("Friday").assertIsNotSelected().performClick()
        rule.waitForIdle()

        assertThat(day).isEqualTo(DayOfWeek.FRIDAY)
        rule.onNodeWithContentDescription("Friday").assertIsSelected()
        rule.onNodeWithContentDescription("Monday").assertIsNotSelected()
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertCountEquals(1)
    }

    // ---------------------------------------------------------------- Chips

    @Test
    fun `a selectable chip is a checkbox that reports taps`() {
        var selected by mutableStateOf(false)
        rule.setContent { SleepSaverTheme { SelectableChip("45 min", selected = selected, onClick = { selected = !selected }) } }

        rule.onNodeWithText("45 min").assert(hasRole(Role.Checkbox)).assertIsOff().performClick()
        rule.waitForIdle()
        assertThat(selected).isTrue()
        rule.onNodeWithText("45 min").assertIsOn()
    }

    @Test
    fun `a disabled selectable chip ignores taps`() {
        var taps = 0
        rule.setContent { SleepSaverTheme { SelectableChip("Until my window", selected = false, onClick = { taps++ }, enabled = false) } }
        rule.onNodeWithText("Until my window").assertIsNotEnabled().performClick()
        assertThat(taps).isEqualTo(0)
    }

    @Test
    fun `selectable chips keep a 48 dp touch target`() {
        rule.setContent { SleepSaverTheme { SelectableChip("Pink", selected = true, onClick = {}) } }
        rule.onNodeWithText("Pink").assertTouchHeightIsEqualTo(48.dp)
    }

    @Test
    fun `mark chips toggle the tapped mark`() {
        val toggled = mutableListOf<Mark>()
        rule.setContent { SleepSaverTheme { MarkChips(selected = setOf(Mark.STRESS), onToggle = { toggled += it }) } }

        rule.onNodeWithText("Stress").assertIsOn()
        rule.onNodeWithText("Late meal").assertIsOff().performClick()
        assertThat(toggled).containsExactly(Mark.LATE_MEAL)
        // Only the eight stored marks by default.
        rule.onAllNodes(hasRole(Role.Checkbox)).assertCountEquals(Mark.stored.size)
    }

    // ---------------------------------------------------------------- Rows and notices

    @Test
    fun `a toggle row switches from anywhere on the row`() {
        var on by mutableStateOf(false)
        rule.setContent {
            SleepSaverTheme { ToggleRow("Night steps", checked = on, onCheckedChange = { on = it }, supporting = "From 9:45 PM") }
        }
        rule.onNodeWithText("Night steps").assert(hasRole(Role.Switch)).assertIsOff()
        rule.onNodeWithText("From 9:45 PM", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertThat(on).isTrue()
        rule.onNodeWithText("Night steps").assertIsOn()
    }

    @Test
    fun `a disabled toggle row ignores taps`() {
        var calls = 0
        rule.setContent { SleepSaverTheme { ToggleRow("Usual wake time", checked = false, onCheckedChange = { calls++ }, enabled = false) } }
        rule.onNodeWithText("Usual wake time").assertIsNotEnabled().performClick()
        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun `a notice shows its action only with a handler`() {
        var fixes = 0
        rule.setContent {
            SleepSaverTheme {
                Column {
                    NoticeCard("Notifications are off", "Alarms need notifications.", actionLabel = "Allow", onAction = { fixes++ })
                    NoticeCard("Got it", "No handler.", actionLabel = "Hidden")
                }
            }
        }
        rule.onNodeWithText("Allow").assertIsEnabled().performClick()
        assertThat(fixes).isEqualTo(1)
        rule.onAllNodesWithText("Hidden").assertCountEquals(0)
    }

    // ---------------------------------------------------------------- Hush

    private val hush = HushControlsState(
        color = HushColor.PINK,
        timer = Duration.ofMinutes(45),
        volume = 0.25f,
        playing = false,
        untilWindowAvailable = false,
        mediaVolumeHigh = false,
    )

    @Test
    fun `hush controls report every choice`() {
        val events = mutableListOf<Any?>()
        rule.setContent {
            SleepSaverTheme {
                HushControls(
                    hush,
                    HushControlsActions(
                        onColor = { events += it },
                        onTimer = { events += it },
                        onPlay = { events += "play" },
                        onStop = { events += "stop" },
                    ),
                )
            }
        }
        rule.onNodeWithText("Pink").assertIsOn()
        rule.onNodeWithText("Brown").performClick()
        rule.onNodeWithText("30 min").performClick()
        rule.onNodeWithText("Play").performClick()
        // No alarm armed: "Until my window" can't be picked.
        rule.onNodeWithText("Until my window").assertIsNotEnabled().performClick()

        assertThat(events).containsExactly(HushColor.BROWN, Duration.ofMinutes(30), "play").inOrder()
        rule.onNodeWithText("45 min").assertIsOn()
        rule.onNodeWithText("Fades out after 45 min").assertExists()
        rule.onNodeWithText("Softer, like steady rain").assertExists()
    }

    @Test
    fun `hush offers stop while playing and warns about loud media`() {
        var stops = 0
        rule.setContent {
            SleepSaverTheme {
                HushControls(
                    hush.copy(timer = null, playing = true, untilWindowAvailable = true, mediaVolumeHigh = true, night = true),
                    HushControlsActions(onStop = { stops++ }),
                )
            }
        }
        rule.onNodeWithText("Until my window").assertIsEnabled().assertIsOn()
        rule.onNodeWithText("Fades out before your window").assertExists()
        rule.onNodeWithText("Your media volume is high. Turn it down for Hush.").assertExists()
        rule.onAllNodesWithText("Play").assertCountEquals(0)
        rule.onNodeWithText("Stop").performClick()
        assertThat(stops).isEqualTo(1)
    }

    @Test
    fun `hush volume slider is labelled`() {
        rule.setContent { SleepSaverTheme { HushControls(hush, HushControlsActions()) } }
        rule.onNodeWithContentDescription("Volume").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
    }
}
