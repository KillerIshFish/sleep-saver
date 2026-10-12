package com.sleepsaver.feature.winddown.steps

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.NightStepsRepository
import com.sleepsaver.core.data.repository.PatternsRepository
import com.sleepsaver.core.domain.journal.AnchorSource
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.nightsteps.StepAdherence
import com.sleepsaver.core.domain.nightsteps.StepSuggestion
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.domain.patterns.EffectMetric
import com.sleepsaver.core.domain.patterns.MarkEffect
import com.sleepsaver.core.domain.patterns.PatternLabel
import com.sleepsaver.core.testing.FakeHushSettingsRepository
import com.sleepsaver.core.testing.FakeJournalRepository
import com.sleepsaver.core.testing.FakeNightController
import com.sleepsaver.core.testing.FakeNightStepsRepository
import com.sleepsaver.core.testing.FakePatternsRepository
import com.sleepsaver.core.testing.FakeScheduleRepository
import com.sleepsaver.core.testing.FakeTimeProvider
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.PlanSamples.NightVariant
import com.sleepsaver.feature.winddown.nightSource
import com.sleepsaver.feature.winddown.richActivities
import com.sleepsaver.feature.winddown.richSession
import com.sleepsaver.feature.winddown.writer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class NightStepsViewModelTest {

    private val today = PlanSamples.today
    private val nightSteps = FakeNightStepsRepository(
        tonight = richSession(),
        activities = richActivities(),
        adherence = PlanSamples.adherence(),
        suggestions = PlanSamples.suggestions,
    )
    private val journal = FakeJournalRepository()
    private val patterns = FakePatternsRepository()

    /** Without nights, a night's end comes from its alarm or, with none, from the planned wake (the timing inputs). */
    private val timingPatterns = FakePatternsRepository(debtPreviewInputs = PlanSamples.debtPreviewInputs())
    private val schedule = FakeScheduleRepository(week = PlanSamples.weekPlan(), upcoming = listOf(PlanSamples.plannedAlarm()))
    private val nightController = FakeNightController()
    private val hushSettings = FakeHushSettingsRepository()

    /** Tue Oct 6, 21:50: Night steps run (window 21:30, bedtime 23:00). */
    private val time = FakeTimeProvider(PlanSamples.at(today, LocalTime.of(21, 50)))

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        patterns: PatternsRepository = this@NightStepsViewModelTest.patterns,
        nightSteps: NightStepsRepository = this@NightStepsViewModelTest.nightSteps,
    ): NightStepsViewModel {
        val viewModel = NightStepsViewModel(
            nightSteps = nightSteps,
            journal = journal,
            patterns = patterns,
            schedule = schedule,
            nightSource = nightSource(nightSteps, time, schedule, nightController, patterns = patterns),
            nightController = nightController,
            hushSettings = hushSettings,
            time = time,
            writer = writer(),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel
    }

    private suspend fun NightStepsViewModel.ready(): NightStepsUiState.Ready =
        uiState.first { it is NightStepsUiState.Ready } as NightStepsUiState.Ready

    private fun moveTo(date: LocalDate, time: LocalTime) {
        this.time.now = PlanSamples.at(date, time)
        this.time.ticks.value = this.time.now
    }

    /** At midnight the repository's "tonight" moves on to the schedule's new evening; Tuesday's stays readable. */
    private fun scheduleRollsOver() {
        nightSteps.sessionsState.value = mapOf(today to nightSteps.tonightState.value!!)
        nightSteps.tonightState.value = PlanSamples.nightStepsSession(nightOf = today.plusDays(1), done = 0, activities = richActivities())
    }

    @Test
    fun `tonight's steps come with tracking, suggestions and Hush`() = runTest {
        val state = viewModel().ready()

        assertThat(state.session).isEqualTo(richSession())
        assertThat(state.night).isFalse()
        assertThat(state.anchored).isFalse()
        assertThat(state.adherence).isEqualTo(PlanSamples.adherence())
        assertThat(state.suggestions).isEqualTo(PlanSamples.suggestions)
        assertThat(state.ownList).isFalse()
        assertThat(state.day).isEqualTo(DayOfWeek.TUESDAY)
        assertThat(state.wakeDate).isEqualTo(today.plusDays(1))
        assertThat(state.compareStatus).isEqualTo(CompareStatus.NOT_ENOUGH)
        // An alarm is armed, so Hush can run until the window.
        assertThat(state.hush.untilWindowAvailable).isTrue()
        assertThat(state.hush.playing).isFalse()
    }

    @Test
    fun `without a session the screen is unavailable`() = runTest {
        nightSteps.tonightState.value = null

        val viewModel = viewModel()

        assertThat(viewModel.uiState.first { it != NightStepsUiState.Loading }).isEqualTo(NightStepsUiState.Unavailable)
    }

    @Test
    fun `without a session the screen doesn't wait for tracking that never comes`() = runTest {
        nightSteps.tonightState.value = null
        // Like the real repository, tracking and suggestions wait for tonight's evening.
        val waiting = object : NightStepsRepository by nightSteps {
            override val adherence: Flow<StepAdherence> = flow { awaitCancellation() }
            override val suggestions: Flow<List<StepSuggestion>> = flow { awaitCancellation() }
        }

        val viewModel = viewModel(nightSteps = waiting)

        assertThat(viewModel.uiState.first { it != NightStepsUiState.Loading }).isEqualTo(NightStepsUiState.Unavailable)
    }

    @Test
    fun `after Drop anchor it is night, and ending the night ends it at once`() = runTest {
        nightController.state.value = PlanSamples.nightState(NightVariant.ANCHORED)
        val viewModel = viewModel()

        val anchored = viewModel.ready()
        assertThat(anchored.night).isTrue()
        assertThat(anchored.anchored).isTrue()
        assertThat(anchored.hush.night).isTrue()

        nightController.endNight()
        assertThat(viewModel.ready().night).isFalse()
    }

    @Test
    fun `I'm up past bedtime ends night mode at once, even between ticks, until the next anchor, as on Tonight`() = runTest {
        moveTo(today, LocalTime.of(23, 30))
        val viewModel = viewModel()
        assertThat(viewModel.ready().night).isTrue() // past bedtime, no anchor

        // "I'm up" on Tonight, 45 s after the last tick.
        nightController.clock = { PlanSamples.at(today, LocalTime.of(23, 30, 45)) }
        nightController.endNight()
        assertThat(viewModel.ready().night).isFalse()

        moveTo(today, LocalTime.of(23, 50))
        assertThat(viewModel.ready().night).isFalse()
        nightController.clock = { time.now }
        nightController.dropAnchor(AnchorSource.TONIGHT, wholeNightLog = false)
        assertThat(viewModel.ready().night).isTrue()
    }

    @Test
    fun `from bedtime until the alarm window it is night`() = runTest {
        val viewModel = viewModel()

        time.advance(Duration.ofMinutes(100)) // 23:30
        assertThat(viewModel.ready().night).isTrue()

        // Tomorrow's alarm: 06:45 with a 30 min window, so night ends at 06:15.
        time.now = PlanSamples.at(today.plusDays(1), LocalTime.of(6, 14))
        time.ticks.value = time.now
        assertThat(viewModel.ready().night).isTrue()
        time.advance(Duration.ofMinutes(1))
        assertThat(viewModel.ready().night).isFalse()
    }

    @Test
    fun `after midnight the checklist, its ticks and marks stay last night's until the alarm window`() = runTest {
        moveTo(today, LocalTime.of(23, 40))
        val viewModel = viewModel()
        assertThat(viewModel.ready().session.nightOf).isEqualTo(today)

        moveTo(today.plusDays(1), LocalTime.of(0, 20))
        scheduleRollsOver()

        val small = viewModel.ready()
        assertThat(small.session).isEqualTo(richSession())
        assertThat(small.wakeDate).isEqualTo(today.plusDays(1))
        assertThat(small.night).isTrue()
        viewModel.setDone(3, true)
        viewModel.toggleMark(Mark.ALCOHOL)
        assertThat(nightSteps.doneCalls).containsExactly(Triple(today, 3L, true))
        assertThat(journal.marksSet).containsExactly(Triple(today.plusDays(1), Mark.ALCOHOL, true))

        // Wednesday's alarm window opens at 06:15: from then on it is Wednesday evening's checklist, in daytime.
        moveTo(today.plusDays(1), LocalTime.of(6, 15))
        val morning = viewModel.ready()
        assertThat(morning.session.nightOf).isEqualTo(today.plusDays(1))
        assertThat(morning.night).isFalse()
    }

    @Test
    fun `with no alarm for the morning after, the night still runs from bedtime to the wake zone`() = runTest {
        // No Wednesday alarm; the next one is Friday's, days away.
        schedule.upcomingState.value = listOf(PlanSamples.plannedAlarm(date = today.plusDays(3)))
        moveTo(today, LocalTime.of(23, 30))
        val viewModel = viewModel(patterns = timingPatterns)

        assertThat(viewModel.ready().night).isTrue()

        // Wednesday's planned wake is 06:45, so its wake zone (and the night) ends at 06:15.
        moveTo(today.plusDays(1), LocalTime.of(6, 14))
        scheduleRollsOver()
        assertThat(viewModel.ready().night).isTrue()
        moveTo(today.plusDays(1), LocalTime.of(6, 15))
        assertThat(viewModel.ready().night).isFalse()
    }

    @Test
    fun `last night's anchor doesn't darken the next morning when no alarm ends the night`() = runTest {
        schedule.upcomingState.value = listOf(PlanSamples.plannedAlarm(date = today.plusDays(3)))
        nightController.state.value = PlanSamples.nightState(NightVariant.ANCHORED)
        val viewModel = viewModel(patterns = timingPatterns)
        assertThat(viewModel.ready().anchored).isTrue()

        // 09:00 Wednesday: the night state lasts until anchor + 14 h, but that night ended at its wake zone.
        moveTo(today.plusDays(1), LocalTime.of(9, 0))
        scheduleRollsOver()
        val state = viewModel.ready()
        assertThat(state.session.nightOf).isEqualTo(today.plusDays(1))
        assertThat(state.night).isFalse()
        assertThat(state.anchored).isFalse()
    }

    @Test
    fun `checking a step saves it for tonight`() = runTest {
        val viewModel = viewModel()
        viewModel.ready()

        viewModel.setDone(2, true)
        viewModel.setDone(1, false)

        assertThat(nightSteps.doneCalls).containsExactly(Triple(today, 2L, true), Triple(today, 1L, false)).inOrder()
        assertThat(viewModel.ready().session.steps.first { it.activity.id == 2L }.done).isTrue()
    }

    @Test
    fun `marks are tonight's, keyed by tomorrow's wake date`() = runTest {
        journal.setMark(today.plusDays(1), Mark.STRESS, true)
        val viewModel = viewModel()
        assertThat(viewModel.ready().marks).containsExactly(Mark.STRESS)

        viewModel.toggleMark(Mark.ALCOHOL)
        viewModel.toggleMark(Mark.STRESS)

        assertThat(journal.marksSet.drop(1))
            .containsExactly(Triple(today.plusDays(1), Mark.ALCOHOL, true), Triple(today.plusDays(1), Mark.STRESS, false))
            .inOrder()
        assertThat(viewModel.ready().marks).containsExactly(Mark.ALCOHOL)
    }

    @Test
    fun `a suggestion is added with the label resolved by the screen`() = runTest {
        val viewModel = viewModel()
        viewModel.ready()

        viewModel.addTechnique(Technique.COOL_DARK_ROOM, "Cool, dark room")

        assertThat(nightSteps.techniquesAdded).containsExactly(Technique.COOL_DARK_ROOM to "Cool, dark room")
    }

    @Test
    fun `on an evening with its own list a suggestion goes on that list, not every evening's`() = runTest {
        val week = PlanSamples.weekPlan()
        val tuesday = week.day(DayOfWeek.TUESDAY)
        schedule.weekState.value = week.with(tuesday.copy(evening = tuesday.evening.copy(routine = listOf(1L))))
        val viewModel = viewModel()
        assertThat(viewModel.ready().ownList).isTrue()

        viewModel.addTechnique(Technique.COOL_DARK_ROOM, "Cool, dark room")

        val added = nightSteps.activitiesState.value.single { it.technique == Technique.COOL_DARK_ROOM }
        assertThat(added.selected).isFalse()
        assertThat(schedule.dayRoutines).containsExactly(DayOfWeek.TUESDAY to listOf(1L, added.id))
    }

    @Test
    fun `Drop anchor saves it from Night steps, then reports back`() = runTest {
        val viewModel = viewModel()
        val events = mutableListOf<NightStepsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.eventFlow.collect { events += it } }

        viewModel.dropAnchor(wholeNightLog = true)
        nightController.dropAnchorResult = null
        viewModel.dropAnchor(wholeNightLog = false)

        assertThat(nightController.nightControllerCalls)
            .containsExactly("dropAnchor(NIGHT_STEPS, true)", "dropAnchor(NIGHT_STEPS, false)")
            .inOrder()
        assertThat(events).containsExactly(NightStepsEvent.ANCHOR_DROPPED, NightStepsEvent.ANCHOR_FAILED).inOrder()
    }

    @Test
    fun `Hush from its step plays and checks the Hush step off`() = runTest {
        val viewModel = viewModel()
        viewModel.ready()

        viewModel.hushActions.onColor(com.sleepsaver.core.domain.alarm.HushColor.BROWN)
        viewModel.hushActions.onPlay()

        assertThat(nightController.nightControllerCalls).containsExactly("startHush(BROWN, PT45M, 0.25)")
        assertThat(nightSteps.doneCalls).containsExactly(Triple(today, 9L, true))
        assertThat(viewModel.ready().hush.playing).isTrue()

        // While it plays, a volume change goes straight to Hush.
        viewModel.hushActions.onVolume(0.5f)
        assertThat(nightController.nightControllerCalls.last()).isEqualTo("setHushVolume(0.5)")
    }

    @Test
    fun `Hush waits for the window only when tonight has an alarm`() = runTest {
        // No Wednesday alarm; the next one is Friday's.
        schedule.upcomingState.value = listOf(PlanSamples.plannedAlarm(date = today.plusDays(3)))
        val viewModel = viewModel()
        assertThat(viewModel.ready().hush.untilWindowAvailable).isFalse()

        viewModel.hushActions.onTimer(null)
        viewModel.hushActions.onPlay()

        assertThat(nightController.nightControllerCalls).containsExactly("startHush(PINK, PT45M, 0.25)")
    }

    @Test
    fun `an evening with its own list is flagged so Pick steps edits that day`() = runTest {
        val week = PlanSamples.weekPlan()
        val tuesday = week.day(DayOfWeek.TUESDAY)
        schedule.weekState.value = week.with(tuesday.copy(evening = tuesday.evening.copy(routine = listOf(1L))))

        assertThat(viewModel().ready().ownList).isTrue()
    }

    @Test
    fun `the comparison shows only Night steps' clear and possible patterns`() = runTest {
        fun effect(mark: Mark, metric: EffectMetric, label: PatternLabel) =
            MarkEffect(mark, metric, label, withNights = 9, withoutNights = 12, difference = 20.0, low = 5.0, high = 30.0)
        patterns.markEffectsState.value = listOf(
            effect(Mark.ALCOHOL, EffectMetric.TOTAL_SLEEP, PatternLabel.CLEAR),
            effect(Mark.NIGHT_STEPS_DONE, EffectMetric.TOTAL_SLEEP, PatternLabel.CLEAR),
            effect(Mark.NIGHT_STEPS_DONE, EffectMetric.AWAKE, PatternLabel.NO_PATTERN),
            effect(Mark.NIGHT_STEPS_DONE, EffectMetric.MORNING_FEEL, PatternLabel.POSSIBLE),
        )
        val viewModel = viewModel()

        val rows = viewModel.ready()
        assertThat(rows.compareStatus).isEqualTo(CompareStatus.ROWS)
        assertThat(rows.compare.map { it.metric }).containsExactly(EffectMetric.TOTAL_SLEEP, EffectMetric.MORNING_FEEL).inOrder()
        assertThat(rows.compare.all { it.mark == Mark.NIGHT_STEPS_DONE }).isTrue()

        patterns.markEffectsState.value = listOf(
            effect(Mark.NIGHT_STEPS_DONE, EffectMetric.TOTAL_SLEEP, PatternLabel.NO_PATTERN),
            effect(Mark.NIGHT_STEPS_DONE, EffectMetric.AWAKE, PatternLabel.NOT_ENOUGH),
        )
        assertThat(viewModel.ready().compareStatus).isEqualTo(CompareStatus.NO_PATTERN)

        patterns.markEffectsState.value = listOf(effect(Mark.NIGHT_STEPS_DONE, EffectMetric.AWAKE, PatternLabel.NOT_ENOUGH))
        assertThat(viewModel.ready().compareStatus).isEqualTo(CompareStatus.NOT_ENOUGH)
    }

    @Test
    fun `a failing pattern source doesn't take the checklist down`() = runTest {
        val broken = object : PatternsRepository by patterns {
            override val markEffects: Flow<List<MarkEffect>> = flow { throw IOException("disk") }
        }

        val state = viewModel(broken).ready()

        assertThat(state.compareStatus).isEqualTo(CompareStatus.NOT_ENOUGH)
        assertThat(state.session.steps).isNotEmpty()
    }
}
