package com.sleepsaver.feature.tonight.tonight

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.domain.alarm.EndedAlarms
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.TomorrowList
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.PlanPause
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.PlanSamples.NightVariant
import com.sleepsaver.core.ui.platform.AlarmBlocker
import com.sleepsaver.core.ui.testing.ScreenshotTest
import com.sleepsaver.feature.tonight.Clock24
import com.sleepsaver.feature.tonight.LargeFont
import com.sleepsaver.feature.tonight.SheetOver
import com.sleepsaver.feature.tonight.TonightSamples
import com.sleepsaver.feature.tonight.TonightSamples.at
import com.sleepsaver.feature.tonight.TonightSamples.withAlarm
import com.sleepsaver.feature.tonight.oneoff.OneOffSheetActions
import com.sleepsaver.feature.tonight.oneoff.OneOffSheetContent
import com.sleepsaver.feature.tonight.oneoff.OneOffSheetState
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

/** The Tonight tab in each phase and card state (SPEC_V2 §5.2). Tue Oct 6, alarm Mon–Fri 6:45, bedtime 23:00. */
class TonightScreenshotTest : ScreenshotTest() {

    private val s = TonightSamples
    private val wednesday = s.today.plusDays(1)

    @Composable
    private fun Tonight(state: TonightUiState, scrollTo: Int = 0, blockers: List<AlarmBlocker> = emptyList()) {
        TonightContent(state, TonightActions(), scrollState = remember { ScrollState(scrollTo) }, alarmBlockers = blockers)
    }

    private val day get() = s.ready(s.inputs(at(14, 0)))
    private val evening get() = s.ready(s.inputs(at(21, 45), marks = mapOf(wednesday to setOf(Mark.LATE_MEAL))))
    private val night get() = s.ready(s.inputs(at(23, 30), night = PlanSamples.nightState(NightVariant.ANCHORED)))

    @Test
    fun day() = captureScreen("tonight_day") { Tonight(day) }

    @Test
    fun dayScrolled() = captureScreen("tonight_day_scrolled") { Tonight(day, scrollTo = Int.MAX_VALUE) }

    @Test
    fun evening() = captureScreen("tonight_evening") { Tonight(evening) }

    @Test
    fun eveningMarks() = captureScreen("tonight_evening_marks") { Tonight(evening, scrollTo = 1500) }

    @Test
    fun eveningScrolled() = captureScreen("tonight_evening_scrolled") { Tonight(evening, scrollTo = Int.MAX_VALUE) }

    @Test
    fun night() = captureScreen("tonight_night") { Tonight(night) }

    @Test
    fun morning() {
        val list = TomorrowList(s.today.minusDays(1), "Call the dentist\nPack the gym bag\nBook flights for Lisbon", at(22, 0, s.today.minusDays(1)), s.zone.rules.getOffset(at(22, 0)))
        captureScreen("tonight_morning") { Tonight(s.ready(s.inputs(at(7, 30), lastNightList = list))) }
    }

    @Test
    fun noNights() {
        val session = PlanSamples.nightStepsSession(nightOf = s.today, done = 0, fromSettingsOnly = true)
        val inputs = s.inputs(at(21, 45), insights = InsightsState.NoNights, session = session)
            .copy(debtInputs = s.debtInputs(nights = emptyList()))
        captureScreen("tonight_no_nights") { Tonight(s.ready(inputs)) }
    }

    @Test
    fun alarmOff() = captureScreen("tonight_alarm_off") { Tonight(s.ready(s.inputs(at(14, 0), week = s.allOff))) }

    @Test
    fun dayOff() = captureScreen("tonight_day_off") {
        Tonight(s.ready(s.inputs(at(14, 0), oneOffs = PlanSamples.oneOffs(dayOff = wednesday))))
    }

    @Test
    fun paused() = captureScreen("tonight_paused") {
        Tonight(s.ready(s.inputs(at(14, 0), oneOffs = PlanSamples.oneOffs(pause = PlanPause(s.today, s.today.plusDays(5))))))
    }

    /** Wednesday's 6:45 already rang (the clock went back to Tuesday evening): the card says so, with the next one. */
    @Test
    fun alarmDone() {
        val now = at(20, 55)
        val rang = PlanSamples.alarmEvent(RingReason.DEADLINE, wakeDate = wednesday)
        val ended = EndedAlarms(ids = setOf(checkNotNull(rang.occurrenceId)), dates = setOf(wednesday))
        captureScreen("tonight_alarm_done") { Tonight(s.ready(s.inputs(now, ended = ended, tomorrowEvent = rang))) }
    }

