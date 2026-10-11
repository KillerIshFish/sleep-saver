package com.sleepsaver.feature.sleep

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.core.ui.format.keepTimesTogether
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

class SleepUiStateTest {

    private fun stateAt(hour: Int, minute: Int): SleepUiState.Ready {
        val now = ZonedDateTime.of(SampleData.today, LocalTime.of(hour, minute), SampleData.zone).toInstant()
        return readyState(
            insights = SampleData.insights(now = now),
            settings = AppSettings(),
            sync = SyncState(),
            habitSettings = SampleData.habits,
            pendingReview = null,
            source = SourceLabel.Garmin,
        )
    }

    @Test
    fun `late at night with nothing left today the strip shows tonight's wind-down and melatonin window`() {
        val state = stateAt(23, 55)

        assertThat(state.phasesAreTonight).isTrue()
        assertThat(state.phases.map { it.phase.type }).containsExactly(PhaseType.WIND_DOWN, PhaseType.MELATONIN_WINDOW).inOrder()
        assertThat(state.phases.none { it.isCurrent }).isTrue()
    }

    @Test
    fun `during the day the current phase leads and is the only highlighted one`() {
        val state = stateAt(10, 20)

        assertThat(state.phasesAreTonight).isFalse()
        assertThat(state.phases.count { it.isCurrent }).isEqualTo(1)
        assertThat(state.phases.first().isCurrent).isTrue()
        assertThat(state.phases.map { it.phase.start }).isInOrder()
    }

    @Test
    fun `times and durations don't split across lines`() {
        assertThat("8h 35m".unbreakable()).isEqualTo("8h 35m")
        assertThat("9:30 AM – 12:30 PM".keepTimesTogether()).isEqualTo("9:30 AM – 12:30 PM")
        assertThat("21:30 – 23:00".keepTimesTogether()).isEqualTo("21:30 – 23:00")
    }

    @Test
    fun `header date reads like a calendar line`() {
        assertThat(formatHeaderDate(SampleData.today)).isEqualTo("Tuesday, Oct 6")
    }

    @Test
    fun `before waking every phase is upcoming`() {
        val state = stateAt(5, 0)

        assertThat(state.phases.none { it.isCurrent }).isTrue()
        assertThat(state.phases.first().phase.type).isEqualTo(PhaseType.GROGGINESS)
    }

    // ---------------------------------------------------------------- Tonight row and night mode

    private val tuesday = SampleData.today
    private val wednesday = tuesday.plusDays(1)

    /** Nights up to this (Tuesday) morning: what the watch has before Wednesday's wake. */
    private val nightsToTuesday = SampleData.nights(today = tuesday)

    private fun at(date: LocalDate, hour: Int, minute: Int): Instant =
        ZonedDateTime.of(date, LocalTime.of(hour, minute), SampleData.zone).toInstant()

    private val bedtime: Instant = SampleData.insights().schedule.targetBedtime

    @Test
    fun `by day the row has tonight's Night steps and tomorrow's alarm`() {
        val state = ready()

        assertThat(state.nightMode).isFalse()
        assertThat(state.tonight).isEqualTo(TonightSummary(stepsFrom = at(tuesday, 21, 30), alarm = LocalTime.of(6, 45), surfacing = true))
    }

    @Test
    fun `steps switched off, none picked or no session leave just the alarm`() {
        val alarmOnly = TonightSummary(stepsFrom = null, alarm = LocalTime.of(6, 45), surfacing = true)

        assertThat(ready(steps = PlanSamples.nightStepsSession(enabled = false)).tonight).isEqualTo(alarmOnly)
        assertThat(ready(steps = PlanSamples.nightStepsSession(activities = emptyList())).tonight).isEqualTo(alarmOnly)
        assertThat(ready(steps = null).tonight).isEqualTo(alarmOnly)
    }

    @Test
    fun `a fixed alarm isn't surfacing, and a later morning's alarm isn't tonight's`() {
        val fixed = PlanSamples.plannedAlarm(mode = AlarmMode.FIXED, time = LocalTime.of(7, 0))
        val friday = PlanSamples.plannedAlarm(date = tuesday.plusDays(3))

        assertThat(ready(alarms = listOf(fixed)).tonight?.surfacing).isFalse()
        assertThat(ready(alarms = listOf(fixed)).tonight?.alarm).isEqualTo(LocalTime.of(7, 0))
        assertThat(ready(alarms = listOf(friday)).tonight?.alarm).isNull()
        assertThat(ready(alarms = emptyList(), steps = null).tonight).isEqualTo(TonightSummary(stepsFrom = null, alarm = null))
    }

    @Test
    fun `night mode starts at bedtime and names the alarm that ends the night`() {
        val before = ready(SampleData.insights(now = bedtime - Duration.ofMinutes(5)))
        val after = ready(SampleData.insights(now = bedtime + Duration.ofMinutes(10)))

        assertThat(before.nightMode).isFalse()
        assertThat(after.nightMode).isTrue()
        assertThat(after.tonight).isEqualTo(TonightSummary(stepsFrom = null, alarm = LocalTime.of(6, 45), surfacing = true))
    }

    @Test
    fun `dropping anchor makes it night before bedtime`() {
        val evening = SampleData.insights(now = at(tuesday, 21, 10))

        assertThat(ready(evening).nightMode).isFalse()
        assertThat(ready(evening, anchoredAt = at(tuesday, 21, 5)).nightMode).isTrue()
    }

    @Test
    fun `in the small hours it is still last night until the alarm's window opens`() {
        fun stateAt(hour: Int, minute: Int, alarms: List<PlannedAlarm> = sampleAlarms) =
            ready(SampleData.insights(now = at(wednesday, hour, minute), nights = nightsToTuesday), alarms = alarms)

        // The schedule has moved on to Wednesday by now, but the night isn't over.
        assertThat(SampleData.insights(now = at(wednesday, 2, 30), nights = nightsToTuesday).schedule.date).isEqualTo(wednesday)
        assertThat(stateAt(2, 30).nightMode).isTrue()
        assertThat(stateAt(2, 30).tonight?.alarm).isEqualTo(LocalTime.of(6, 45))
        assertThat(stateAt(2, 30, alarms = emptyList()).nightMode).isTrue()
        assertThat(stateAt(6, 20).nightMode).isFalse()
    }

    @Test
    fun `last night's anchor doesn't keep the morning dark, and an alarm days away doesn't stretch the night`() {
        val anchor = at(tuesday, 22, 55)
        val friday = listOf(PlanSamples.plannedAlarm(date = tuesday.plusDays(3)))
        val morning = SampleData.insights(now = at(wednesday, 9, 30), nights = SampleData.nights(today = wednesday))

        assertThat(ready(morning, anchoredAt = anchor).nightMode).isFalse()
        assertThat(ready(morning, alarms = friday, anchoredAt = anchor).nightMode).isFalse()
    }
}
