package com.sleepsaver.feature.tonight.tonight

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.DayPart
import com.sleepsaver.core.domain.schedule.PlanPause
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.PlanSamples.NightVariant
import com.sleepsaver.core.ui.components.HushControlsActions
import com.sleepsaver.core.ui.platform.AlarmBlocker
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.feature.tonight.TonightPhase
import com.sleepsaver.feature.tonight.TonightSamples
import com.sleepsaver.feature.tonight.TonightSamples.at
import com.sleepsaver.feature.tonight.night.NightActions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek

/** Taps on the Tonight tab's content: which callback fires with what. Plus: the night never shows the time. */
@RunWith(AndroidJUnit4::class)
class TonightContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val s = TonightSamples
    private val wednesday = s.today.plusDays(1)
    private val calls = mutableListOf<String>()

    private val actions = TonightActions(
        onOpenSettings = { calls += "settings" },
        onOpenNightSteps = { calls += "nightSteps" },
        onOpenRoutineEditor = { calls += "routineEditor" },
        onOpenDayPlan = { day, part -> calls += "dayPlan:$day:$part" },
        onOpenWeekPlan = { calls += "weekPlan" },
        onOpenAlarmCheck = { calls += "alarmCheck" },
        onOpenMorningReview = { calls += "review:$it" },
        onDayOff = { calls += "dayOff:$it" },
        onOneOff = { calls += "oneOff:$it" },
        onTurnBackOn = { calls += "turnBackOn:$it" },
        onResumeAlarms = { calls += "resume" },
        onDropAnchor = { calls += "dropAnchor" },
        onMark = { mark, on -> calls += "mark:$mark:$on" },
        hush = HushControlsActions(onPlay = { calls += "hushPlay" }, onStop = { calls += "hushStop" }),
        onCatnapLength = { calls += "catnapLength:${it.toMinutes()}" },
        onStartCatnap = { calls += "catnapStart" },
        onCancelCatnap = { calls += "catnapCancel" },
        onFixBlocker = { calls += "fix:$it" },
        night = NightActions(
            onResumeLog = { calls += "resumeLog" },
            onCantSleep = { calls += "cantSleep" },
            onEndNight = { calls += "endNight" },
        ),
    )

    private fun show(
        state: TonightUiState,
        blockers: List<AlarmBlocker> = emptyList(),
        alarmArmBlocker: AlarmBlocker? = null,
        catnapArmBlocker: AlarmBlocker? = null,
    ) {
        compose.setContent {
            SleepSaverTheme {
                TonightContent(
                    state,
                    actions,
                    alarmBlockers = blockers,
                    alarmArmBlocker = alarmArmBlocker,
                    catnapArmBlocker = catnapArmBlocker,
                    scrollState = ScrollState(0),
                )
            }
        }
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    private fun tap(text: String) {
        scrollTo(text)
        compose.onNodeWithText(text).performClick()
    }

    // ---------------------------------------------------------------- Evening

    @Test
    fun `evening actions`() {
        show(s.ready(s.inputs(at(21, 45))))

        compose.onNodeWithContentDescription("Settings").performClick()
        tap("Open Night steps")
        tap("Drop anchor")
        tap("Day off")
        tap("Just this once")
        tap("Alcohol")

        assertThat(calls).containsExactly(
            "settings",
            "nightSteps",
            "dropAnchor",
            "dayOff:$wednesday",
            "oneOff:$wednesday",
            "mark:ALCOHOL:true",
        ).inOrder()
    }

    @Test
    fun `a double tap on the sheet's Drop anchor drops one anchor`() {
        val drops = mutableListOf<Boolean>()
        var open by mutableStateOf(true)
        compose.setContent {
            SleepSaverTheme { if (open) AnchorSheet(onDrop = { drops += it }, onDismiss = { open = false }) }
        }
        compose.waitForIdle()

        // Both taps land while the sheet is still hiding.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Drop anchor").performClick()
        compose.onNodeWithText("Drop anchor").performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertThat(drops).containsExactly(false)
        assertThat(open).isFalse()
    }

    @Test
    fun `a set mark taps off`() {
        show(s.ready(s.inputs(at(21, 45), marks = mapOf(wednesday to setOf(Mark.STRESS)))))
        tap("Stress")
        assertThat(calls).containsExactly("mark:STRESS:false")
    }

    @Test
    fun `steps switched off point to that evening's plan`() {
        val off = PlanSamples.nightStepsSession(nightOf = s.today, enabled = false)
        show(s.ready(s.inputs(at(21, 45), session = off)))

        tap("Change Tuesday evening")

        assertThat(calls).containsExactly("dayPlan:${DayOfWeek.TUESDAY}:${DayPart.EVENING}")
    }

    @Test
    fun `no steps yet points to the routine editor`() {
        val empty = PlanSamples.nightStepsSession(nightOf = s.today, activities = emptyList())
        show(s.ready(s.inputs(at(21, 45), session = empty)))

        tap("Pick steps")

        assertThat(calls).containsExactly("routineEditor")
    }

    // ---------------------------------------------------------------- Alarm card states

    @Test
    fun `a day off turns back on`() {
        show(s.ready(s.inputs(at(14, 0), oneOffs = PlanSamples.oneOffs(dayOff = wednesday))))
        compose.onNodeWithText("No alarm Wed").assertExists()
        tap("Turn back on")
        assertThat(calls).containsExactly("turnBackOn:$wednesday")
    }

    @Test
    fun `a morning whose alarm already rang says so, and a one-off can still be set for it`() {
        // Wednesday's 6:45 rang; the clock then went back to Tuesday evening.
        val now = at(20, 55)
        val rang = PlanSamples.alarmEvent(RingReason.DEADLINE, wakeDate = wednesday)
        val alarms = s.alarms(now, exclude = setOf(checkNotNull(rang.occurrenceId)), endedDates = setOf(wednesday))
        show(s.ready(s.inputs(now, alarms = alarms, tomorrowEvent = rang)))

        compose.onNodeWithText("No alarm Wed").assertExists()
        compose.onNodeWithText("Wed's alarm already rang.").assertExists()
        compose.onNodeWithText("Next alarm: Thu 6:45 AM").assertExists()
        compose.onNodeWithText("May ring from", substring = true).assertDoesNotExist()
        tap("Set one for Wed")

        assertThat(calls).containsExactly("oneOff:$wednesday")
    }

    @Test
    fun `a blocked turn back on says why in the alarm card, and the catnap card stays clear`() {
        show(
            s.ready(s.inputs(at(14, 0), oneOffs = PlanSamples.oneOffs(dayOff = wednesday))),
            alarmArmBlocker = AlarmBlocker.RING_CHANNEL_OFF,
        )

        compose.onNodeWithText("Alarm notifications are turned off").assertExists()
        assertThat(compose.onAllNodesWithText("Alarm notifications are turned off").fetchSemanticsNodes()).hasSize(1)
        tap("Open settings")
        scrollTo("Start catnap")
        compose.onNodeWithText("Notifications are off").assertDoesNotExist()

        assertThat(calls).containsExactly("fix:${AlarmBlocker.RING_CHANNEL_OFF}")
    }

    @Test
    fun `paused alarms resume`() {
        show(s.ready(s.inputs(at(14, 0), oneOffs = PlanSamples.oneOffs(pause = PlanPause(s.today, s.today.plusDays(5))))))
        compose.onNodeWithText("Until Sun, Oct 11").assertExists()
        tap("Resume alarms")
        assertThat(calls).containsExactly("resume")
    }

    @Test
    fun `no alarm offers one for tomorrow`() {
        val off = WeekPlan(s.week.days.map { it.copy(morning = it.morning.copy(alarm = it.morning.alarm.copy(mode = AlarmMode.OFF))) })
        show(s.ready(s.inputs(at(14, 0), week = off)))

        tap("Set one for Wed")
        // The alarm card's link (the This week card has one too).
        compose.onAllNodesWithText("Week plan")[0].performClick()

        assertThat(calls).containsExactly("oneOff:$wednesday", "weekPlan").inOrder()
    }

    @Test
    fun `a blocked alarm warns and opens alarm check`() {
        show(s.ready(s.inputs(at(14, 0))), blockers = listOf(AlarmBlocker.EXACT_ALARMS_OFF))

        compose.onNodeWithText("Your alarm might not ring").assertExists()
        tap("Alarm check")

        assertThat(calls).containsExactly("alarmCheck")
    }

    @Test
    fun `no warning when nothing would ring`() {
        val off = WeekPlan(s.week.days.map { it.copy(morning = it.morning.copy(alarm = it.morning.alarm.copy(mode = AlarmMode.OFF))) })
        show(s.ready(s.inputs(at(14, 0), week = off)), blockers = listOf(AlarmBlocker.EXACT_ALARMS_OFF))
        compose.onNodeWithText("Your alarm might not ring").assertDoesNotExist()
    }

    // ---------------------------------------------------------------- Day

    @Test
    fun `catnap length, start and the arming notice`() {
        show(s.ready(s.inputs(at(14, 0))), catnapArmBlocker = AlarmBlocker.NOTIFICATIONS_OFF)

        scrollTo("Start catnap")
        compose.onNodeWithText("Notifications are off").assertExists()
        tap("Allow")
        // The catnap's chips come before Hush's timer chips.
        compose.onAllNodesWithText("30 min")[0].performClick()
        tap("Start catnap")

        assertThat(calls).containsExactly("fix:${AlarmBlocker.NOTIFICATIONS_OFF}", "catnapLength:30", "catnapStart").inOrder()
    }

    @Test
    fun `a running catnap can be cancelled`() {
        show(s.ready(s.inputs(at(14, 10), night = PlanSamples.nightState(NightVariant.CATNAP_PENDING))))

        compose.onNodeWithText("Catnap · rings at 2:25\u00A0PM").assertExists()
        tap("Cancel")

        assertThat(calls).containsExactly("catnapCancel")
    }

    @Test
    fun `the morning opens the review`() {
        show(s.ready(s.inputs(at(7, 30))))
        tap("Morning review")
        assertThat(calls).containsExactly("review:${s.today}")
    }

    @Test
    fun `this week opens the week plan`() {
        show(s.ready(s.inputs(at(14, 0))))
        scrollTo("This week")
        compose.onNodeWithText("This week").performClick()
        assertThat(calls).containsExactly("weekPlan")
    }

    @Test
    fun `hush plays`() {
        show(s.ready(s.inputs(at(14, 0))))
        tap("Play")
        assertThat(calls).containsExactly("hushPlay")
    }

    // ---------------------------------------------------------------- Night: no clock

    /** Every text and content description on screen. */
    private fun allText(): List<String> = compose
        .onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                listOfNotNull(node.config.getOrNull(SemanticsProperties.StateDescription))
        }

    private val clock = Regex("""\b\d{1,2}:\d{2}\b""")
    private val countdown = Regex("""\bin \d+\s*(h|m|min|hr|hours?|minutes?)\b""")

    /** The only time on screen is the alarm's latest time, in the alarm line; no countdown anywhere. */
    private fun assertNoClock(alarmTime: String?) {
        val texts = allText()
        val timed = texts.filter { clock.containsMatchIn(it) }
        if (alarmTime == null) {
            assertThat(timed).isEmpty()
        } else {
            assertThat(timed).hasSize(1)
            assertThat(timed.single()).startsWith("Alarm set")
            assertThat(clock.findAll(timed.single()).map { it.value }.toList()).containsExactly(alarmTime)
        }
        assertThat(texts.filter { countdown.containsMatchIn(it) }).isEmpty()
    }

    @Test
    fun `the night shows no time but the alarm's latest`() {
        val night = PlanSamples.nightState(NightVariant.HUSH_PLAYING).let {
            it.copy(night = it.night?.copy(wholeNightLog = true))
        }
        val state = s.ready(s.inputs(at(23, 34), night = night))
        assertThat(state.phase).isEqualTo(TonightPhase.NIGHT)

        show(state)

        compose.onNodeWithText("Alarm set · never later than 6:45\u00A0AM").assertExists()
        compose.onNodeWithText("Movement log stopped").assertExists()
        assertNoClock("6:45")
    }

    @Test
    fun `a night without an alarm shows no time at all`() {
        val off = WeekPlan(s.week.days.map { it.copy(morning = it.morning.copy(alarm = it.morning.alarm.copy(mode = AlarmMode.OFF))) })
        show(s.ready(s.inputs(at(0, 47, date = wednesday), week = off, night = PlanSamples.nightState(NightVariant.ANCHORED))))

        compose.onNodeWithText("No alarm in the morning").assertExists()
        assertNoClock(null)
    }

    @Test
    fun `the night without nights yet shows no time either`() {
        val session = PlanSamples.nightStepsSession(nightOf = s.today, fromSettingsOnly = true)
        show(s.ready(s.inputs(at(23, 50), insights = InsightsState.NoNights, session = session)))

        assertNoClock("6:45")
    }

    @Test
    fun `night actions`() {
        show(s.ready(s.inputs(at(23, 30), night = PlanSamples.nightState(NightVariant.ANCHORED_WITH_LOG).let {
            it.copy(sensing = it.sensing?.copy(running = false))
        })))

        tap("Resume")
        tap("Can't sleep?")
        tap("I'm up")
        compose.onNodeWithText("End the night?").assertExists()
        compose.onNodeWithText("Your alarm stays set.").assertExists()
        compose.onNodeWithText("Not yet").performClick()
        compose.onNodeWithText("End the night?").assertDoesNotExist()
        tap("I'm up")
        compose.onNodeWithText("End night").performClick()

        assertThat(calls).containsExactly("resumeLog", "cantSleep", "endNight").inOrder()
    }

    @Test
    fun `hush timer chips stay as lengths at night`() {
        val state = s.ready(s.inputs(at(23, 30), night = PlanSamples.nightState(NightVariant.HUSH_PLAYING)))
        show(state)
        // The chosen length, never when it ends.
        compose.onNodeWithText("Fades out after 45 min").assertExists()
        assertNoClock("6:45")
    }
}