    /** "Set one for Wed" from that card: the sheet starts at the 6:45 that already rang, and won't set it again. */
    @Test
    fun alarmDoneSheet() {
        val now = at(20, 55)
        val rang = PlanSamples.alarmEvent(RingReason.DEADLINE, wakeDate = wednesday)
        val ended = EndedAlarms(ids = setOf(checkNotNull(rang.occurrenceId)), dates = setOf(wednesday))
        val sheet = OneOffSheetState(
            date = wednesday,
            dates = emptyList(),
            today = s.today,
            alarmOn = true,
            time = LocalTime.of(6, 45),
            mode = AlarmMode.SURFACING,
            onceEnded = true,
            pauseDates = (1L..60L).map { s.today.plusDays(it) },
            pauseUntil = s.today.plusDays(1),
        )
        captureScreen("oneoff_sheet_already_ended") {
            SheetOver(screen = { Tonight(s.ready(s.inputs(now, ended = ended, tomorrowEvent = rang))) }) {
                OneOffSheetContent(sheet, OneOffSheetActions())
            }
        }
    }

    /** A Tuesday-only alarm the morning it rang: the next one, a week away, carries its date. */
    @Test
    fun nextAlarmNextWeek() = captureScreen("tonight_next_alarm_next_week") {
        val tuesdays = s.allOff.withAlarm(DayOfWeek.TUESDAY) { it.copy(mode = AlarmMode.SURFACING) }
        Tonight(s.ready(s.inputs(at(7, 30), week = tuesdays)))
    }

    @Test
    fun blocked() = captureScreen("tonight_blocked") { Tonight(day, blockers = listOf(AlarmBlocker.EXACT_ALARMS_OFF)) }

    @Test
    fun demo() = captureScreen("tonight_demo") {
        Tonight(s.ready(s.inputs(at(14, 0), settings = AppSettings(sleep = s.sleepSettings, demoMode = true))))
    }

    @Test
    fun catnapRunning() = captureScreen("tonight_catnap_running") {
        Tonight(s.ready(s.inputs(at(14, 10), night = PlanSamples.nightState(NightVariant.CATNAP_PENDING))))
    }

    @Test
    fun hushPlaying() {
        val playing = PlanSamples.nightState(NightVariant.HUSH_PLAYING).copy(night = null)
        captureScreen("tonight_hush_playing") { Tonight(s.ready(s.inputs(at(21, 45), night = playing)), scrollTo = Int.MAX_VALUE) }
    }

    @Test
    fun anchorSheet() = captureScreen("tonight_anchor_sheet") {
        SheetOver(screen = { Tonight(evening) }) {
            AnchorSheetContent(alarm = evening.alarmCard.nightAlarm(), wholeNightLog = false, onWholeNightLog = {}, onDrop = {})
        }
    }

    @Test
    fun anchorSheetNoAlarm() {
        val off = WeekPlan(s.week.days.map { it.copy(morning = it.morning.copy(alarm = it.morning.alarm.copy(mode = AlarmMode.OFF))) })
        val noAlarm = s.ready(s.inputs(at(21, 45), week = off))
        captureScreen("tonight_anchor_sheet_no_alarm") {
            SheetOver(screen = { Tonight(noAlarm) }) {
                AnchorSheetContent(alarm = noAlarm.alarmCard.nightAlarm(), wholeNightLog = false, onWholeNightLog = {}, onDrop = {})
            }
        }
    }

    @Test
    fun evening24h() = captureScreen("tonight_evening_24h") { Clock24 { Tonight(evening) } }

    @Test
    fun eveningLargeFont() = captureScreen("tonight_evening_large_font") { LargeFont { Tonight(evening) } }

    @Test
    fun nightLargeFont() = captureScreen("tonight_night_large_font") { LargeFont { Tonight(night) } }

    @Test
    fun loading() = captureScreen("tonight_loading") { Tonight(TonightUiState.Loading) }

    @Test
    fun unavailable() = captureScreen("tonight_unavailable") { Tonight(TonightUiState.Unavailable) }
}
