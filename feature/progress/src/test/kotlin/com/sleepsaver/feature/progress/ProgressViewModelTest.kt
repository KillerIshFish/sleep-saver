package com.sleepsaver.feature.progress

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.testing.FakeInsightsRepository
import com.sleepsaver.core.testing.FakeSettingsRepository
import com.sleepsaver.core.testing.FakeSleepRepository
import com.sleepsaver.core.testing.SampleData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
    private val clock = FakeTimeProvider()
    private val savedState = SavedStateHandle()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): ProgressViewModel {
        val vm = ProgressViewModel(savedState, sleep, insights, settings, clock)
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
    fun `the page survives recreation`() = runTest {
        viewModel().apply { ready(); showPreviousWindow(); ready() }

        val recreated = viewModel().ready()

        assertThat(recreated.window.to).isEqualTo(LocalDate.of(2026, 9, 22))
    }

    @Test
    fun `no nights at all is the empty state`() = runTest {
        sleep.nightsState.value = emptyList()

        val state = viewModel().uiState.first { it !is ProgressUiState.Loading }

        assertThat(state).isEqualTo(ProgressUiState.NoNights(today))
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
}
