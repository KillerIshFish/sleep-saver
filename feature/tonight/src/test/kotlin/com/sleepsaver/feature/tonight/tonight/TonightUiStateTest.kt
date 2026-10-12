package com.sleepsaver.feature.tonight.tonight

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.hooks.NightState
import com.sleepsaver.core.data.hooks.SensingMode
import com.sleepsaver.core.data.hooks.SensingState
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.domain.alarm.AlarmSource
import com.sleepsaver.core.domain.alarm.EndedAlarms
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.TomorrowList
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.OverrideKind
import com.sleepsaver.core.domain.schedule.PlanPause
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.PlanSamples.NightVariant
import com.sleepsaver.feature.tonight.TonightPhase
import com.sleepsaver.feature.tonight.TonightSamples
import com.sleepsaver.feature.tonight.TonightSamples.at
import com.sleepsaver.feature.tonight.TonightSamples.withAlarm
import com.sleepsaver.feature.tonight.night.MovementLog
import com.sleepsaver.feature.tonight.night.NightAlarm
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime

/** The Tonight tab's state: cards by phase, the alarm card's states, the night model (Tue Oct 6, alarm Mon–Fri 6:45). */
class TonightUiStateTest {

    private val s = TonightSamples
    private val wednesday = s.today.plusDays(1)
    private val thursday = s.today.plusDays(2)

    private val allOff = WeekPlan(s.week.days.map { it.copy(morning = it.morning.copy(alarm = it.morning.alarm.copy(mode = AlarmMode.OFF))) })

    // ---------------------------------------------------------------- Alarm card

    @Test
    fun `a surfacing alarm shows its window and wake time`() {
        val state = s.ready(s.inputs(at(14, 0)))

        val card = state.alarmCard as AlarmCardModel.On
        assertThat(card.wakeDate).isEqualTo(wednesday)
        assertThat(card.time).isEqualTo(LocalTime.of(6, 45))
        assertThat(card.mode).isEqualTo(AlarmMode.SURFACING)
        assertThat(card.windowStart).isEqualTo(LocalTime.of(6, 15))
        assertThat(card.thisMorning).isFalse()
        assertThat(card.justOnce).isFalse()
        assertThat(state.armed).isTrue()
    }

    @Test
    fun `once its window opens, the card stays on this morning's alarm until it has rung`() {
        val now = at(6, 20, date = wednesday)

        val windowOpen = s.ready(s.inputs(now))

        // The night has moved on to tonight ...
        assertThat(windowOpen.nightOf).isEqualTo(wednesday)
        assertThat(windowOpen.wakeDate).isEqualTo(thursday)
        // ... but the 6:45 about to ring is what the card and the week strip show (and Day off acts on).
        val card = windowOpen.alarmCard as AlarmCardModel.On
        assertThat(card.wakeDate).isEqualTo(wednesday)
        assertThat(card.thisMorning).isTrue()
        assertThat(windowOpen.week.first().date).isEqualTo(wednesday)

        // Rung and stopped: it leaves the planned alarms, and the card moves on to tomorrow.
        val rung = s.ready(s.inputs(now, alarms = s.alarms(now).filterNot { it.occurrence.date == wednesday }))
        assertThat(rung.alarmCard.wakeDate).isEqualTo(thursday)
        assertThat((rung.alarmCard as AlarmCardModel.On).thisMorning).isFalse()
        assertThat(rung.week.first().date).isEqualTo(thursday)
    }

    @Test
    fun `a debt-aware night opens the window earlier`() {
        val now = at(14, 0)
        val alarms = listOf(PlanSamples.plannedAlarm(date = wednesday, debtAware = true))

        val card = s.ready(s.inputs(now, alarms = alarms)).alarmCard as AlarmCardModel.On

        assertThat(card.debtAware).isTrue()
        assertThat(card.windowStart).isEqualTo(LocalTime.of(6, 5))
    }

