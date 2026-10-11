package com.sleepsaver.feature.review.detail

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.MovementTrace
import com.sleepsaver.core.domain.alarm.LandingStage
import com.sleepsaver.core.domain.alarm.MovementEpoch
import com.sleepsaver.core.domain.journal.FeelSource
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.journal.MorningFeelEntry
import com.sleepsaver.core.domain.model.ManualNap
import com.sleepsaver.core.testing.FakeJournalRepository
import com.sleepsaver.core.testing.FakeNightLogRepository
import com.sleepsaver.core.testing.FakePatternsRepository
import com.sleepsaver.core.testing.FakeSleepRepository
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.feature.review.FakeTimeProvider
import com.sleepsaver.feature.review.TestNight
import com.sleepsaver.feature.review.TestNight.at
import com.sleepsaver.feature.review.TestNight.wakeDate
import com.sleepsaver.feature.review.common.NightSourceKind
import com.sleepsaver.feature.review.common.manualNapId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class NightDetailViewModelTest {

    private val nap = ManualNap(id = 7, start = at(wakeDate, 14), end = at(wakeDate, 14, 25), offset = TestNight.offset)
    private val sleep = FakeSleepRepository(TestNight.nights(manualNaps = listOf(nap)))
    private var time = FakeTimeProvider(now = TestNight.now, zone = TestNight.zone)
    private val journal = FakeJournalRepository()
    private val nightLog = FakeNightLogRepository()
    private val patterns = FakePatternsRepository()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(date: LocalDate = wakeDate) =
        NightDetailViewModel(SavedStateHandle(mapOf("wakeDate" to date.toString())), sleep, time, journal, nightLog, patterns)

    private fun TestScope.collected(date: LocalDate = wakeDate): NightDetailViewModel =
        viewModel(date).also { vm -> backgroundScope.launch { vm.uiState.collect {} } }

    @Test
    fun `shows the night with its source and naps`() = runTest {
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val state = vm.uiState.first { !it.loading }
        assertThat(state.isMissing).isFalse()
        assertThat(state.night!!.wakeDate).isEqualTo(wakeDate)
        assertThat(state.source!!.kind).isEqualTo(NightSourceKind.HEALTH_CONNECT)
        assertThat(state.night!!.naps.single().manualNapId).isEqualTo(7)
    }

    @Test
    fun `a night with no data is missing`() = runTest {
        val vm = viewModel(wakeDate.minusDays(3))
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.isMissing).isTrue()
    }

    @Test
    fun `deletes a manual nap and reverts edits`() = runTest {
        val vm = viewModel()

        vm.onDeleteNap(7)
        vm.onRevert()
        advanceUntilIdle()

        assertThat(sleep.deletedNaps).containsExactly(7L)
        assertThat(sleep.reverted).containsExactly(wakeDate)
    }

    @Test
    fun `marks, feel, anchor, landing and movement come with the night`() = runTest {
        val offset = TestNight.offset
        journal.marksState.value = mapOf(wakeDate to setOf(Mark.ALCOHOL))
        journal.feelsState.value = mapOf(wakeDate to MorningFeelEntry(wakeDate, MorningFeel.CLEAR, TestNight.now, offset, FeelSource.RING))
        patterns.anchorStatsState.value = mapOf(wakeDate to PlanSamples.anchorStats(wakeDate))
        nightLog.landingsState.value = mapOf(wakeDate to PlanSamples.landing(LandingStage.REM, wakeDate))
        val trace = MovementTrace(1, "WINDOW", at(wakeDate, 6), at(wakeDate, 6, 30), listOf(MovementEpoch(at(wakeDate, 6, 10), 14.0)))
        nightLog.movementState.value = mapOf(wakeDate to listOf(trace))
        val vm = collected()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.marks).containsExactly(Mark.ALCOHOL)
        assertThat(state.feel).isEqualTo(MorningFeel.CLEAR)
        assertThat(state.feelEditable).isTrue() // the same day
        assertThat(state.marksEditable).isTrue()
        assertThat(state.anchor?.latencyShown).isEqualTo(Duration.ofMinutes(15))
        assertThat(state.landing?.stageAtRing).isEqualTo(LandingStage.REM)
        assertThat(state.movement).containsExactly(trace)
    }

    @Test
    fun `marks and feel are saved for this night`() = runTest {
        val vm = collected()
        advanceUntilIdle()

        vm.onMarkToggle(Mark.LATE_MEAL)
        advanceUntilIdle()
        vm.onMarkToggle(Mark.HUSH_USED) // derived: never set by hand
        vm.onFeel(MorningFeel.SLOW)
        advanceUntilIdle()

        assertThat(journal.marksSet).containsExactly(Triple(wakeDate, Mark.LATE_MEAL, true))
        assertThat(vm.uiState.value.marks).containsExactly(Mark.LATE_MEAL)
        val feel = journal.feelsSet.single()
        assertThat(feel.feel).isEqualTo(MorningFeel.SLOW)
        assertThat(feel.source).isEqualTo(FeelSource.REVIEW)
    }

    @Test
    fun `feel stays editable after midnight until the next wake time`() = runTest {
        patterns.debtPreviewInputsState.value = PlanSamples.debtPreviewInputs()
        time = FakeTimeProvider(now = at(wakeDate.plusDays(1), 0, 30), zone = TestNight.zone)
        val early = collected()
        advanceUntilIdle()
        assertThat(early.uiState.value.feelEditable).isTrue()

        time = FakeTimeProvider(now = at(wakeDate.plusDays(1), 7, 0), zone = TestNight.zone)
        val late = collected()
        advanceUntilIdle()
        assertThat(late.uiState.value.feelEditable).isFalse()
        late.onFeel(MorningFeel.OKAY)
        advanceUntilIdle()
        assertThat(journal.feelsSet).isEmpty()
    }

    @Test
    fun `an old night's marks are read-only`() = runTest {
        sleep.nightsState.value = TestNight.nights()
        val vm = collected(wakeDate.minusDays(10))
        advanceUntilIdle()

        assertThat(vm.uiState.value.marksEditable).isFalse()
        vm.onMarkToggle(Mark.STRESS)
        advanceUntilIdle()
        assertThat(journal.marksSet).isEmpty()
    }
}
