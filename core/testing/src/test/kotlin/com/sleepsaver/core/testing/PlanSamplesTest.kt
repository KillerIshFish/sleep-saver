package com.sleepsaver.core.testing

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.alarm.LandingStage
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.AnchorSource
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.nightsteps.EveningState
import com.sleepsaver.core.domain.nightsteps.StepLog
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.domain.nightsteps.stepAdherence
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.OverrideKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneOffset

/** The samples build without any stubbed (B1/B2) engine, and the fakes behave like their contracts. */
class PlanSamplesTest {

    @Test
    fun `every sample builds without a stubbed engine`() {
        PlanSamples.weekPlan()
        PlanSamples.oneOffs(dayOff = PlanSamples.today.plusDays(3))
        PlanSamples.plannedAlarm(debtAware = true)
        PlanSamples.nightStepsSession()
        PlanSamples.adherence()
        PlanSamples.suggestions
        PlanSamples.keelReady()
        PlanSamples.keelCalibrating()
        PlanSamples.socialJetlag()
        PlanSamples.planCheck(warn = true)
        PlanSamples.markEffects()
        PlanSamples.oddNight()
        PlanSamples.anchorStats()
        PlanSamples.deepPrior()
        PlanSamples.tuning()
        PlanSamples.debtPreviewInputs()
        RingReason.entries.forEach { PlanSamples.alarmEvent(it) }
        LandingStage.entries.forEach { PlanSamples.landing(it) }
        PlanSamples.NightVariant.entries.forEach { PlanSamples.nightState(it) }
    }

    @Test
    fun `the sample week rings at 6 45 on weekdays only`() {
        val week = PlanSamples.weekPlan()

        assertThat(week.days.filter { it.morning.alarm.mode == AlarmMode.SURFACING }.map { it.day })
            .containsExactly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY).inOrder()
        assertThat(week.day(DayOfWeek.MONDAY).morning.wakeTime).isEqualTo(LocalTime.of(6, 45))
        assertThat(week.day(DayOfWeek.SUNDAY).morning.wakeTime).isNull()
    }

    @Test
    fun `the debt-aware alarm widens its window`() {
        val alarm = PlanSamples.plannedAlarm(debtAware = true)

        assertThat(alarm.window.length).isEqualTo(Duration.ofMinutes(40))
        assertThat(alarm.window.target).isEqualTo(alarm.occurrence.target)
        assertThat(PlanSamples.plannedAlarm(mode = AlarmMode.FIXED).window.length).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `alarm events carry the ring kind their reason implies`() {
        assertThat(PlanSamples.alarmEvent(RingReason.RESTLESS).kind).isEqualTo(RingKind.PLANNED)
        assertThat(PlanSamples.alarmEvent(RingReason.TEST).kind).isEqualTo(RingKind.TEST)
        assertThat(PlanSamples.alarmEvent(RingReason.TEST).occurrenceId).isNull()
        assertThat(PlanSamples.alarmEvent(RingReason.CATNAP_END).kind).isEqualTo(RingKind.NAP)
    }

    @Test
    fun `a ringing catnap offers its one catnap-length snooze as the last`() {
        val ringing = PlanSamples.nightState(PlanSamples.NightVariant.RINGING_CATNAP).ringing!!

        assertThat(Duration.between(ringing.since, checkNotNull(ringing.snooze.until))).isEqualTo(SleepConfig.DEFAULT.catnap.snooze)
        assertThat(ringing.snooze.lastOne).isTrue()
        assertThat(ringing.offersMorningFeel).isFalse()
    }

    @Test
    fun `the night controller fake's state is writable`() {
        val night = FakeNightController()

        night.state.value = PlanSamples.nightState(PlanSamples.NightVariant.RINGING_PLANNED)

        assertThat(night.state.value.ringing?.kind).isEqualTo(RingKind.PLANNED)
        assertThat(night.mutableState.value).isEqualTo(night.state.value)
    }

    @Test
    fun `tonight's sample session runs from the wind-down start with the first step checked`() {
        val session = PlanSamples.nightStepsSession(done = 1)

        assertThat(session.steps.map { it.activity.technique })
            .containsExactly(Technique.SCREENS_DOWN, Technique.TOMORROW_LIST, Technique.SLOW_TIDE).inOrder()
        assertThat(session.doneCount).isEqualTo(1)
        assertThat(session.steps.first().done).isTrue()
        assertThat(Duration.between(session.windowStart, session.bedtime)).isEqualTo(Duration.ofMinutes(90))
    }

    @Test
    fun `the literal adherence follows the adherence rules`() {
        val sample = PlanSamples.adherence()
        val logs = sample.evenings.flatMap { evening ->
            (1..evening.done).map { id ->
                StepLog(evening.nightOf, id.toLong(), null, SampleData.now, ZoneOffset.UTC)
            }
        }
        // Before the first log every evening is NEUTRAL anyway, so planned counts only matter from there.
        val computed = stepAdherence(
            logs = logs,
            plannedCount = { e -> sample.evenings.firstOrNull { it.nightOf == e }?.planned ?: 3 },
            tonight = PlanSamples.today,
            tonightOver = false,
        )

        assertThat(computed.evenings.map { it.state }).isEqualTo(sample.evenings.map { it.state })
        assertThat(computed.doneRecent).isEqualTo(sample.doneRecent)
        assertThat(computed.countedRecent).isEqualTo(sample.countedRecent)
        assertThat(computed.quietStreak).isEqualTo(sample.quietStreak)
        assertThat(sample.evenings.last().state).isEqualTo(EveningState.OPEN)
    }

    @Test
    fun `fakes apply their writes`() = runTest {
        val schedule = FakeScheduleRepository(PlanSamples.weekPlan())
        schedule.updateDay(DayOfWeek.SATURDAY) { it.copy(morning = it.morning.copy(wakeTime = LocalTime.of(8, 0))) }
        val once = DateOverride(PlanSamples.today.plusDays(4), OverrideKind.JUST_ONCE, LocalTime.of(5, 30), AlarmMode.FIXED)
        schedule.setOverride(once)
        assertThat(schedule.updatedDays).containsExactly(DayOfWeek.SATURDAY)
        assertThat(schedule.weekPlan.first().day(DayOfWeek.SATURDAY).morning.wakeTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(schedule.oneOffs.first().overrides).containsExactly(once)

        val journal = FakeJournalRepository()
        journal.setMark(PlanSamples.today, Mark.STRESS, on = true)
        assertThat(journal.marks(PlanSamples.today).first()).containsExactly(Mark.STRESS)

        val night = FakeNightController()
        night.dropAnchor(AnchorSource.TONIGHT, wholeNightLog = false)
        assertThat(night.state.value.night?.anchorId).isEqualTo(1L)
        assertThat(night.nightControllerCalls).containsExactly("dropAnchor(TONIGHT, false)")

        val steps = FakeNightStepsRepository(tonight = PlanSamples.nightStepsSession(done = 0), activities = PlanSamples.activities())
        steps.setDone(PlanSamples.today, activityId = 2, done = true)
        assertThat(steps.tonight.first()?.doneCount).isEqualTo(1)
        assertThat(steps.addTechnique(Technique.SLOW_TIDE, "Slow tide breathing")).isEqualTo(3L)
        assertThat(steps.addTechnique(Technique.BODY_SCAN, "Body scan")).isEqualTo(8L)
    }
}
