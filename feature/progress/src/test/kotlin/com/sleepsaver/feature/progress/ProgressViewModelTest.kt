package com.sleepsaver.feature.progress

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.patterns.EffectMetric
import com.sleepsaver.core.domain.patterns.KeelResult
import com.sleepsaver.core.domain.patterns.MarkEffect
import com.sleepsaver.core.domain.patterns.PatternLabel
import com.sleepsaver.core.testing.FakeInsightsRepository
import com.sleepsaver.core.testing.FakePatternsRepository
import com.sleepsaver.core.testing.FakeSettingsRepository
import com.sleepsaver.core.testing.FakeSleepRepository
import com.sleepsaver.core.testing.FakeSyncRepository
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.SampleData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressViewModelTest {

    private val today = SampleData.today // Tue 2026-10-06
    private val nights = SampleData.nights() // wake dates Sep 7 … Oct 6

    private val sleep = FakeSleepRepository(nights).apply { vitalsState.value = SampleData.vitals(SampleData.nights()) }
    private val insights = FakeInsightsRepository(
        debtSeriesResult = SampleData.debtSeries(today.minusDays(90), today, nights),
    )
    private val settings = FakeSettingsRepository(AppSettings(onboardingDone = true))
    private val sync = FakeSyncRepository()
    private val clock = FakeTimeProvider()
    private val patterns = FakePatternsRepository()
    private val savedState = SavedStateHandle()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        compute: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
    ): ProgressViewModel {
        val vm = ProgressViewModel(savedState, sleep, insights, settings, sync, clock, compute, patterns)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    private suspend fun ProgressViewModel.ready(): ProgressUiState.Ready =
        uiState.first { it is ProgressUiState.Ready } as ProgressUiState.Ready

    @Test
    fun `starts on the 14 days ending today`() = runTest {
        val state = viewModel().ready()

        assertThat(state.today).isEqualTo(today)
        assertThat(state.window.from).isEqualTo(LocalDate.of(2026, 9, 23))
        assertThat(state.window.to).isEqualTo(today)
        assertThat(state.window.canGoForward).isFalse()
        assertThat(state.window.canGoBack).isTrue()
        assertThat(state.sleepTimes.columns.map { it.wakeDate }).isEqualTo(state.window.dates)
        assertThat(state.debt.points.map { it.date }).isEqualTo(state.window.dates)
        assertThat(state.quality.chart.bars).hasSize(14)
        assertThat(state.hasNightsInWindow).isTrue()
    }

    @Test
    fun `lists are newest first with recorded clock times`() = runTest {
        val state = viewModel().ready()

        assertThat(state.nightRows.map { it.wakeDate }).isEqualTo(state.window.dates.reversed())
        val lastNight = nights.single { it.wakeDate == today }
        val row = state.nightRows.first()
        assertThat(row.fellAsleep).isEqualTo(lastNight.fellAsleep!!.atZone(SampleData.zone).toLocalTime())
        assertThat(row.wokeUp).isEqualTo(lastNight.wokeUp!!.atZone(SampleData.zone).toLocalTime())
        assertThat(row.total).isEqualTo(lastNight.totalSleep)
        assertThat(row.isMissing).isFalse()
    }

    @Test
    fun `pages back two weeks at a time and stops at the oldest night`() = runTest {
        val vm = viewModel()
        vm.ready()

        vm.showNextWindow() // already on the latest window: no-op
        assertThat(vm.ready().window.to).isEqualTo(today)

        vm.showPreviousWindow()
        val previous = vm.ready()
        assertThat(previous.window.from).isEqualTo(LocalDate.of(2026, 9, 9))
        assertThat(previous.window.to).isEqualTo(LocalDate.of(2026, 9, 22))
        assertThat(previous.window.canGoForward).isTrue()
        assertThat(previous.window.canGoBack).isTrue()

        vm.showPreviousWindow()
        val oldest = vm.ready()
        assertThat(oldest.window.to).isEqualTo(LocalDate.of(2026, 9, 8))
        assertThat(oldest.window.canGoBack).isFalse() // holds Sep 7, the first night
        assertThat(oldest.nightRows.count { !it.isMissing }).isEqualTo(2)

        vm.showPreviousWindow() // no-op
        assertThat(vm.ready().window.to).isEqualTo(LocalDate.of(2026, 9, 8))

        vm.showNextWindow()
        assertThat(vm.ready().window.to).isEqualTo(LocalDate.of(2026, 9, 22))
        vm.showLatestWindow()
        assertThat(vm.ready().window.to).isEqualTo(today)
    }

    @Test
    fun `a double tap on previous can't page past the oldest night`() = runTest {
        // Only the last 20 nights: the window before the current one is the oldest.
        sleep.nightsState.value = nights.filter { it.wakeDate > today.minusDays(20) }
        // The charts are only rebuilt when the test lets the scheduler run, like a busy device.
        val vm = viewModel(compute = StandardTestDispatcher(testScheduler))
        assertThat(vm.ready().window.canGoBack).isTrue()

        vm.showPreviousWindow()
        vm.showPreviousWindow() // arrives while the screen still shows the latest window

        val previous = vm.uiState.first { (it as? ProgressUiState.Ready)?.window?.to == today.minusDays(14) }
        assertThat((previous as ProgressUiState.Ready).window.canGoBack).isFalse()
        advanceUntilIdle()
        assertThat((vm.uiState.value as ProgressUiState.Ready).window.to).isEqualTo(today.minusDays(14))
    }

    @Test
    fun `the page survives recreation`() = runTest {
        viewModel().apply { ready(); showPreviousWindow(); ready() }

        val recreated = viewModel().ready()

        assertThat(recreated.window.to).isEqualTo(LocalDate.of(2026, 9, 22))
    }

    @Test
    fun `no nights at all is the empty state`() = runTest {
        sleep.nightsState.value = emptyList()

        val state = viewModel().uiState.first { it !is ProgressUiState.Loading }

        assertThat(state).isEqualTo(ProgressUiState.NoNights(today, NoNightsReason.NO_DATA))
    }

    @Test
    fun `the empty state knows when Health Connect is missing or sleep access is off`() = runTest {
        sleep.nightsState.value = emptyList()
        sync.mutableState.value = SyncState(lastError = SyncError.HEALTH_CONNECT_NOT_INSTALLED)
        val vm = viewModel()

        assertThat((vm.uiState.first { it is ProgressUiState.NoNights } as ProgressUiState.NoNights).reason)
            .isEqualTo(NoNightsReason.HEALTH_CONNECT_UNAVAILABLE)

        sync.mutableState.value = SyncState(lastError = SyncError.PERMISSION_DENIED)
        vm.uiState.first { (it as? ProgressUiState.NoNights)?.reason == NoNightsReason.PERMISSION_DENIED }

        sync.mutableState.value = SyncState(lastError = SyncError.RATE_LIMITED)
        vm.uiState.first { (it as? ProgressUiState.NoNights)?.reason == NoNightsReason.NO_DATA }
    }

    @Test
    fun `a sync error doesn't hide nights that are already in`() = runTest {
        sync.mutableState.value = SyncState(lastError = SyncError.PERMISSION_DENIED)

        assertThat(viewModel().ready().hasNightsInWindow).isTrue()
    }

    @Test
    fun `a window without nights still renders, every night missing`() = runTest {
        // A night two months ago and the recent ones: the window in between is empty.
        val old = nights.first().copy(wakeDate = today.minusDays(60))
        sleep.nightsState.value = listOf(old) + nights.filter { it.wakeDate > today.minusDays(14) }
        val vm = viewModel()
        vm.ready()

        vm.showPreviousWindow()
        val empty = vm.ready()

        assertThat(empty.window.to).isEqualTo(LocalDate.of(2026, 9, 22))
        assertThat(empty.hasNightsInWindow).isFalse()
        assertThat(empty.nightRows.all { it.isMissing }).isTrue()
        assertThat(empty.sleepTimes.columns.all { it.isMissing }).isTrue()
        assertThat(empty.quality.chart.isEmpty).isTrue()
        assertThat(empty.window.canGoBack).isTrue()
    }

    @Test
    fun `missing and edited nights are marked`() = runTest {
        val eventful = ProgressFixtures.eventfulNights()
        sleep.nightsState.value = eventful

        val state = viewModel().ready()

        val missing = state.nightRows.filter { it.isMissing }.map { it.wakeDate }
        assertThat(missing).containsExactly(today.minusDays(2), today.minusDays(5), today.minusDays(6))
        assertThat(state.nightRows.single { it.isEdited }.wakeDate).isEqualTo(today.minusDays(4))
        val napOnly = state.sleepTimes.columns.single { it.wakeDate == today.minusDays(2) }
        assertThat(napOnly.isMissing).isTrue()
        assertThat(napOnly.offAxis).isNotEmpty()
    }

    @Test
    fun `settings move the streak line`() = runTest {
        val vm = viewModel()
        assertThat(vm.ready().debt.threshold).isEqualTo(Duration.ofHours(5))

        settings.setStreakThreshold(Duration.ofHours(3))

        val state = vm.uiState.first { (it as? ProgressUiState.Ready)?.debt?.threshold == Duration.ofHours(3) } as ProgressUiState.Ready
        assertThat(state.debt.thresholdY).isWithin(1e-6f).of(3 / 12f)
    }

    @Test
    fun `vitals for the window become trends`() = runTest {
        val state = viewModel().ready()

        assertThat(state.quality.restingHeartRate).isNotNull()
        assertThat(state.quality.heartRateVariability).isNotNull()
        assertThat(state.quality.restingHeartRate!!.values).hasSize(14)
    }

    @Test
    fun `without heart-rate data there are no trends`() = runTest {
        sleep.vitalsState.value = emptyList()

        val state = viewModel().ready()

        assertThat(state.quality.hasVitals).isFalse()
    }

    @Test
    fun `the window follows the date at midnight`() = runTest {
        val vm = viewModel()
        assertThat(vm.ready().window.to).isEqualTo(today)

        clock.ticks.value = ZonedDateTime.of(today.plusDays(1), LocalTime.of(0, 1), SampleData.zone).toInstant()

        val next = vm.uiState.first { (it as? ProgressUiState.Ready)?.today == today.plusDays(1) } as ProgressUiState.Ready
        assertThat(next.window.to).isEqualTo(today.plusDays(1))
        assertThat(next.nightRows.first().isMissing).isTrue()
        assertThat(next.debt.points.last().waiting).isTrue()
    }

    @Test
    fun `new nights show up without paging`() = runTest {
        sleep.nightsState.value = nights.filter { it.wakeDate < today }
        val vm = viewModel()
        assertThat(vm.ready().nightRows.first().isMissing).isTrue()

        sleep.nightsState.value = nights

        val state = vm.uiState.first { (it as? ProgressUiState.Ready)?.nightRows?.first()?.isMissing == false }
        assertThat((state as ProgressUiState.Ready).sleepTimes.columns.last().isMissing).isFalse()
    }

    // ---------------------------------------------------------------- Keel and marks

    @Test
    fun `keel, social jetlag and mark patterns come in as of today`() = runTest {
        patterns.keelState.value = PlanSamples.keelReady()
        patterns.socialJetlagState.value = PlanSamples.socialJetlag()
        patterns.markEffectsState.value = PlanSamples.markEffects() + MarkEffect(
            Mark.NIGHT_STEPS_DONE, EffectMetric.TOTAL_SLEEP, PatternLabel.CLEAR, 12, 30, difference = 20.0, low = 9.0, high = 31.0,
        )
        val vm = viewModel()

        val state = vm.uiState.first { (it as? ProgressUiState.Ready)?.keel is KeelResult.Ready } as ProgressUiState.Ready

        assertThat(state.keel).isEqualTo(PlanSamples.keelReady())
        assertThat(state.socialJetlag).isEqualTo(PlanSamples.socialJetlag())
        // Night steps done is Night steps' own comparison: never listed here.
        assertThat(state.marks.shown.map { it.mark }).containsExactly(Mark.ALCOHOL, Mark.LATE_CAFFEINE).inOrder()
        assertThat(state.marks.waiting.single().mark).isEqualTo(Mark.STRESS)
    }

    @Test
    fun `keel stays on today's value while paging back`() = runTest {
        patterns.keelState.value = PlanSamples.keelCalibrating()
        val vm = viewModel()
        vm.uiState.first { (it as? ProgressUiState.Ready)?.keel != null }

        vm.showPreviousWindow()

        val previous = vm.uiState.first { (it as? ProgressUiState.Ready)?.window?.to == today.minusDays(14) } as ProgressUiState.Ready
        assertThat(previous.keel).isEqualTo(PlanSamples.keelCalibrating())
    }

    @Test
    fun `failing patterns never hold up the charts`() = runTest {
        val failing = object : com.sleepsaver.core.data.repository.PatternsRepository by patterns {
            override val keel = kotlinx.coroutines.flow.flow<KeelResult> { error("disk") }
            override val markEffects = kotlinx.coroutines.flow.flow<List<MarkEffect>> { error("disk") }
        }
        val vm = ProgressViewModel(savedState, sleep, insights, settings, sync, clock, UnconfinedTestDispatcher(testScheduler), failing)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        val state = vm.ready()

        assertThat(state.keel).isNull()
        assertThat(state.marks.shown).isEmpty()
        assertThat(state.hasNightsInWindow).isTrue()
    }
}