    @Test
    fun `just this once makes a fixed alarm on a free day`() {
        val saturday = s.today.plusDays(4)
        val friday = s.today.plusDays(3)
        val now = at(14, 0, date = friday)
        val oneOffs = PlanSamples.oneOffs(justOnce = DateOverride(saturday, OverrideKind.JUST_ONCE, LocalTime.of(9, 0), AlarmMode.FIXED))

        val card = s.ready(s.inputs(now, oneOffs = oneOffs)).alarmCard as AlarmCardModel.On

        assertThat(card.wakeDate).isEqualTo(saturday)
        assertThat(card.mode).isEqualTo(AlarmMode.FIXED)
        assertThat(card.windowStart).isEqualTo(LocalTime.of(9, 0))
        assertThat(card.justOnce).isTrue()
    }

    @Test
    fun `a day off names the next alarm`() {
        val state = s.ready(s.inputs(at(14, 0), oneOffs = PlanSamples.oneOffs(dayOff = wednesday)))

        val card = state.alarmCard as AlarmCardModel.DayOff
        assertThat(card.wakeDate).isEqualTo(wednesday)
        assertThat(card.next).isEqualTo(NextAlarm(thursday, LocalTime.of(6, 45)))
        // The next alarm still rings, so a blocker is still worth a warning.
        assertThat(state.armed).isTrue()
    }

    @Test
    fun `paused alarms say until when`() {
        val pause = PlanPause(s.today, s.today.plusDays(5))
        val now = at(14, 0)
        val oneOffs = PlanSamples.oneOffs(pause = pause)

        val card = s.ready(s.inputs(now, oneOffs = oneOffs)).alarmCard as AlarmCardModel.Paused

        assertThat(card.until).isEqualTo(pause.until)
        assertThat(card.next?.date).isEqualTo(s.today.plusDays(6))
    }

    @Test
    fun `a morning whose alarm already rang says so and names the next one, instead of claiming an alarm`() {
        // Wednesday-only 6:45. It rang on Wednesday and was stopped; then the clock went back to Tuesday evening.
        val wednesdays = s.allOff.withAlarm(DayOfWeek.WEDNESDAY) { it.copy(mode = AlarmMode.SURFACING) }
        val now = at(20, 55)
        val rang = PlanSamples.alarmEvent(RingReason.DEADLINE, wakeDate = wednesday)
        val ended = EndedAlarms(ids = setOf(rang.occurrenceId!!), dates = setOf(wednesday))
        // The planner skips the ended Wednesday and plans the one after (today + 8).
        val alarms = s.alarms(now, wednesdays, ended = ended)
        assertThat(alarms.map { it.occurrence.date }).containsExactly(wednesday.plusDays(7))

        val state = s.ready(s.inputs(now, week = wednesdays, ended = ended, tomorrowEvent = rang))

        val card = state.alarmCard as AlarmCardModel.Done
        assertThat(card.wakeDate).isEqualTo(wednesday)
        assertThat(card.rang).isTrue()
        assertThat(card.next).isEqualTo(NextAlarm(wednesday.plusDays(7), LocalTime.of(6, 45)))
        // Nothing rings Wednesday: not in this week's list, no "Until my window", and the night says no alarm.
        assertThat(state.week.first()).isEqualTo(WeekStripDay(wednesday, LocalTime.of(6, 45), AlarmMode.OFF, alarmOn = false))
        assertThat(state.hush.untilWindowAvailable).isFalse()
        assertThat(state.armed).isTrue()
        assertThat(s.ready(s.inputs(at(23, 30), week = wednesdays, ended = ended, tomorrowEvent = rang)).night?.alarm)
            .isEqualTo(NightAlarm.None)
    }

