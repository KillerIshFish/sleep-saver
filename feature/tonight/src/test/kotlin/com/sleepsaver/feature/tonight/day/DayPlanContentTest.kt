package com.sleepsaver.feature.tonight.day

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.AlarmTone
import com.sleepsaver.core.domain.schedule.DayPart
import com.sleepsaver.core.domain.schedule.SnoozeStyle
import com.sleepsaver.core.domain.schedule.WakeStyle
import com.sleepsaver.core.ui.platform.AlarmBlocker
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.feature.tonight.TonightSamples
import com.sleepsaver.feature.tonight.TonightSamples.withAlarm
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek

/** Taps on the day plan's content: which callback fires with what, and the lines it shows. */
@RunWith(AndroidJUnit4::class)
class DayPlanContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val s = TonightSamples
    private val calls = mutableListOf<String>()

    private val actions = DayPlanActions(
        onBack = { calls += "back" },
        onSelectDay = { calls += "select:$it" },
        onWakeUsual = { calls += "wakeUsual" },
        onWakeSet = { calls += "wakeSet" },
        onWakeTime = { calls += "wakeTime:$it" },
        onAlarmMode = { calls += "mode:$it" },
        onWindow = { calls += "window:${it.toMinutes()}" },
        onStyle = { calls += "style:$it" },
        onTone = { calls += "tone:$it" },
        onPreviewTone = { calls += "preview:$it" },
        onVolume = { calls += "volume" },
        onVolumeDone = { calls += "volumeDone" },
        onSnoozeStyle = { calls += "snooze:$it" },
        onFixedLength = { calls += "fixedLength:${it.toMinutes()}" },
        onFixedCount = { calls += "fixedCount:$it" },
        onOverrun = { calls += "overrun:${it.toMinutes()}" },
        onBedtimeRule = { calls += "bedtimeRule" },
        onBedtimeSet = { calls += "bedtimeSet" },
        onBedtime = { calls += "bedtime:$it" },
        onNightSteps = { calls += "nightSteps:$it" },
        onStepsStart = { calls += "stepsStart:$it" },
        onEditSteps = { calls += "editSteps:$it" },
        onCopy = { days, parts -> calls += "copy:${days.sorted()}:${parts.sorted()}" },
        onFixBlocker = { calls += "fix:$it" },
    )

    private fun show(state: DayPlanUiState, armBlocker: AlarmBlocker? = null) {
        compose.setContent { SleepSaverTheme { DayPlanContent(state, actions, armBlocker = armBlocker) } }
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    private fun tap(text: String) {
        scrollTo(text)
        compose.onNodeWithText(text).performClick()
    }

    @Test
    fun `the morning has its titles, the usual wake and the alarm caption`() {
        show(s.dayPlan())

        compose.onNodeWithText("Tuesday morning").assertExists()
        compose.onNodeWithText("Days with an alarm use a set time, so the alarm never moves on its own.").assertExists()
        compose.onNodeWithText("May ring up to 30 min before 6:45\u00A0AM when you're restless. Always rings by 6:45\u00A0AM.")
            .performScrollTo()
        compose.onNodeWithText("Tuesday evening (before Wednesday)").performScrollTo()
    }

    @Test
    fun `usual wake is offered only without an alarm, with its source`() {
        show(s.dayPlan(day = DayOfWeek.SATURDAY))

        compose.onNodeWithText("8:10\u00A0AM (learned)", substring = true).assertExists()
        tap("Usual wake time")
        // The morning's (the evening's bedtime has one too).
        compose.onAllNodesWithText("Set a time")[0].performClick()
        assertThat(calls).containsExactly("wakeUsual", "wakeSet").inOrder()
    }

    @Test
    fun `usual wake can't be picked while the alarm is on`() {
        show(s.dayPlan())
        compose.onNodeWithText("Usual wake time", substring = true).assertIsNotEnabled()
    }

    @Test
    fun `alarm settings`() {
        show(s.dayPlan())

        tap("At wake time")
        tap("45 min")
        tap("Firm")
        tap("Warm rise")
        compose.onNodeWithContentDescription("Preview Bright bells").performScrollTo().performClick()
        tap("Fixed")

        assertThat(calls).containsExactly(
            "mode:${AlarmMode.FIXED}",
            "window:45",
            "style:${WakeStyle.FIRM}",
            "tone:${AlarmTone.WARM_RISE}",
            "preview:${AlarmTone.BRIGHT_BELLS}",
            "snooze:${SnoozeStyle.FIXED}",
        ).inOrder()
    }

    @Test
    fun `fixed snooze has a length, a count and the overrun`() {
        val week = s.week.withAlarm(DayOfWeek.TUESDAY) { it.copy(snooze = it.snooze.copy(style = SnoozeStyle.FIXED)) }
        show(s.dayPlan(week = week))

        scrollTo("Latest snooze ends 10 min after your wake time")
        compose.onNodeWithText("9 min").assertExists()
        compose.onNodeWithText("3 snoozes").assertExists()
        // Increase buttons: the snooze length, the count, then the overrun.
        val increase = compose.onAllNodesWithContentDescription("Increase")
        increase[0].performScrollTo().performClick()
        increase[1].performScrollTo().performClick()
        increase[2].performScrollTo().performClick()

        assertThat(calls).containsExactly("fixedLength:10", "fixedCount:4", "overrun:15").inOrder()
    }

    @Test
    fun `the evening`() {
        show(s.dayPlan(part = DayPart.EVENING))

        scrollTo("3 steps · same as every evening")
        compose.onNodeWithText("1 h 30 m").assertExists()
        compose.onNodeWithText("before bedtime · From 9:30\u00A0PM").assertExists()
        tap("Settings rule")
        tap("Edit steps")
        // The last stepper on the screen is "Night steps start".
        compose.onAllNodesWithContentDescription("Increase").let { it[it.fetchSemanticsNodes().size - 1] }.performScrollTo().performClick()
        tap("Night steps")

        assertThat(calls).containsExactly("bedtimeRule", "editSteps:${DayOfWeek.TUESDAY}", "stepsStart:105", "nightSteps:false").inOrder()
    }

    @Test
    fun `the day tabs`() {
        show(s.dayPlan())
        compose.onNodeWithContentDescription("Friday").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        assertThat(calls).containsExactly("select:${DayOfWeek.FRIDAY}", "back").inOrder()
    }

    @Test
    fun `copy to other days`() {
        show(s.dayPlan())

        tap("Copy to…")
        compose.onNodeWithText("Copy Tuesday to…").assertExists()
        // The dialog's day chips (the day tabs behind it have the same names).
        compose.onAllNodesWithContentDescription("Saturday").filterToOne(hasAnyAncestor(isDialog())).performClick()
        compose.onAllNodesWithContentDescription("Sunday").filterToOne(hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Evening: bedtime and Night steps").performClick()
        compose.onNodeWithText("Copy").performClick()

        assertThat(calls).containsExactly("copy:[${DayOfWeek.SATURDAY}, ${DayOfWeek.SUNDAY}]:[${DayPart.MORNING}]")
    }

    @Test
    fun `nothing to copy until a day is picked`() {
        show(s.dayPlan())
        tap("Copy to…")
        compose.onNodeWithText("Copy").assertIsNotEnabled()
    }

    @Test
    fun `the arming notice sits under the alarm`() {
        show(s.dayPlan(day = DayOfWeek.SATURDAY), armBlocker = AlarmBlocker.NOTIFICATIONS_OFF)
        tap("Allow")
        assertThat(calls).containsExactly("fix:${AlarmBlocker.NOTIFICATIONS_OFF}")
    }
}
