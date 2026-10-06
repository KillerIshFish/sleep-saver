package com.sleepsaver.feature.energy

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.data.repository.SyncStatus
import com.sleepsaver.core.data.repository.SyncTrigger
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.LastNightState
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.testing.FakeHabitsRepository
import com.sleepsaver.core.testing.FakeHealthConnectGateway
import com.sleepsaver.core.testing.FakeInsightsRepository
import com.sleepsaver.core.testing.FakeSettingsRepository
import com.sleepsaver.core.testing.FakeSleepRepository
import com.sleepsaver.core.testing.FakeSyncRepository
import com.sleepsaver.core.testing.SampleData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class EnergyViewModelTest {

    private class FakeTimeProvider(start: Instant) : TimeProvider {
        val ticks = MutableStateFlow(start)
        override fun now(): Instant = ticks.value
        override fun zone(): ZoneId = SampleData.zone
        override val minuteTicks: Flow<Instant> = ticks
    }

    private val insights = FakeInsightsRepository()
    private val sleep = FakeSleepRepository(SampleData.nights())
    private val settings = FakeSettingsRepository()
    private val habits = FakeHabitsRepository()
    private val sync = FakeSyncRepository()
    private val time = FakeTimeProvider(SampleData.now)

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val healthConnect = FakeHealthConnectGateway()

    private fun viewModel() =
        EnergyViewModel(insights, sleep, settings, habits, sync, healthConnect, SavedStateHandle(), time, dispatcher)

    @Test
    fun `starts loading`() = runTest {
        assertThat(viewModel().uiState.value).isEqualTo(EnergyUiState.Loading)
    }

    @Test
    fun `no nights shows the empty state`() = runTest {
        insights.mutableState.value = InsightsState.NoNights

        assertThat(viewModel().uiState.first { it != EnergyUiState.Loading }).isEqualTo(EnergyUiState.NoNights(EmptyReason.NO_DATA))
    }

    @Test
    fun `the empty state says what to fix`() = runTest {
        insights.mutableState.value = InsightsState.NoNights
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        sync.mutableState.value = SyncState(lastError = SyncError.HEALTH_CONNECT_NOT_INSTALLED)
        assertThat(viewModel.uiState.value).isEqualTo(EnergyUiState.NoNights(EmptyReason.HEALTH_CONNECT_MISSING))

        sync.mutableState.value = SyncState(lastError = SyncError.HEALTH_CONNECT_UPDATE_REQUIRED)
        assertThat(viewModel.uiState.value).isEqualTo(EnergyUiState.NoNights(EmptyReason.HEALTH_CONNECT_UPDATE))

        sync.mutableState.value = SyncState(lastError = SyncError.PERMISSION_DENIED)
        assertThat(viewModel.uiState.value).isEqualTo(EnergyUiState.NoNights(EmptyReason.PERMISSION_DENIED))

        sync.mutableState.value = SyncState(status = SyncStatus.Importing(0.4f), lastError = SyncError.PERMISSION_DENIED)
        assertThat(viewModel.uiState.value).isEqualTo(EnergyUiState.NoNights(EmptyReason.IMPORTING))
    }

    @Test
    fun `sync now runs a manual sync, once while one is running`() = runTest {
        insights.mutableState.value = SampleData.readyState()
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        viewModel.syncNow()
        sync.mutableState.value = SyncState(status = SyncStatus.Syncing)
        viewModel.syncNow()

        assertThat(sync.syncCalls).containsExactly(SyncTrigger.MANUAL)
        assertThat((viewModel.uiState.value as EnergyUiState.Ready).syncing).isTrue()
    }

    @Test
    fun `ready carries today's schedule, last night and the habits with the plan first`() = runTest {
        insights.mutableState.value = SampleData.readyState()

        val state = viewModel().uiState.first { it is EnergyUiState.Ready } as EnergyUiState.Ready

        val expected = (SampleData.readyState() as InsightsState.Ready).insights
        assertThat(state.today).isEqualTo(SampleData.today)
        assertThat(state.schedule).isEqualTo(expected.schedule)
        assertThat(state.energyPotential).isEqualTo(expected.energyPotential)
        assertThat(state.status).isEqualTo(expected.status)
        assertThat(state.lastNight?.wakeDate).isEqualTo(SampleData.today)
        assertThat(state.lastNightState).isEqualTo(LastNightState.AVAILABLE)
        assertThat(state.sleepNeed).isEqualTo(Duration.ofHours(8))
        assertThat(state.habits).hasSize(HabitType.entries.size)
        val firstOff = state.habits.indexOfFirst { !it.enabled }
        assertThat(state.habits.drop(firstOff).none { it.enabled }).isTrue()
        assertThat(state.habits.filter { it.enabled }.map { it.time }).isInOrder()
        assertThat(state.nextHabit?.type).isEqualTo(expected.nextHabit?.type)
        assertThat(state.nights.map { it.wakeDate }).containsExactly(SampleData.today.minusDays(1), SampleData.today)
    }

    @Test
    fun `the now-line follows the minute clock`() = runTest {
        insights.mutableState.value = SampleData.readyState()
        val viewModel = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        time.ticks.value = SampleData.now + Duration.ofMinutes(1)

        val state = viewModel.uiState.value as EnergyUiState.Ready
        assertThat(state.now).isEqualTo(SampleData.now + Duration.ofMinutes(1))
    }

    @Test
    fun `a night that synced after the insights is drawn right away`() = runTest {
        // Insights still think last night is missing, but the sleep repository already has it.
        insights.mutableState.value = SampleData.readyState(nights = SampleData.nights(includeLastNight = false))

        val state = viewModel().uiState.first { it is EnergyUiState.Ready } as EnergyUiState.Ready

        assertThat(state.lastNight?.wakeDate).isEqualTo(SampleData.today)
        assertThat(state.lastNightState).isEqualTo(LastNightState.AVAILABLE)
    }

    @Test
    fun `while waiting for last night there is no main night`() = runTest {
        val nights = SampleData.nights(includeLastNight = false)
        val now = EnergyTestData.at(8, 40)
        sleep.nightsState.value = nights
        time.ticks.value = now
        insights.mutableState.value = SampleData.readyState(now = now, nights = nights)

        val state = viewModel().uiState.first { it is EnergyUiState.Ready } as EnergyUiState.Ready

        assertThat(state.lastNight).isNull()
        assertThat(state.lastNightState).isEqualTo(LastNightState.WAITING)
    }

    @Test
    fun `the sleep need comes from settings`() = runTest {
        insights.mutableState.value = SampleData.readyState()
        settings.setSleepNeed(Duration.ofMinutes(8 * 60 + 30))

        val state = viewModel().uiState.first { it is EnergyUiState.Ready } as EnergyUiState.Ready

        assertThat(state.sleepNeed).isEqualTo(Duration.ofMinutes(8 * 60 + 30))
    }

    @Test
    fun `adding awake time goes to the sleep repository`() = runTest {
        val start = Instant.parse("2026-10-06T07:10:00Z")

        viewModel().addAwake(SampleData.today, start, start + Duration.ofMinutes(15))

        assertThat(sleep.addedAwake).containsExactly(Triple(SampleData.today, start, start + Duration.ofMinutes(15)))
    }

    @Test
    fun `an empty awake stretch is ignored`() = runTest {
        val start = Instant.parse("2026-10-06T07:10:00Z")

        viewModel().addAwake(SampleData.today, start, start)

        assertThat(sleep.addedAwake).isEmpty()
    }

    @Test
    fun `the bell toggles the habit's reminder`() = runTest {
        viewModel().setReminder(HabitType.CAFFEINE_CUTOFF, false)

        assertThat(habits.settingsState.value.single { it.type == HabitType.CAFFEINE_CUTOFF }.reminderEnabled).isFalse()
    }

    @Test
    fun `sync now opens Garmin Connect and syncs once the screen resumes`() = runTest {
        val viewModel = viewModel()

        assertThat(viewModel.syncNowIntent()).isNotNull()
        assertThat(sync.syncCalls).isEmpty()

        viewModel.onResume()
        viewModel.onResume()
        assertThat(sync.syncCalls).containsExactly(SyncTrigger.MANUAL)
    }

    @Test
    fun `sync now syncs right away when Garmin isn't the source`() = runTest {
        settings.state.value = settings.state.value.let { it.copy(sleep = it.sleep.copy(source = SourceSelection.Single("com.example.other"))) }
        val viewModel = viewModel()

        assertThat(viewModel.syncNowIntent()).isNull()
        assertThat(sync.syncCalls).containsExactly(SyncTrigger.MANUAL)
    }

    @Test
    fun `if Garmin Connect can't be opened it syncs directly`() = runTest {
        val viewModel = viewModel()
        viewModel.syncNowIntent()

        viewModel.onExternalLaunchFailed()
        viewModel.onResume()
        assertThat(sync.syncCalls).containsExactly(SyncTrigger.MANUAL)
    }
}
