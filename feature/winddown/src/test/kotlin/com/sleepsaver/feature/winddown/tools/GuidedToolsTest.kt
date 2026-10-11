package com.sleepsaver.feature.winddown.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.testing.FakeJournalRepository
import com.sleepsaver.core.testing.FakeNightController
import com.sleepsaver.core.testing.FakeNightStepsRepository
import com.sleepsaver.core.testing.FakeScheduleRepository
import com.sleepsaver.core.testing.FakeTimeProvider
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.feature.winddown.RecordingHaptics
import com.sleepsaver.feature.winddown.nightSource
import com.sleepsaver.feature.winddown.reset.NightResetViewModel
import com.sleepsaver.feature.winddown.reset.ResetStage
import com.sleepsaver.feature.winddown.richActivities
import com.sleepsaver.feature.winddown.richSession
import com.sleepsaver.feature.winddown.testClock
import com.sleepsaver.feature.winddown.writer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/**
 * The timed tools on a fake clock (the test scheduler's virtual time): which cue shows when, which haptic fires, and
 * that finishing checks the technique off in tonight's steps. Haptics go through the injected [Haptics] seam
 * (bound to the vibrator by WinddownModule in the app).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GuidedToolsTest {

    private val haptics = RecordingHaptics()

    /** Tonight's six steps, none checked: Slow tide is id 3, Body scan id 8, Tomorrow list id 2. */
    private val nightSteps = FakeNightStepsRepository(tonight = richSession(done = 0), activities = richActivities())

    /** Tue Oct 6, 10:20; Wednesday's alarm is 06:45 with a 30-min window (bedtime 23:00). */
    private val time = FakeTimeProvider()
    private val schedule = FakeScheduleRepository(upcoming = listOf(PlanSamples.plannedAlarm()))

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.completion() = ToolCompletion(nightSource(nightSteps, time, schedule), nightSteps, writer())

    /** [at] on [date]; at midnight the repository's "tonight" has moved on to Wednesday, Tuesday's stays readable. */
    private fun afterMidnight(date: LocalDate = PlanSamples.today.plusDays(1), at: LocalTime = LocalTime.of(0, 20)) {
        time.now = PlanSamples.at(date, at)
        time.ticks.value = time.now
        nightSteps.sessionsState.value = mapOf(PlanSamples.today to nightSteps.tonightState.value!!)
        nightSteps.tonightState.value =
            PlanSamples.nightStepsSession(nightOf = PlanSamples.today.plusDays(1), done = 0, activities = richActivities())
    }

    private fun TestScope.step(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <C> ToolStage<C>.running(): RunPosition<C> = (this as ToolStage.Running<C>).position

    // ---------------------------------------------------------------- Slow tide

    @Test
    fun `slow tide cues each half breath on the clock`() = runTest {
        val viewModel = SlowTideViewModel(haptics, testClock(), completion())
        assertThat(viewModel.stage.value).isEqualTo(ToolStage.Setup)

        viewModel.start()
        runCurrent()
        assertThat(viewModel.stage.value.running().cue).isEqualTo(BreathPhase.IN)
        assertThat(haptics.cues).containsExactly("tick")

        step(3_999)
        assertThat(viewModel.stage.value.running().cue).isEqualTo(BreathPhase.IN)
        step(1)
        assertThat(viewModel.stage.value.running().cue).isEqualTo(BreathPhase.OUT)
        assertThat(viewModel.stage.value.running().length).isEqualTo(Duration.ofSeconds(6))
        assertThat(haptics.cues).containsExactly("tick", "doubleTick").inOrder()

        step(6_000)
        assertThat(viewModel.stage.value.running().cue).isEqualTo(BreathPhase.IN)
        assertThat(viewModel.stage.value.running().index).isEqualTo(2)
        assertThat(haptics.cues).containsExactly("tick", "doubleTick", "tick").inOrder()
    }

    @Test
    fun `slow tide runs its whole length, pulses at the end and checks itself off`() = runTest {
        val viewModel = SlowTideViewModel(haptics, testClock(), completion())
        viewModel.setLength(Duration.ofMinutes(5))
        viewModel.start()
        runCurrent()

        step(Duration.ofMinutes(5).toMillis())

        assertThat(viewModel.stage.value).isEqualTo(ToolStage.Finished(checkedOff = true))
        // 5 min at 4 in · 6 out = 30 breaths: a tick and a double tick each, then the end pulse.
        assertThat(haptics.cues.count { it == "tick" }).isEqualTo(30)
        assertThat(haptics.cues.count { it == "doubleTick" }).isEqualTo(30)
        assertThat(haptics.cues.last()).isEqualTo("pulse")
        assertThat(nightSteps.doneCalls).containsExactly(Triple(PlanSamples.today, 3L, true))
    }

    @Test
    fun `another pace changes the rhythm`() = runTest {
        val viewModel = SlowTideViewModel(haptics, testClock(), completion())
        val longOut = viewModel.uiState.value.patterns[2]
        viewModel.setPattern(longOut)
        viewModel.setEyesClosed(true)
        viewModel.start()
        runCurrent()

        step(4_000)
        assertThat(viewModel.stage.value.running().cue).isEqualTo(BreathPhase.OUT)
        step(7_999)
        assertThat(viewModel.stage.value.running().cue).isEqualTo(BreathPhase.OUT)
        step(1)
        assertThat(viewModel.stage.value.running().cue).isEqualTo(BreathPhase.IN)
        assertThat(viewModel.uiState.value.eyesClosed).isTrue()
        assertThat(viewModel.uiState.value.pattern).isEqualTo(BreathPattern(Duration.ofSeconds(4), Duration.ofSeconds(8)))
    }

    @Test
    fun `finishing a tool that isn't in tonight's steps checks nothing off`() = runTest {
        nightSteps.tonightState.value = richSession(done = 0).let { session ->
            session.copy(steps = session.steps.filter { it.activity.technique != Technique.SLOW_TIDE })
        }
        val viewModel = SlowTideViewModel(haptics, testClock(), completion())
        viewModel.setLength(Duration.ofMinutes(5))
        viewModel.start()
        runCurrent()

        step(Duration.ofMinutes(5).toMillis())

        assertThat(viewModel.stage.value).isEqualTo(ToolStage.Finished(checkedOff = false))
        assertThat(nightSteps.doneCalls).isEmpty()
    }

    @Test
    fun `nothing is checked off on an evening with Night steps off`() = runTest {
        nightSteps.tonightState.value = richSession(done = 0, enabled = false)

        assertThat(completion().markDone(Technique.SLOW_TIDE)).isFalse()
        assertThat(nightSteps.doneCalls).isEmpty()
    }

    // ---------------------------------------------------------------- Body scan

    @Test
    fun `body scan pulses at each area and holds while paused`() = runTest {
        val viewModel = BodyScanViewModel(haptics, testClock(), completion())
        viewModel.start()
        runCurrent()
        assertThat(viewModel.stage.value.running().cue).isEqualTo(0)
        assertThat(haptics.cues).containsExactly("pulse")

        step(60_000)
        assertThat(viewModel.stage.value.running().cue).isEqualTo(1)
        assertThat(haptics.cues).containsExactly("pulse", "pulse")

        step(30_000)
        viewModel.pause()
        assertThat(viewModel.stage.value.running().paused).isTrue()
        step(Duration.ofMinutes(10).toMillis())
        assertThat(viewModel.stage.value.running().cue).isEqualTo(1)
        assertThat(haptics.cues).hasSize(2)

        viewModel.resume()
        runCurrent()
        assertThat(viewModel.stage.value.running().paused).isFalse()
        step(29_999)
        assertThat(viewModel.stage.value.running().cue).isEqualTo(1)
        step(1)
        assertThat(viewModel.stage.value.running().cue).isEqualTo(2)
        assertThat(haptics.cues).hasSize(3)
    }

    @Test
    fun `body scan ends after ten areas and checks itself off`() = runTest {
        val viewModel = BodyScanViewModel(haptics, testClock(), completion())
        viewModel.start()
        runCurrent()

        step(Duration.ofMinutes(10).toMillis())

        assertThat(viewModel.stage.value).isEqualTo(ToolStage.Finished(checkedOff = true))
        // One pulse per area, then the end.
        assertThat(haptics.cues).containsExactlyElementsIn(List(11) { "pulse" })
        assertThat(nightSteps.doneCalls).containsExactly(Triple(PlanSamples.today, 8L, true))
    }

    @Test
    fun `a body scan finished after midnight ticks last night's step`() = runTest {
        afterMidnight()
        val viewModel = BodyScanViewModel(haptics, testClock(), completion())
        viewModel.start()
        runCurrent()

        step(Duration.ofMinutes(10).toMillis())

        assertThat(viewModel.stage.value).isEqualTo(ToolStage.Finished(checkedOff = true))
        assertThat(nightSteps.doneCalls).containsExactly(Triple(PlanSamples.today, 8L, true))
    }

    // ---------------------------------------------------------------- Muscle release

    @Test
    fun `muscle release tenses then lets go, group by group`() = runTest {
        val viewModel = MuscleReleaseViewModel(haptics, testClock(), completion())
        viewModel.start()
        runCurrent()
        assertThat(viewModel.stage.value.running().cue).isEqualTo(MuscleCue(MuscleGroup.HANDS, tense = true))

        step(5_000)
        assertThat(viewModel.stage.value.running().cue).isEqualTo(MuscleCue(MuscleGroup.HANDS, tense = false))
        step(15_000)
        assertThat(viewModel.stage.value.running().cue).isEqualTo(MuscleCue(MuscleGroup.FOREARMS, tense = true))
        assertThat(haptics.cues).containsExactly("tick", "doubleTick", "tick").inOrder()

        step(Duration.ofMinutes(4).toMillis())
        // Muscle release isn't one of tonight's steps.
        assertThat(viewModel.stage.value).isEqualTo(ToolStage.Finished(checkedOff = false))
        assertThat(haptics.cues.count { it == "tick" }).isEqualTo(12)
        assertThat(haptics.cues.count { it == "doubleTick" }).isEqualTo(12)
        assertThat(haptics.cues.last()).isEqualTo("pulse")
    }

    // ---------------------------------------------------------------- Tomorrow list

    private fun TestScope.tomorrowList(journal: FakeJournalRepository = FakeJournalRepository()): TomorrowListViewModel {
        val viewModel = TomorrowListViewModel(nightSource(nightSteps, time, schedule), journal, time, completion(), writer())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    @Test
    fun `tomorrow list saves for tonight and checks its step off`() = runTest {
        val journal = FakeJournalRepository()
        val viewModel = tomorrowList(journal)
        assertThat(viewModel.uiState.value).isEqualTo(TomorrowListUiState.Ready(PlanSamples.today, ""))

        viewModel.save("Call Sam\nBuy milk")
        runCurrent()

        assertThat(journal.tomorrowListsSet).containsExactly(PlanSamples.today to "Call Sam\nBuy milk")
        assertThat(nightSteps.doneCalls).containsExactly(Triple(PlanSamples.today, 2L, true))
        assertThat(viewModel.uiState.value)
            .isEqualTo(TomorrowListUiState.Ready(PlanSamples.today, "Call Sam\nBuy milk", saved = true, checkedOff = true))
    }

    @Test
    fun `tomorrow list opens with tonight's saved list and a blank one checks nothing`() = runTest {
        val journal = FakeJournalRepository()
        journal.setTomorrowList(PlanSamples.today, "Pack lunch")
        val viewModel = tomorrowList(journal)
        assertThat((viewModel.uiState.value as TomorrowListUiState.Ready).text).isEqualTo("Pack lunch")

        viewModel.save("  ")
        runCurrent()

        assertThat(nightSteps.doneCalls).isEmpty()
        assertThat((viewModel.uiState.value as TomorrowListUiState.Ready).checkedOff).isFalse()
    }

    @Test
    fun `a list written after midnight is last night's, the one the morning shows`() = runTest {
        val journal = FakeJournalRepository()
        journal.setTomorrowList(PlanSamples.today, "Pack lunch")
        afterMidnight()

        val viewModel = tomorrowList(journal)
        assertThat(viewModel.uiState.value).isEqualTo(TomorrowListUiState.Ready(PlanSamples.today, "Pack lunch"))

        viewModel.save("Pack lunch\nCall Sam")
        runCurrent()

        assertThat(journal.tomorrowListsSet.last()).isEqualTo(PlanSamples.today to "Pack lunch\nCall Sam")
        assertThat(nightSteps.doneCalls).containsExactly(Triple(PlanSamples.today, 2L, true))
    }

    @Test
    fun `without Night steps the list still belongs to the night in progress`() = runTest {
        nightSteps.tonightState.value = null
        time.now = PlanSamples.at(PlanSamples.today.plusDays(1), LocalTime.of(1, 0))
        time.ticks.value = time.now

        val viewModel = tomorrowList()

        assertThat((viewModel.uiState.value as TomorrowListUiState.Ready).nightOf).isEqualTo(PlanSamples.today)
        // When even that can't be read: the evening before the wake date.
        assertThat(fallbackNightOf(PlanSamples.at(PlanSamples.today, LocalTime.of(23, 10)), SampleData.zone)).isEqualTo(PlanSamples.today)
        assertThat(fallbackNightOf(time.now, SampleData.zone)).isEqualTo(PlanSamples.today)
    }

    // ---------------------------------------------------------------- Night reset

    @Test
    fun `night reset breathes with eyes-closed ticks for two minutes, then shows the advice`() = runTest {
        val viewModel = NightResetViewModel(FakeNightController(), FakeScheduleRepository(), haptics, testClock())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        runCurrent()
        assertThat(viewModel.uiState.value.stage).isEqualTo(ResetStage.BREATHING)
        assertThat(viewModel.uiState.value.eyesClosed).isTrue()
        assertThat(viewModel.uiState.value.breath?.cue).isEqualTo(BreathPhase.IN)
        assertThat(haptics.cues).containsExactly("tick")

        step(4_000)
        assertThat(viewModel.uiState.value.breath?.cue).isEqualTo(BreathPhase.OUT)
        assertThat(haptics.cues).containsExactly("tick", "doubleTick").inOrder()

        step(Duration.ofMinutes(2).toMillis())
        assertThat(viewModel.uiState.value.stage).isEqualTo(ResetStage.TEXT)
        assertThat(viewModel.uiState.value.breath).isNull()
        assertThat(haptics.cues.last()).isEqualTo("pulse")
    }

    @Test
    fun `skipping goes straight to the advice and stops the cues`() = runTest {
        val viewModel = NightResetViewModel(FakeNightController(), FakeScheduleRepository(), haptics, testClock())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        runCurrent()

        viewModel.skip()
        runCurrent()
        step(Duration.ofMinutes(3).toMillis())

        assertThat(viewModel.uiState.value.stage).isEqualTo(ResetStage.TEXT)
        assertThat(haptics.cues).containsExactly("tick")
    }

    @Test
    fun `night reset plays Hush with the default choice, never until a window without an alarm`() = runTest {
        val nightController = FakeNightController()
        val viewModel = NightResetViewModel(nightController, FakeScheduleRepository(), haptics, testClock())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        runCurrent()

        viewModel.hushActions.onTimer(null)
        viewModel.hushActions.onPlay()
        runCurrent()

        assertThat(nightController.nightControllerCalls).containsExactly("startHush(WHITE, PT45M, 0.25)")
        assertThat(viewModel.uiState.value.hush.playing).isTrue()
        assertThat(viewModel.uiState.value.hush.night).isTrue()
    }
}