    @Test
    fun `an alarm that ended without ringing reads as ended`() {
        val missed = PlanSamples.alarmEvent(RingReason.NEVER_RANG, wakeDate = wednesday)
        val ended = EndedAlarms(ids = setOf(missed.occurrenceId!!), dates = setOf(wednesday))

        val card = s.ready(s.inputs(at(20, 55), ended = ended, tomorrowEvent = missed)).alarmCard

        assertThat(card).isEqualTo(AlarmCardModel.Done(wednesday, rang = false, next = NextAlarm(thursday, LocalTime.of(6, 45))))
    }

    @Test
    fun `on a morning whose alarm already rang, just this once at another time rings and at that time doesn't`() {
        val now = at(20, 55)
        val rang = PlanSamples.alarmEvent(RingReason.DEADLINE, wakeDate = wednesday)
        val ended = EndedAlarms(ids = setOf(rang.occurrenceId!!), dates = setOf(wednesday))
        fun card(time: LocalTime, planned: Boolean): AlarmCardModel {
            val oneOffs = PlanSamples.oneOffs(justOnce = DateOverride(wednesday, OverrideKind.JUST_ONCE, time, AlarmMode.FIXED))
            // planned = false: right after the write, before the planned alarms are recomputed.
            val alarms = s.alarms(now, oneOffs = if (planned) oneOffs else PlanSamples.oneOffs(), ended = ended)
            return s.ready(s.inputs(now, oneOffs = oneOffs, ended = ended, alarms = alarms, tomorrowEvent = rang)).alarmCard
        }

        // 6:55 is a new alarm: On at once, before it is planned too, never "already rang" in between.
        for (planned in listOf(false, true)) {
            val on = card(LocalTime.of(6, 55), planned) as AlarmCardModel.On
            assertThat(on.time).isEqualTo(LocalTime.of(6, 55))
            assertThat(on.justOnce).isTrue()
        }
        // 6:45 is the alarm that already rang, which the planner leaves out (the one-off sheet doesn't set it).
        assertThat(card(LocalTime.of(6, 45), planned = true)).isInstanceOf(AlarmCardModel.Done::class.java)
    }

    @Test
    fun `until the planned alarms catch up with a change, the card reads the plan, not the old time`() {
        val now = at(14, 0)
        val stale = s.alarms(now)
        // Wednesday just moved to 7:00; the planned alarms still say 6:45 for a moment.
        val plan = s.week.day(DayOfWeek.WEDNESDAY)
        val moved = s.week.with(plan.copy(morning = plan.morning.copy(wakeTime = LocalTime.of(7, 0))))

        val card = s.ready(s.inputs(now, week = moved, alarms = stale)).alarmCard as AlarmCardModel.On

        assertThat(card.time).isEqualTo(LocalTime.of(7, 0))
        assertThat(card.windowStart).isEqualTo(LocalTime.of(6, 30))

        // "Just this once" at 7:15 from the card: the time and its label at once, then the planned alarm's window.
        val once = PlanSamples.oneOffs(justOnce = DateOverride(wednesday, OverrideKind.JUST_ONCE, LocalTime.of(7, 15), AlarmMode.SURFACING))
        val gap = s.ready(s.inputs(now, oneOffs = once, alarms = stale)).alarmCard as AlarmCardModel.On
        assertThat(gap.time).isEqualTo(LocalTime.of(7, 15))
        assertThat(gap.justOnce).isTrue()
        val planned = listOf(PlanSamples.plannedAlarm(date = wednesday, time = LocalTime.of(7, 15), debtAware = true)).map {
            it.copy(occurrence = it.occurrence.copy(source = AlarmSource.JUST_ONCE))
        }
        val caughtUp = s.ready(s.inputs(now, oneOffs = once, alarms = planned)).alarmCard as AlarmCardModel.On
        assertThat(caughtUp.time).isEqualTo(LocalTime.of(7, 15))
        assertThat(caughtUp.debtAware).isTrue()
        assertThat(caughtUp.justOnce).isTrue()
    }

