package com.sleepsaver.feature.sleep

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.healthconnect.HealthConnectStatus
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.SyncTrigger
import com.sleepsaver.core.domain.model.Confidence
import com.sleepsaver.core.domain.model.DailyInsights
import com.sleepsaver.core.domain.model.LastNightState
import com.sleepsaver.core.domain.model.SleepNeedEstimate
import com.sleepsaver.core.domain.model.SleepNeedEstimateResult
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.testing.FakeDemoModeController
import com.sleepsaver.core.testing.FakeHabitsRepository
import com.sleepsaver.core.testing.FakeHealthConnectGateway
import com.sleepsaver.core.testing.FakeInsightsRepository
import com.sleepsaver.core.testing.FakeNightController
import com.sleepsaver.core.testing.FakeNightStepsRepository
import com.sleepsaver.core.testing.FakeReviewRepository
import com.sleepsaver.core.testing.FakeScheduleRepository
import com.sleepsaver.core.testing.FakeSettingsRepository
import com.sleepsaver.core.testing.FakeSyncRepository
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.core.data.repository.DemoModeController
import com.sleepsaver.core.data.repository.NightStepsRepository
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.domain.journal.AnchorSource
import com.sleepsaver.core.domain.nightsteps.NightStepsSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class SleepViewModelTest {

    private val insights = FakeInsightsRepository()
    private val sync = FakeSyncRepository()
    private val settings = FakeSettingsRepository(AppSettings(onboardingDone = true))
    private val habits = FakeHabitsRepository()
    private val reviews = FakeReviewRepository()
    private val demo = FakeDemoModeController(settings)
    private val gateway = FakeHealthConnectGateway()
    private val schedule = FakeScheduleRepository(upcoming = listOf(PlanSamples.plannedAlarm()))
    private val steps = FakeNightStepsRepository(tonight = PlanSamples.nightStepsSession())
    private val night = FakeNightController()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        savedState: SavedStateHandle = SavedStateHandle(),
        demoController: DemoModeController = demo,
        scheduleRepository: ScheduleRepository = schedule,
        nightStepsRepository: NightStepsRepository = steps,
        now: Instant = SampleData.now,
    ) = SleepViewModel(
        context = ApplicationProvider.getApplicationContext(),
        savedStateHandle = savedState,
        insightsRepository = insights,
        syncRepository = sync,
        settingsRepository = settings,
        habitsRepository = habits,
        reviewRepository = reviews,
        demoModeController = demoController,
        healthConnect = gateway,
        scheduleRepository = scheduleRepository,
        nightStepsRepository = nightStepsRepository,
        nightController = night,
        timeProvider = FixedTime(now),
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    /** uiState is WhileSubscribed: keep a collector running for the test's duration. */
    private fun TestScope.started(
        savedState: SavedStateHandle = SavedStateHandle(),
        demoController: DemoModeController = demo,
        scheduleRepository: ScheduleRepository = schedule,
        nightStepsRepository: NightStepsRepository = steps,
    ): SleepViewModel = viewModel(savedState, demoController, scheduleRepository, nightStepsRepository).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    private fun SleepViewModel.ready(): SleepUiState.Ready = uiState.value as SleepUiState.Ready

    private fun at(hour: Int, minute: Int): Instant = ZonedDateTime.of(SampleData.today, LocalTime.of(hour, minute), SampleData.zone).toInstant()

    private fun ready(insights: DailyInsights) = InsightsState.Ready(insights)

    // ---------------------------------------------------------------- Roll-up

    @Test
    fun `loading until insights arrive`() = runTest {
        val vm = started()

        assertThat(vm.uiState.value).isEqualTo(SleepUiState.Loading)
    }

    @Test
    fun `a normal day rolls the sample insights into the ready state`() = runTest {
        val sample = SampleData.insights()
        insights.mutableState.value = ready(sample)

        val state = started().ready()

        assertThat(state.today).isEqualTo(SampleData.today)
        assertThat(state.banner).isNull()
        assertThat(state.debt).isEqualTo(sample.debt.debt)
        assertThat(state.status).isEqualTo(sample.status)
        assertThat(state.debtIsFromYesterday).isFalse()
        assertThat(state.debtChange).isEqualTo(sample.debtChange)
        assertThat(state.streakDays).isEqualTo(sample.streak.days)
        assertThat(state.lastNightSleep).isEqualTo(sample.lastNight!!.mainSleep)
        assertThat(state.energyPotential).isEqualTo(sample.energyPotential)
        assertThat(state.source).isEqualTo(SourceLabel.Garmin)
        assertThat(state.syncOpensGarmin).isTrue()
        // The current phase leads, highlighted, followed by the rest of today's phases.
        assertThat(state.phases.first().phase).isEqualTo(sample.currentPhase)
        assertThat(state.phases.first().isCurrent).isTrue()
        assertThat(state.phases.drop(1).map { it.phase }).isEqualTo(sample.upcomingPhases)
        assertThat(state.phasesAreTonight).isFalse()
        assertThat(state.nextHabit?.type).isEqualTo(sample.nextHabit?.type)
        assertThat(state.missingNights).doesNotContain(SampleData.today)
    }

    @Test
    fun `sync status and last sync time come from the sync repository`() = runTest {
        insights.mutableState.value = SampleData.readyState()
        val vm = started()

        sync.mutableState.value = sync.mutableState.value.copy(
            status = com.sleepsaver.core.data.repository.SyncStatus.Syncing,
            lastSuccessAt = at(9, 42),
        )

        assertThat(vm.ready().isSyncing).isTrue()
        assertThat(vm.ready().lastSyncAt).isEqualTo(at(9, 42))
    }

    @Test
    fun `source labels follow settings`() = runTest {
        insights.mutableState.value = SampleData.readyState()
        val vm = started()

        settings.setSource(SourceSelection.All)
        assertThat(vm.ready().source).isEqualTo(SourceLabel.AllSources)
        assertThat(vm.ready().syncOpensGarmin).isTrue()

        settings.setSource(SourceSelection.Single("com.fitbit.FitbitMobile"))
        assertThat(vm.ready().source).isEqualTo(SourceLabel.App("Fitbit"))
        assertThat(vm.ready().syncOpensGarmin).isFalse()

        settings.setDemoModeFlag(true, Instant.EPOCH)
        assertThat(vm.ready().source).isEqualTo(SourceLabel.Demo)
        assertThat(vm.ready().syncOpensGarmin).isFalse()
    }

    @Test
    fun `unknown apps get a readable fallback name`() {
        assertThat(fallbackAppLabel("com.example.sleeptracker")).isEqualTo("Sleeptracker")
        assertThat(fallbackAppLabel("com.ouraring.oura")).isEqualTo("Oura")
    }

    // ---------------------------------------------------------------- Last night

    @Test
    fun `waiting for last night shows the banner, yesterday's debt and starts retries once`() = runTest {
        val nights = SampleData.nights(includeLastNight = false)
        val waiting = SampleData.insights(nights = nights)
        assertThat(waiting.lastNightState).isEqualTo(LastNightState.WAITING)
        insights.mutableState.value = ready(waiting)

        val vm = started()

        assertThat(vm.ready().banner).isEqualTo(LastNightBanner.WAITING)
        assertThat(vm.ready().debtIsFromYesterday).isTrue()
        assertThat(vm.ready().lastNightSleep).isNull()
        assertThat(sync.retriesStarted).isEqualTo(1)

        // The once-a-minute recompute doesn't restart the chain.
        insights.mutableState.value = ready(SampleData.insights(now = at(10, 21), nights = nights))
        assertThat(sync.retriesStarted).isEqualTo(1)
    }

    @Test
    fun `missing last night offers to add it`() = runTest {
        val missing = SampleData.insights(now = at(15, 0), nights = SampleData.nights(includeLastNight = false))
        assertThat(missing.lastNightState).isEqualTo(LastNightState.MISSING)
        insights.mutableState.value = ready(missing)

        val vm = started()

        assertThat(vm.ready().banner).isEqualTo(LastNightBanner.MISSING)
        assertThat(vm.ready().missingNights).doesNotContain(SampleData.today)
        assertThat(sync.retriesStarted).isEqualTo(0)
    }

    @Test
    fun `a pending morning review is offered once last night is in`() = runTest {
        insights.mutableState.value = SampleData.readyState()
        val vm = started()

        reviews.pending.value = SampleData.today

        assertThat(vm.ready().banner).isEqualTo(LastNightBanner.REVIEW_READY)
    }

    // ---------------------------------------------------------------- No nights

    @Test
    fun `no nights without Health Connect asks to install it`() = runTest {
        gateway.status = HealthConnectStatus.NOT_INSTALLED
        insights.mutableState.value = InsightsState.NoNights

        val state = started().uiState.value as SleepUiState.Empty

        assertThat(state.reason).isEqualTo(EmptyReason.INSTALL_HEALTH_CONNECT)
        assertThat(state.today).isEqualTo(SampleData.today)
    }

    @Test
    fun `no nights picks the fix-it state from Health Connect`() = runTest {
        insights.mutableState.value = InsightsState.NoNights
        val cases = listOf(
            Triple(HealthConnectStatus.UPDATE_REQUIRED, FakeHealthConnectGateway.ALL_GRANTED, EmptyReason.UPDATE_HEALTH_CONNECT),
            Triple(HealthConnectStatus.NOT_SUPPORTED, FakeHealthConnectGateway.ALL_GRANTED, EmptyReason.NOT_SUPPORTED),
            Triple(HealthConnectStatus.AVAILABLE, FakeHealthConnectGateway.NONE_GRANTED, EmptyReason.PERMISSION_DENIED),
            Triple(HealthConnectStatus.AVAILABLE, FakeHealthConnectGateway.ALL_GRANTED, EmptyReason.NO_DATA),
        )
        for ((status, permissions, expected) in cases) {
            gateway.status = status
            gateway.permissions = permissions
            val state = started().uiState.value as SleepUiState.Empty
            assertThat(state.reason).isEqualTo(expected)
        }
    }

    @Test
    fun `demo mode with no nights never asks for Health Connect`() = runTest {
        gateway.status = HealthConnectStatus.NOT_INSTALLED
        settings.setDemoModeFlag(true, Instant.EPOCH)
        insights.mutableState.value = InsightsState.NoNights

        val state = started().uiState.value as SleepUiState.Empty

        assertThat(state.reason).isEqualTo(EmptyReason.NO_DATA)
        assertThat(state.syncOpensGarmin).isFalse()
        assertThat(state.canTryDemo).isFalse()
    }

    @Test
    fun `Health Connect is checked again on resume`() = runTest {
        gateway.status = HealthConnectStatus.NOT_INSTALLED
        insights.mutableState.value = InsightsState.NoNights
        val vm = started()
        assertThat((vm.uiState.value as SleepUiState.Empty).reason).isEqualTo(EmptyReason.INSTALL_HEALTH_CONNECT)

        gateway.status = HealthConnectStatus.AVAILABLE
        gateway.permissions = FakeHealthConnectGateway.NONE_GRANTED
        vm.onResume()
        assertThat((vm.uiState.value as SleepUiState.Empty).reason).isEqualTo(EmptyReason.PERMISSION_DENIED)

        gateway.permissions = FakeHealthConnectGateway.ALL_GRANTED
        vm.onResume()
        assertThat((vm.uiState.value as SleepUiState.Empty).reason).isEqualTo(EmptyReason.NO_DATA)
    }

    @Test
    fun `try demo data switches demo mode on`() = runTest {
        insights.mutableState.value = InsightsState.NoNights
        val vm = started()
        assertThat((vm.uiState.value as SleepUiState.Empty).canTryDemo).isTrue()

        vm.onTryDemo()

        assertThat(demo.calls).containsExactly(true)
        assertThat(settings.state.value.demoMode).isTrue()
    }

    @Test
    fun `the screen shows loading while the demo nights are written`() = runTest {
        val written = CompletableDeferred<Unit>()
        val slowDemo = object : DemoModeController {
            override suspend fun setDemoMode(enabled: Boolean) {
                settings.setDemoModeFlag(enabled, Instant.EPOCH)
                written.await()
            }
        }
        insights.mutableState.value = InsightsState.NoNights
        val vm = started(demoController = slowDemo)

        vm.onTryDemo()
        assertThat(vm.uiState.value).isEqualTo(SleepUiState.Loading)

        insights.mutableState.value = SampleData.readyState()
        written.complete(Unit)
        assertThat(vm.ready().source).isEqualTo(SourceLabel.Demo)
    }

    // ---------------------------------------------------------------- Sleep need suggestion

    private fun withSuggestion(need: Duration = Duration.ofMinutes(8 * 60 + 35)): DailyInsights =
        SampleData.insights().copy(
            needEstimate = SleepNeedEstimateResult.Available(
                SleepNeedEstimate(
                    need = need,
                    freeNightsUsed = 9,
                    interquartileRange = Duration.ofMinutes(30),
                    confidence = Confidence.HIGH,
                    typicalWeekdayWake = LocalTime.of(6, 50),
                ),
            ),
        )

    @Test
    fun `a sleep need estimate far from the current need is suggested`() = runTest {
        insights.mutableState.value = ready(withSuggestion())

        val suggestion = started().ready().needSuggestion

        assertThat(suggestion).isEqualTo(NeedSuggestion(Duration.ofMinutes(515), Confidence.HIGH, 9))
    }

    @Test
    fun `an estimate close to the current need is not suggested`() = runTest {
        insights.mutableState.value = ready(withSuggestion(need = Duration.ofMinutes(8 * 60 + 10)))

        assertThat(started().ready().needSuggestion).isNull()
    }

    @Test
    fun `accepting the suggestion sets the sleep need and hides the card`() = runTest {
        insights.mutableState.value = ready(withSuggestion())
        val vm = started()

        vm.onUseSuggestedNeed()

        assertThat(settings.state.value.sleep.sleepNeed).isEqualTo(Duration.ofMinutes(515))
        assertThat(vm.ready().needSuggestion).isNull()
    }

    @Test
    fun `dismissing the suggestion records it and hides the card`() = runTest {
        val sample = withSuggestion()
        insights.mutableState.value = ready(sample)
        val vm = started()

        vm.onDismissNeedSuggestion()

        assertThat(settings.state.value.needSuggestionDismissedValue).isEqualTo(Duration.ofMinutes(515))
        assertThat(settings.state.value.needSuggestionDismissedAt).isEqualTo(sample.now)
        assertThat(settings.state.value.sleep.sleepNeed).isEqualTo(Duration.ofHours(8))
        assertThat(vm.ready().needSuggestion).isNull()
    }

    // ---------------------------------------------------------------- Habits

    @Test
    fun `the bell toggles the next habit's reminder`() = runTest {
        insights.mutableState.value = SampleData.readyState()
        val vm = started()
        val next = vm.ready().nextHabit!!
        assertThat(next.reminderOn).isTrue()

        vm.onReminderChange(next.type, false)

        assertThat(habits.settingsState.value.first { it.type == next.type }.reminderEnabled).isFalse()
        assertThat(vm.ready().nextHabit!!.reminderOn).isFalse()
    }

    // ---------------------------------------------------------------- Tonight

    @Test
    fun `the tonight row has tonight's Night steps and tomorrow's alarm`() = runTest {
        insights.mutableState.value = SampleData.readyState()
        val vm = started()

        val tonight = vm.ready().tonight
        assertThat(tonight?.stepsFrom).isEqualTo(PlanSamples.nightStepsSession().windowStart)
        assertThat(tonight?.alarm).isEqualTo(LocalTime.of(6, 45))
        assertThat(tonight?.surfacing).isTrue()

        // Day off tomorrow: the planner drops the alarm, and the row follows.
        schedule.upcomingState.value = emptyList()
        assertThat(vm.ready().tonight?.alarm).isNull()

        // The evening's steps switched off.
        steps.tonightState.value = PlanSamples.nightStepsSession(enabled = false)
        assertThat(vm.ready().tonight?.stepsFrom).isNull()
    }

    @Test
    fun `night mode follows the night state's anchor, so I'm up ends it at once`() = runTest {
        insights.mutableState.value = ready(SampleData.insights(now = at(21, 10)))
        val vm = started()
        assertThat(vm.ready().nightMode).isFalse()

        night.clock = { at(21, 5) }
        night.dropAnchor(AnchorSource.TONIGHT, wholeNightLog = false)
        assertThat(vm.ready().nightMode).isTrue()

        night.endNight()
        assertThat(vm.ready().nightMode).isFalse()
    }

    @Test
    fun `the home screen still loads when tonight's alarm or steps can't be read`() = runTest {
        val failingSchedule = object : ScheduleRepository by schedule {
            override val upcomingAlarms: Flow<List<PlannedAlarm>> = flow { throw IOException("disk") }
        }
        val failingSteps = object : NightStepsRepository by steps {
            override val tonight: Flow<NightStepsSession?> = flow { throw IOException("disk") }
        }
        insights.mutableState.value = SampleData.readyState()

        val state = started(scheduleRepository = failingSchedule, nightStepsRepository = failingSteps).ready()

        assertThat(state.tonight).isEqualTo(TonightSummary(stepsFrom = null, alarm = null))
    }

    // ---------------------------------------------------------------- Sync

    @Test
    fun `sync now opens Garmin Connect and syncs when the screen resumes`() = runTest {
        insights.mutableState.value = ready(SampleData.insights(nights = SampleData.nights(includeLastNight = false)))
        val vm = started()

        val intent = vm.syncNowIntent()

        assertThat(intent).isNotNull()
        assertThat(sync.syncCalls).isEmpty()

        vm.onResume()
        assertThat(sync.syncCalls).containsExactly(SyncTrigger.MANUAL)

        // Only once.
        vm.onResume()
        assertThat(sync.syncCalls).containsExactly(SyncTrigger.MANUAL)
    }

    @Test
    fun `a pending sync survives the process being killed while Garmin Connect is open`() = runTest {
        insights.mutableState.value = ready(SampleData.insights(nights = SampleData.nights(includeLastNight = false)))
        val savedState = SavedStateHandle()
        started(savedState).syncNowIntent()

        // A new ViewModel restored from the same saved state.
        val restored = started(savedState)
        restored.onResume()

        assertThat(sync.syncCalls).containsExactly(SyncTrigger.MANUAL)
    }

    @Test
    fun `sync now falls back to the store page without Garmin Connect`() = runTest {
        gateway.garminInstalled = false
        insights.mutableState.value = InsightsState.NoNights
        val vm = started()

        assertThat(vm.syncNowIntent()).isNotNull()
    }

    @Test
    fun `sync now syncs at once when Garmin Connect can't be opened`() = runTest {
        insights.mutableState.value = SampleData.readyState()
        val vm = started()

        vm.syncNowIntent()
        vm.onExternalLaunchFailed()
        assertThat(sync.syncCalls).containsExactly(SyncTrigger.MANUAL)

        vm.onResume()
        assertThat(sync.syncCalls).hasSize(1)
    }

    @Test
    fun `sync now with another app as the source syncs directly`() = runTest {
        settings.setSource(SourceSelection.Single("com.fitbit.FitbitMobile"))
        insights.mutableState.value = SampleData.readyState()
        val vm = started()

        assertThat(vm.syncNowIntent()).isNull()
        assertThat(sync.syncCalls).containsExactly(SyncTrigger.MANUAL)
    }

    @Test
    fun `pull to refresh runs a manual sync`() = runTest {
        insights.mutableState.value = SampleData.readyState()
        val vm = started()

        vm.onRefresh()

        assertThat(sync.syncCalls).containsExactly(SyncTrigger.MANUAL)
        assertThat(vm.ready().isRefreshing).isFalse()
    }

    @Test
    fun `resuming without a pending sync doesn't sync`() = runTest {
        insights.mutableState.value = SampleData.readyState()
        val vm = started()

        vm.onResume()

        assertThat(sync.syncCalls).isEmpty()
    }

    private class FixedTime(
        private val instant: Instant,
        private val zoneId: ZoneId = SampleData.zone,
    ) : TimeProvider {
        override fun now(): Instant = instant
        override fun zone(): ZoneId = zoneId
        override val minuteTicks: Flow<Instant> = flowOf(instant)
    }
}
