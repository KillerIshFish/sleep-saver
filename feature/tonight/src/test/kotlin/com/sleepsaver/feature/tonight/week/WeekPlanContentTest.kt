package com.sleepsaver.feature.tonight.week

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.OverrideKind
import com.sleepsaver.core.domain.schedule.PlanPause
import com.sleepsaver.core.domain.schedule.WeekPreset
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.ui.platform.AlarmBlocker
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.feature.tonight.TonightSamples
import com.sleepsaver.feature.tonight.TonightSamples.withAlarm
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime

/** Taps on the week plan's content and the lines it shows. */
@RunWith(AndroidJUnit4::class)
class WeekPlanContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val s = TonightSamples
    private val calls = mutableListOf<String>()

    private val actions = WeekPlanActions(
        onBack = { calls += "back" },
        onOpenDay = { calls += "day:$it" },
        onAlarmSwitch = { day, on -> calls += "switch:$day:$on" },
        onChangeOneDay = { calls += "changeOneDay" },
        onUndoOverride = { calls += "undo:$it" },
        onResumeAlarms = { calls += "resume" },
        onDismissNotice = { calls += "dismiss" },
        onApplyPreset = { preset, wake -> calls += "preset:$preset:$wake" },
        onOpenAlarmCheck = { calls += "alarmCheck" },
        onTestRing = { calls += "testRing" },
        onFixBlocker = { calls += "fix:$it" },
    )

    private fun show(state: WeekPlanUiState, armBlocker: AlarmBlocker? = null) {
        compose.setContent { SleepSaverTheme { WeekPlanContent(state, actions, armBlocker = armBlocker) } }
    }

    private fun tap(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).performClick()
    }

    @Test
    fun `rows read as morning and evening`() {
        show(s.weekPlanState())

        compose.onNodeWithText("Next: Wed 6:45\u00A0AM · window\u00A030\u00A0min").assertExists()
        compose.onNodeWithText("Tue · 6:45\u00A0AM · Surfacing 30 min").assertExists()
        compose.onNodeWithText("Sat · usual 8:10\u00A0AM · no alarm").assertExists()
        compose.onAllNodesWithText("Evening · Night steps from 9:30\u00A0PM · 3 steps").assertCountEquals(7)
    }

    @Test
    fun `a set bedtime and steps off show in the evening line`() {
        val week = s.week.with(
            s.week.day(DayOfWeek.FRIDAY).let { it.copy(evening = it.evening.copy(bedtime = LocalTime.of(22, 45), nightStepsOn = false)) },
        )
        show(s.weekPlanState(week = week))
        compose.onNodeWithText("Evening · Night steps off · bedtime 10:45\u00A0PM").assertExists()
    }

    @Test
    fun `a next alarm a week away carries its date`() {
        // Tuesday-only 6:45 seen on Tuesday afternoon: the next one is next Tuesday, not today.
        val tuesdays = s.allOff.withAlarm(DayOfWeek.TUESDAY) { it.copy(mode = AlarmMode.SURFACING) }
        show(s.weekPlanState(week = tuesdays))

        compose.onNodeWithText("Next: Tue, Oct\u00A013, 6:45\u00A0AM · window\u00A030\u00A0min").assertExists()
    }

    @Test
    fun `a widened window keeps the day's setting and says why it is wider`() {
        // The day's row says Surfacing 30 min; tonight's planned window is 40 (debt-aware).
        val next = PlanSamples.plannedAlarm(date = s.today.plusDays(1), debtAware = true)
        show(s.weekPlanState(next = next))

        compose.onNodeWithText("Next: Wed 6:45\u00A0AM · window\u00A030\u00A0min (40 while you're short on sleep)").assertExists()
        compose.onNodeWithText("Wed · 6:45\u00A0AM · Surfacing 30 min").assertExists()
    }

    @Test
    fun `at night a widened window reads the same, without Next`() {
        val next = PlanSamples.plannedAlarm(date = s.today.plusDays(1), debtAware = true)
        show(s.weekPlanState(night = true, next = next))

        compose.onNodeWithText("Wed 6:45\u00A0AM · window\u00A030\u00A0min (40 while you're short on sleep)").assertExists()
    }

    @Test
    fun `at night the subtitle drops Next`() {
        show(s.weekPlanState(night = true))
        compose.onNodeWithText("Wed 6:45\u00A0AM · window\u00A030\u00A0min").assertExists()
    }

    @Test
    fun `a fixed alarm has no window in the subtitle`() {
        val fixed = s.week.days.fold(s.week) { week, plan ->
            if (plan.morning.alarm.mode == AlarmMode.OFF) week else week.with(plan.copy(morning = plan.morning.copy(alarm = plan.morning.alarm.copy(mode = AlarmMode.FIXED))))
        }
        show(s.weekPlanState(week = fixed))
        compose.onNodeWithText("Next: Wed 6:45\u00A0AM").assertExists()
        compose.onNodeWithText("Tue · 6:45\u00A0AM · At wake time").assertExists()
    }

    @Test
    fun `switches and rows`() {
        show(s.weekPlanState())

        compose.onNodeWithContentDescription("Alarm on Saturday").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Alarm on Monday").performScrollTo().performClick()
        compose.onNodeWithText("Tue · 6:45\u00A0AM · Surfacing 30 min").performClick()
        compose.onNodeWithContentDescription("Back").performScrollTo().performClick()

        assertThat(calls).containsExactly(
            "switch:${DayOfWeek.SATURDAY}:true",
            "switch:${DayOfWeek.MONDAY}:false",
            "day:${DayOfWeek.TUESDAY}",
            "back",
        ).inOrder()
    }

    @Test
    fun `one-offs are listed with undo`() {
        val wednesday = s.today.plusDays(1)
        val thursday = s.today.plusDays(2)
        val oneOffs = PlanSamples.oneOffs(
            dayOff = wednesday,
            justOnce = DateOverride(thursday, OverrideKind.JUST_ONCE, LocalTime.of(5, 30), AlarmMode.FIXED),
            pause = PlanPause(s.today.plusDays(5), s.today.plusDays(6)),
        )
        show(s.weekPlanState(oneOffs = oneOffs))

        compose.onNodeWithText("Wed, Oct 7: day off").assertExists()
        compose.onNodeWithText("Thu, Oct 8: 5:30\u00A0AM just this once").assertExists()
        compose.onNodeWithText("Paused until Mon, Oct 12").assertExists()
        // Six days away: with its date.
        compose.onNodeWithText("Alarms paused until Mon, Oct\u00A012").assertExists()
        compose.onAllNodesWithText("Undo")[0].performScrollTo().performClick()
        compose.onAllNodesWithText("Undo")[1].performScrollTo().performClick()
        compose.onAllNodesWithText("Undo")[2].performScrollTo().performClick()
        compose.onNodeWithText("Resume").performScrollTo().performClick()
        tap("Change one day")

        assertThat(calls).containsExactly("undo:$wednesday", "undo:$thursday", "resume", "resume", "changeOneDay").inOrder()
    }

    @Test
    fun `the plan check and its notice`() {
        show(s.weekPlanState(planCheck = PlanSamples.planCheck(warn = true)))

        compose.onNodeWithText("Planned keel 66 · Choppy · last\u00A014\u00A0days:\u00A084").assertExists()
        // Labelled as the plan's, so it never reads like Progress's social jetlag from the recorded nights.
        compose.onNodeWithText("Planned social jetlag").assertExists()
        compose.onNodeWithText(
            "In this plan, free days wake 1h 45m later than alarm days. Keeping it within an hour steadies your " +
                "Keel; a short early-afternoon catnap is one alternative.",
        ).assertExists()
        tap("Got it")

        assertThat(calls).containsExactly("dismiss")
    }

    @Test
    fun `social jetlag alone has its own line`() {
        val check = PlanSamples.planCheck(warn = true).copy(warnFreeDayShift = false)
        show(s.weekPlanState(planCheck = check, keel = PlanSamples.keelCalibrating()))

        compose.onNodeWithText("Planned keel 66 · Choppy").assertExists()
        compose.onNodeWithText("Planned social jetlag").assertExists()
        compose.onNodeWithText("Your planned sleep on free days sits 1h 35m later than on alarm days.").assertExists()
    }

    @Test
    fun `free days that sleep earlier say earlier`() {
        // Alarm days 9:00, free days 7:00: the planned social jetlag is negative and still warns (|-2h| >= 1h).
        val check = PlanSamples.planCheck(warn = true).copy(
            freeDayWakeShift = Duration.ofHours(-2),
            plannedSocialJetlag = Duration.ofHours(-2),
            warnFreeDayShift = false,
        )
        show(s.weekPlanState(planCheck = check, keel = PlanSamples.keelCalibrating()))

        compose.onNodeWithText("Planned social jetlag").assertExists()
        compose.onNodeWithText("Your planned sleep on free days sits 2h earlier than on alarm days.").assertExists()
        compose.onNodeWithText("Your planned sleep on free days sits 2h later than on alarm days.").assertDoesNotExist()
    }

    @Test
    fun `with no weekly alarm the notice compares weekends with weekdays`() {
        // No alarm days to compare against: the plan check fell back to the weekend against Monday–Friday.
        show(s.weekPlanState(planCheck = PlanSamples.planCheck(warn = true).copy(splitByAlarms = false)))

        compose.onNodeWithText(
            "In this plan, weekends wake 1h 45m later than weekdays. Keeping it within an hour steadies your " +
                "Keel; a short early-afternoon catnap is one alternative.",
        ).assertExists()
        compose.onNodeWithText("alarm days", substring = true).assertDoesNotExist()
    }

    @Test
    fun `with no weekly alarm social jetlag alone compares weekends with weekdays`() {
        val check = PlanSamples.planCheck(warn = true).copy(warnFreeDayShift = false, splitByAlarms = false)
        show(s.weekPlanState(planCheck = check, keel = PlanSamples.keelCalibrating()))

        compose.onNodeWithText("Your planned sleep on weekends sits 1h 35m later than on weekdays.").assertExists()
    }

    @Test
    fun `quick setup applies the picked preset and time`() {
        show(s.weekPlanState())

        tap("Same every day")
        tap("Apply")

        assertThat(calls).containsExactly("preset:${WeekPreset.SAME_EVERY_DAY}:06:45")
    }

    @Test
    fun `alarm check and the test ring`() {
        show(s.weekPlanState(), armBlocker = AlarmBlocker.RING_CHANNEL_OFF)

        compose.onNodeWithText("Alarm notifications are turned off").assertExists()
        tap("Open settings")
        tap("Alarm check")
        tap("Test ring in 1 minute")

        assertThat(calls).containsExactly("fix:${AlarmBlocker.RING_CHANNEL_OFF}", "alarmCheck", "testRing").inOrder()
    }

    @Test
    fun `no alarms at all`() {
        show(s.weekPlanState(week = s.allOff))
        compose.onNodeWithText("No alarms set").assertExists()
    }
}