    @Test
    fun `right after an alarm is turned back on, the card reads that morning's alarm until it is planned`() {
        val now = at(14, 0)
        // "Turn back on" cleared Wednesday's Day off; the planned alarms haven't caught up (no Wednesday, no event).
        val stale = s.alarms(now, oneOffs = PlanSamples.oneOffs(dayOff = wednesday))

        val card = s.ready(s.inputs(now, alarms = stale)).alarmCard as AlarmCardModel.On

        assertThat(card.wakeDate).isEqualTo(wednesday)
        assertThat(card.time).isEqualTo(LocalTime.of(6, 45))
        assertThat(card.windowStart).isEqualTo(LocalTime.of(6, 15))
    }

    @Test
    fun `no alarm at all`() {
        val state = s.ready(s.inputs(at(14, 0), week = allOff))

        val card = state.alarmCard as AlarmCardModel.None
        assertThat(card.next).isNull()
        assertThat(state.armed).isFalse()
        assertThat(state.week.map { it.alarmOn }).doesNotContain(true)
    }

    // ---------------------------------------------------------------- Sections by phase

    @Test
    fun `the debt preview shows only in the evening`() {
        assertThat(s.ready(s.inputs(at(14, 0))).debtPreview).isNull()

        val evening = s.ready(s.inputs(at(21, 45)))
        assertThat(evening.phase).isEqualTo(TonightPhase.EVENING)
        val preview = checkNotNull(evening.debtPreview)
        assertThat(preview.wakeDate).isEqualTo(wednesday)
        assertThat(preview.enoughHistory).isTrue()
    }

    @Test
    fun `the catnap card shows in the day from 9 to 6, or while one runs`() {
        assertThat(s.ready(s.inputs(at(8, 59))).catnap).isNull()
        val day = s.ready(s.inputs(at(14, 0)))
        assertThat(day.phase).isEqualTo(TonightPhase.DAY)
        assertThat(day.catnap?.running).isNull()
        assertThat(day.catnap?.length).isEqualTo(Duration.ofMinutes(20))
        assertThat(s.ready(s.inputs(at(18, 0))).catnap).isNull()

        val running = PlanSamples.nightState(NightVariant.CATNAP_PENDING)
        assertThat(s.ready(s.inputs(at(18, 10), night = running)).catnap?.running).isEqualTo(running.catnap)
    }

    @Test
    fun `hush can wait for the window only when an alarm is set`() {
        assertThat(s.ready(s.inputs(at(21, 45))).hush.untilWindowAvailable).isTrue()
        assertThat(s.ready(s.inputs(at(21, 45), week = allOff)).hush.untilWindowAvailable).isFalse()
    }

    @Test
    fun `hush shows what is playing`() {
        val playing = PlanSamples.nightState(NightVariant.HUSH_PLAYING).copy(night = null)

        val hush = s.ready(s.inputs(at(21, 45), night = playing)).hush

        assertThat(hush.playing).isTrue()
        assertThat(hush.color).isEqualTo(playing.hush?.color)
        assertThat(hush.timer).isEqualTo(playing.hush?.timer)
    }

    @Test
    fun `the morning brings back last night's list`() {
        val list = TomorrowList(s.today.minusDays(1), "Call the dentist\nPack the gym bag", at(22, 0, date = s.today.minusDays(1)), s.zone.rules.getOffset(at(22, 0)))

        val morning = s.ready(s.inputs(at(7, 30), lastNightList = list))

        assertThat(morning.phase).isEqualTo(TonightPhase.MORNING)
        assertThat(morning.tomorrowList?.items).containsExactly("Call the dentist", "Pack the gym bag").inOrder()
        assertThat(s.ready(s.inputs(at(7, 30), lastNightList = list.copy(text = " "))).tomorrowList).isNull()
    }

    @Test
    fun `tonight's marks are the next wake date's`() {
        val marks = mapOf(wednesday to setOf(Mark.ALCOHOL), s.today to setOf(Mark.STRESS))

        assertThat(s.ready(s.inputs(at(21, 45), marks = marks)).tonightMarks).containsExactly(Mark.ALCOHOL)
    }

    @Test
    fun `this week lists seven wake dates from the next one`() {
        val week = s.ready(s.inputs(at(14, 0))).week

        assertThat(week.map { it.date }).containsExactlyElementsIn((1L..7L).map { s.today.plusDays(it) }).inOrder()
        assertThat(week.first().time).isEqualTo(LocalTime.of(6, 45))
        assertThat(week.first { it.date.dayOfWeek.value == 6 }.alarmOn).isFalse()
    }

    @Test
    fun `without nights the times come from settings`() {
        val session = PlanSamples.nightStepsSession(nightOf = s.today, fromSettingsOnly = true)

        val state = s.ready(s.inputs(at(21, 45), insights = InsightsState.NoNights, session = session))

        assertThat(state.noNights).isTrue()
        assertThat(state.phase).isEqualTo(TonightPhase.EVENING)
        assertThat(state.alarmCard).isInstanceOf(AlarmCardModel.On::class.java)
    }

    @Test
    fun `insights still loading is loading`() {
        assertThat(tonightUiState(s.inputs(at(14, 0), insights = InsightsState.Loading))).isEqualTo(TonightUiState.Loading)
    }

    @Test
    fun `demo mode is flagged`() {
        val demo = s.ready(s.inputs(at(14, 0), settings = AppSettings(sleep = s.sleepSettings, demoMode = true)))
        assertThat(demo.demo).isTrue()
    }

    // ---------------------------------------------------------------- Night

    @Test
    fun `the night shows the latest alarm time, never a countdown`() {
        val night = s.ready(s.inputs(at(23, 30)))

        assertThat(night.phase).isEqualTo(TonightPhase.NIGHT)
        assertThat(night.night?.alarm).isEqualTo(NightAlarm.NeverLater(LocalTime.of(6, 45)))
        assertThat(night.night?.log).isNull()
    }

    @Test
    fun `a fixed alarm or none reads that way at night`() {
        val fixed = WeekPlan(s.week.days.map { it.copy(morning = it.morning.copy(alarm = it.morning.alarm.copy(mode = if (it.morning.alarm.mode == AlarmMode.OFF) AlarmMode.OFF else AlarmMode.FIXED))) })

        assertThat(s.ready(s.inputs(at(23, 30), week = fixed)).night?.alarm).isEqualTo(NightAlarm.At(LocalTime.of(6, 45)))
        assertThat(s.ready(s.inputs(at(23, 30), week = allOff)).night?.alarm).isEqualTo(NightAlarm.None)
    }

    @Test
    fun `the movement log shows whether it runs`() {
        val logging = PlanSamples.nightState(NightVariant.ANCHORED_WITH_LOG)
        assertThat(s.ready(s.inputs(at(23, 30), night = logging)).night?.log).isEqualTo(MovementLog.LOGGING)

        val stopped = logging.copy(sensing = SensingState(SensingMode.WHOLE_NIGHT, since = at(22, 55), running = false))
        assertThat(s.ready(s.inputs(at(23, 30), night = stopped)).night?.log).isEqualTo(MovementLog.STOPPED)

        val lost = logging.copy(sensing = null)
        assertThat(s.ready(s.inputs(at(23, 30), night = lost)).night?.log).isEqualTo(MovementLog.STOPPED)
    }

    @Test
    fun `an anchor keeps the night model after the window opens`() {
        val anchored = PlanSamples.nightState(NightVariant.ANCHORED)
        val now = at(6, 20, date = wednesday)

        val state = s.ready(s.inputs(now, night = anchored))

        assertThat(state.phase).isNotEqualTo(TonightPhase.NIGHT)
        assertThat(state.night).isNotNull()
        assertThat(s.ready(s.inputs(now, night = NightState())).night).isNull()
    }
}
