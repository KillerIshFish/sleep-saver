package com.sleepsaver.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.healthconnect.HealthConnectStatus
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.data.repository.SyncRepository
import com.sleepsaver.core.data.repository.SyncResult
import com.sleepsaver.core.data.repository.SyncTrigger
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.habits.HabitCatalog
import com.sleepsaver.core.domain.model.Confidence
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.SleepNeedEstimate
import com.sleepsaver.core.domain.model.SleepNeedEstimateResult
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.testing.FakeDemoModeController
import com.sleepsaver.core.testing.FakeHabitsRepository
import com.sleepsaver.core.testing.FakeHealthConnectGateway
import com.sleepsaver.core.testing.FakeInsightsRepository
import com.sleepsaver.core.testing.FakeSettingsRepository
import com.sleepsaver.core.testing.FakeSleepRepository
import com.sleepsaver.core.testing.FakeSyncRepository
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.feature.onboarding.connect.ConnectStage
import com.sleepsaver.feature.onboarding.connect.SourceCheck
import com.sleepsaver.feature.onboarding.testing.FakeTimeProvider
import com.sleepsaver.feature.onboarding.testing.GatedSyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class OnboardingViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val fitbit = "com.fitbit.FitbitMobile"

    private class Env(
        val gateway: FakeHealthConnectGateway = FakeHealthConnectGateway(),
        val settings: FakeSettingsRepository = FakeSettingsRepository(),
        val sync: SyncRepository = FakeSyncRepository(),
        val sleep: FakeSleepRepository = FakeSleepRepository(),
        val insights: FakeInsightsRepository = FakeInsightsRepository(InsightsState.NoNights),
        val habits: FakeHabitsRepository = FakeHabitsRepository(),
        val handle: SavedStateHandle = SavedStateHandle(),
    ) {
        val demo = FakeDemoModeController(settings)
        val time = FakeTimeProvider()
        val fakeSync get() = sync as FakeSyncRepository
        val viewModel by lazy { OnboardingViewModel(gateway, settings, sync, sleep, insights, habits, demo, time, handle) }
        val state get() = viewModel.uiState.value
    }

    // ------------------------------------------------------------ navigation

    @Test
    fun `starts on welcome and get started moves to connect`() {
        val env = Env()
        assertThat(env.state.step).isEqualTo(OnboardingStep.WELCOME)

        env.viewModel.start()

        assertThat(env.state.step).isEqualTo(OnboardingStep.CONNECT)
    }

    @Test
    fun `back steps back and stops at welcome`() {
        val env = Env()
        env.viewModel.start()

        assertThat(env.viewModel.back()).isTrue()
        assertThat(env.state.step).isEqualTo(OnboardingStep.WELCOME)
        assertThat(env.viewModel.back()).isFalse()
        assertThat(env.state.step).isEqualTo(OnboardingStep.WELCOME)
    }

    @Test
    fun `the current step survives process death`() {
        val env = Env(handle = SavedStateHandle(mapOf("onboarding_step" to "SLEEP_NEED")))

        assertThat(env.state.step).isEqualTo(OnboardingStep.SLEEP_NEED)
    }

    @Test
    fun `choices and the finished import survive process death`() {
        val handle = SavedStateHandle()
        val env = Env(handle = handle)
        env.sleep.nightsState.value = SampleData.nights()
        env.viewModel.start()
        env.viewModel.continueFromConnect()
        env.viewModel.continueFromImport()
        env.viewModel.setNeed(505)
        env.viewModel.setWake(LocalTime.of(6, 40))
        env.viewModel.toggleHabit(HabitType.NAP_WINDOW)
        env.viewModel.setRemind(false)

        val restored = Env(handle = SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        restored.sleep.nightsState.value = SampleData.nights()

        assertThat(restored.state.step).isEqualTo(OnboardingStep.SLEEP_NEED)
        assertThat(restored.state.sleepNeed.needMinutes).isEqualTo(505)
        assertThat(restored.state.sleepNeed.wake).isEqualTo(LocalTime.of(6, 40))
        assertThat(restored.state.habits.options.first { it.type == HabitType.NAP_WINDOW }.selected).isTrue()
        assertThat(restored.state.habits.remind).isFalse()
        restored.viewModel.back()
        assertThat(restored.state.import).isInstanceOf(ImportUiState.Done::class.java)
        assertThat(restored.fakeSync.importCalls).isEqualTo(0)
    }

    @Test
    fun `restored on the import step, the import runs again`() {
        val env = Env(handle = SavedStateHandle(mapOf("onboarding_step" to "IMPORT")))

        assertThat(env.state.import).isInstanceOf(ImportUiState.Done::class.java)
        assertThat(env.fakeSync.importCalls).isEqualTo(1)
    }

    @Test
    fun `restored on the import step in demo mode, nothing is imported`() {
        val env = Env(
            settings = FakeSettingsRepository(AppSettings(demoMode = true)),
            handle = SavedStateHandle(mapOf("onboarding_step" to "IMPORT")),
        )

        assertThat(env.state.import).isInstanceOf(ImportUiState.Demo::class.java)
        assertThat(env.fakeSync.importCalls).isEqualTo(0)
    }

    @Test
    fun `going back onto an import that never ran in this process runs it instead of spinning`() {
        val env = Env(handle = SavedStateHandle(mapOf("onboarding_step" to "SLEEP_NEED")))

        env.viewModel.back()

        assertThat(env.state.step).isEqualTo(OnboardingStep.IMPORT)
        assertThat(env.state.import).isInstanceOf(ImportUiState.Done::class.java)
        assertThat(env.fakeSync.importCalls).isEqualTo(1)
    }

    @Test
    fun `the demo path finishes without touching Health Connect`() {
        val env = Env(gateway = FakeHealthConnectGateway(status = HealthConnectStatus.NOT_SUPPORTED))
        env.viewModel.start()

        env.viewModel.exploreDemo()
        env.viewModel.continueFromImport()
        env.viewModel.continueFromSleepNeed()
        env.viewModel.finish()

        assertThat(env.state.finished).isTrue()
        assertThat(env.settings.state.value.onboardingDone).isTrue()
        assertThat(env.settings.state.value.demoMode).isTrue()
        assertThat(env.fakeSync.importCalls).isEqualTo(0)
        assertThat(env.fakeSync.syncCalls).isEmpty()
    }

    // ------------------------------------------------------------ Health Connect status

    @Test
    fun `missing Health Connect offers install`() {
        val env = Env(gateway = FakeHealthConnectGateway(status = HealthConnectStatus.NOT_INSTALLED))
        env.viewModel.start()

        assertThat(env.state.connection.stage).isEqualTo(ConnectStage.NOT_INSTALLED)
        assertThat(env.state.connection.permissions).isNull()
        assertThat(env.viewModel.installOrUpdateIntent()).isNotNull()
    }

    @Test
    fun `old Health Connect asks for an update`() {
        val env = Env(gateway = FakeHealthConnectGateway(status = HealthConnectStatus.UPDATE_REQUIRED))
        env.viewModel.start()

        assertThat(env.state.connection.stage).isEqualTo(ConnectStage.UPDATE_REQUIRED)
    }

    @Test
    fun `unsupported phone leaves only demo data`() {
        val env = Env(gateway = FakeHealthConnectGateway(status = HealthConnectStatus.NOT_SUPPORTED))
        env.viewModel.start()

        assertThat(env.state.connection.stage).isEqualTo(ConnectStage.NOT_SUPPORTED)
        env.viewModel.continueFromConnect()
        assertThat(env.state.step).isEqualTo(OnboardingStep.CONNECT)
    }

    @Test
    fun `installing Health Connect is picked up on resume`() {
        val env = Env(gateway = FakeHealthConnectGateway(status = HealthConnectStatus.NOT_INSTALLED, permissions = FakeHealthConnectGateway.NONE_GRANTED))
        env.viewModel.start()

        env.gateway.status = HealthConnectStatus.AVAILABLE
        env.viewModel.onResume()

        assertThat(env.state.connection.stage).isEqualTo(ConnectStage.NEEDS_PERMISSION)
    }

    @Test
    fun `a broken gateway is a state, not a crash`() {
        val gateway = object : com.sleepsaver.core.data.healthconnect.HealthConnectGateway by FakeHealthConnectGateway() {
            override fun status(): HealthConnectStatus = throw IllegalStateException("boom")
        }
        val settings = FakeSettingsRepository()
        val viewModel = OnboardingViewModel(
            gateway, settings, FakeSyncRepository(), FakeSleepRepository(), FakeInsightsRepository(InsightsState.NoNights),
            FakeHabitsRepository(), FakeDemoModeController(settings), FakeTimeProvider(), SavedStateHandle(),
        )
        viewModel.start()

        assertThat(viewModel.uiState.value.connection.stage).isEqualTo(ConnectStage.NOT_INSTALLED)
    }

    // ------------------------------------------------------------ permissions

    @Test
    fun `without sleep access we ask first`() {
        val env = Env(gateway = FakeHealthConnectGateway(permissions = FakeHealthConnectGateway.NONE_GRANTED))
        env.viewModel.start()

        assertThat(env.state.connection.stage).isEqualTo(ConnectStage.NEEDS_PERMISSION)
        assertThat(env.state.connection.source).isEqualTo(SourceCheck.NotChecked)
    }

    @Test
    fun `refusing sleep access shows the denied state`() {
        val env = Env(gateway = FakeHealthConnectGateway(permissions = FakeHealthConnectGateway.NONE_GRANTED))
        env.viewModel.start()

        env.viewModel.onPermissionsResult()

        assertThat(env.state.connection.stage).isEqualTo(ConnectStage.DENIED)
    }

    @Test
    fun `a permission screen that can't open counts as a refusal`() {
        val env = Env(gateway = FakeHealthConnectGateway(permissions = FakeHealthConnectGateway.NONE_GRANTED))
        env.viewModel.start()

        env.viewModel.onPermissionsUnavailable()

        assertThat(env.state.connection.stage).isEqualTo(ConnectStage.DENIED)
    }

    @Test
    fun `granting sleep access connects and finds Garmin`() {
        val env = Env(gateway = FakeHealthConnectGateway(permissions = FakeHealthConnectGateway.NONE_GRANTED))
        env.viewModel.start()

        env.gateway.permissions = FakeHealthConnectGateway.ALL_GRANTED
        env.viewModel.onPermissionsResult()

        assertThat(env.state.connection.stage).isEqualTo(ConnectStage.CONNECTED)
        assertThat(env.state.connection.source).isEqualTo(SourceCheck.Sharing(SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE)))
    }

    @Test
    fun `permissions to request come from the gateway`() = kotlinx.coroutines.test.runTest {
        val env = Env()

        assertThat(env.viewModel.permissionsToRequest()).containsExactly(FakeHealthConnectGateway.SLEEP_PERMISSION)
    }

    // ------------------------------------------------------------ Garmin verification

    @Test
    fun `no Garmin sleep lists the other apps that have some`() {
        val env = Env(gateway = FakeHealthConnectGateway(origins = listOf(fitbit)))
        env.viewModel.start()

        assertThat(env.state.connection.source).isEqualTo(
            SourceCheck.Missing(SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE), listOf(fitbit)),
        )
    }

    @Test
    fun `no sleep from anyone has no alternatives`() {
        val env = Env(gateway = FakeHealthConnectGateway(origins = emptyList()))
        env.viewModel.start()

        assertThat(env.state.connection.source).isEqualTo(
            SourceCheck.Missing(SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE), emptyList()),
        )
    }

    @Test
    fun `check again finds Garmin once it shares`() {
        val env = Env(gateway = FakeHealthConnectGateway(origins = emptyList()))
        env.viewModel.start()

        env.gateway.origins = listOf(SleepConfig.GARMIN_CONNECT_PACKAGE, fitbit)
        env.viewModel.checkAgain()

        assertThat(env.state.connection.source).isInstanceOf(SourceCheck.Sharing::class.java)
    }

    @Test
    fun `using another app switches the source`() {
        val env = Env(gateway = FakeHealthConnectGateway(origins = listOf(fitbit)))
        env.viewModel.start()

        env.viewModel.useSource(SourceSelection.Single(fitbit))

        assertThat(env.settings.state.value.sleep.source).isEqualTo(SourceSelection.Single(fitbit))
        assertThat(env.state.connection.source).isEqualTo(SourceCheck.Sharing(SourceSelection.Single(fitbit)))
    }

    @Test
    fun `using all sources counts any app`() {
        val env = Env(gateway = FakeHealthConnectGateway(origins = listOf(fitbit)))
        env.viewModel.start()

        env.viewModel.useSource(SourceSelection.All)

        assertThat(env.settings.state.value.sleep.source).isEqualTo(SourceSelection.All)
        assertThat(env.state.connection.source).isEqualTo(SourceCheck.Sharing(SourceSelection.All))
    }

    @Test
    fun `Garmin Connect opens itself when installed, with its store page as the fallback`() {
        val env = Env()
        assertThat(env.viewModel.garminConnectIntents()).hasSize(2)

        env.gateway.garminInstalled = false
        assertThat(env.viewModel.garminConnectIntents()).hasSize(1)
    }

    // ------------------------------------------------------------ demo

    @Test
    fun `demo data skips the import`() {
        val env = Env()
        env.sleep.nightsState.value = SampleData.nights()
        env.viewModel.start()

        env.viewModel.exploreDemo()

        assertThat(env.demo.calls).containsExactly(true)
        assertThat(env.settings.state.value.demoMode).isTrue()
        assertThat(env.state.step).isEqualTo(OnboardingStep.IMPORT)
        assertThat(env.state.import).isInstanceOf(ImportUiState.Demo::class.java)
        assertThat((env.state.import as ImportUiState.Demo).nights.count).isEqualTo(SampleData.nights().count { it.isPresent })
        assertThat(env.fakeSync.importCalls).isEqualTo(0)
    }

    @Test
    fun `demo works even when Health Connect is missing`() {
        val env = Env(gateway = FakeHealthConnectGateway(status = HealthConnectStatus.NOT_SUPPORTED))
        env.viewModel.start()

        env.viewModel.exploreDemo()

        assertThat(env.state.step).isEqualTo(OnboardingStep.IMPORT)
        env.viewModel.continueFromImport()
        assertThat(env.state.step).isEqualTo(OnboardingStep.SLEEP_NEED)
    }

    @Test
    fun `connecting for real after trying the demo leaves demo mode and imports`() {
        val env = Env()
        env.viewModel.start()
        env.viewModel.exploreDemo()
        env.viewModel.back()

        env.viewModel.continueFromConnect()

        assertThat(env.demo.calls).containsExactly(true, false).inOrder()
        assertThat(env.settings.state.value.demoMode).isFalse()
        assertThat(env.fakeSync.importCalls).isEqualTo(1)
        assertThat(env.state.import).isInstanceOf(ImportUiState.Done::class.java)
    }

    // ------------------------------------------------------------ import

    @Test
    fun `continue imports the history, then syncs, then counts the nights`() {
        val env = Env()
        env.sleep.nightsState.value = SampleData.nights(count = 21)
        env.viewModel.start()

        env.viewModel.continueFromConnect()

        assertThat(env.state.step).isEqualTo(OnboardingStep.IMPORT)
        assertThat(env.fakeSync.importCalls).isEqualTo(1)
        assertThat(env.fakeSync.syncCalls).containsExactly(SyncTrigger.ONBOARDING)
        val done = env.state.import as ImportUiState.Done
        assertThat(done.nights.count).isEqualTo(21)
        assertThat(done.nights.last).isEqualTo(SampleData.today)
        assertThat(done.nights.recent).hasSize(14)
        assertThat(done.nights.recent.last()).isNotNull()
    }

    @Test
    fun `an empty history says nights will come later`() {
        val env = Env()
        env.viewModel.start()

        env.viewModel.continueFromConnect()

        assertThat(env.state.import).isEqualTo(ImportUiState.Done(NightsSummary(recent = List(14) { null })))
    }

    @Test
    fun `import progress follows the sync state`() {
        val sync = GatedSyncRepository()
        val env = Env(sync = sync)
        env.viewModel.start()

        env.viewModel.continueFromConnect()
        assertThat(env.state.import).isEqualTo(ImportUiState.Importing(0f))
        sync.progress(0.5f)
        assertThat(env.state.import).isEqualTo(ImportUiState.Importing(0.5f))
        env.viewModel.continueFromImport()
        assertThat(env.state.step).isEqualTo(OnboardingStep.IMPORT)

        sync.release()

        assertThat(env.state.import).isInstanceOf(ImportUiState.Done::class.java)
        assertThat(sync.importCalls).isEqualTo(1)
    }

    @Test
    fun `a failed import can be retried or skipped`() {
        val env = Env()
        env.fakeSync.nextResult = SyncResult(success = false, error = SyncError.RATE_LIMITED)
        env.viewModel.start()

        env.viewModel.continueFromConnect()
        assertThat(env.state.import).isEqualTo(ImportUiState.Failed(SyncError.RATE_LIMITED))
        assertThat(env.fakeSync.syncCalls).isEmpty()

        env.fakeSync.nextResult = SyncResult(success = true)
        env.viewModel.retryImport()
        assertThat(env.state.import).isInstanceOf(ImportUiState.Done::class.java)
        assertThat(env.fakeSync.importCalls).isEqualTo(2)

        env.viewModel.continueFromImport()
        assertThat(env.state.step).isEqualTo(OnboardingStep.SLEEP_NEED)
    }

    @Test
    fun `coming back to connect doesn't import twice`() {
        val env = Env()
        env.viewModel.start()
        env.viewModel.continueFromConnect()
        env.viewModel.back()

        env.viewModel.continueFromConnect()

        assertThat(env.fakeSync.importCalls).isEqualTo(1)
    }

    @Test
    fun `can't continue without sleep access`() {
        val env = Env(gateway = FakeHealthConnectGateway(permissions = FakeHealthConnectGateway.NONE_GRANTED))
        env.viewModel.start()

        env.viewModel.continueFromConnect()

        assertThat(env.state.step).isEqualTo(OnboardingStep.CONNECT)
        assertThat(env.fakeSync.importCalls).isEqualTo(0)
    }

    // ------------------------------------------------------------ sleep need & wake

    @Test
    fun `defaults are 8 hours and 7 AM without any nights`() {
        val env = Env()

        val need = env.state.sleepNeed
        assertThat(need.needMinutes).isEqualTo(480)
        assertThat(need.wake).isEqualTo(LocalTime.of(7, 0))
        assertThat(need.wakeFromNights).isFalse()
        assertThat(need.suggestion).isNull()
        assertThat(need.date).isEqualTo(SampleData.today)
        assertThat(need.zone).isEqualTo(SampleData.zone)
    }

    @Test
    fun `wake time defaults to the median of recent nights`() {
        val env = Env()
        val nights = SampleData.nights()
        env.sleep.nightsState.value = nights

        assertThat(env.state.sleepNeed.wake).isEqualTo(medianWake(nights))
        assertThat(env.state.sleepNeed.wakeFromNights).isTrue()
    }

    @Test
    fun `the estimate is offered and can be used`() {
        val estimate = SleepNeedEstimate(
            Duration.ofMinutes(515), 6, Duration.ofMinutes(50), Confidence.MEDIUM, LocalTime.of(6, 50),
            freeNightWakeDelay = Duration.ofMinutes(60),
        )
        val insights = SampleData.insights().copy(needEstimate = SleepNeedEstimateResult.Available(estimate))
        val env = Env(insights = FakeInsightsRepository(InsightsState.Ready(insights)))

        assertThat(env.state.sleepNeed.suggestion).isEqualTo(NeedSuggestion(515, Confidence.MEDIUM, 6, wakeDelayMinutes = 60))
        env.viewModel.useSuggestion()

        assertThat(env.state.sleepNeed.needMinutes).isEqualTo(515)
    }

    @Test
    fun `not enough data means no suggestion`() {
        val insights = SampleData.insights().copy(needEstimate = SleepNeedEstimateResult.NotEnoughData(5, 14, 1, 4))
        val env = Env(insights = FakeInsightsRepository(InsightsState.Ready(insights)))

        assertThat(env.state.sleepNeed.suggestion).isNull()
    }

    @Test
    fun `need stays between 6 and 11 hours`() {
        val env = Env()

        env.viewModel.setNeed(300)
        assertThat(env.state.sleepNeed.needMinutes).isEqualTo(360)
        env.viewModel.setNeed(700)
        assertThat(env.state.sleepNeed.needMinutes).isEqualTo(660)
    }

    @Test
    fun `continue saves need and wake time`() {
        val env = Env(handle = SavedStateHandle(mapOf("onboarding_step" to "SLEEP_NEED")))

        env.viewModel.setNeed(505)
        env.viewModel.setWake(LocalTime.of(6, 35))
        env.viewModel.continueFromSleepNeed()

        assertThat(env.settings.state.value.sleep.sleepNeed).isEqualTo(Duration.ofMinutes(505))
        assertThat(env.settings.state.value.sleep.typicalWake).isEqualTo(LocalTime.of(6, 35))
        assertThat(env.state.step).isEqualTo(OnboardingStep.HABITS)
    }

    @Test
    fun `a saved need is shown again`() {
        val saved = AppSettings().let { it.copy(sleep = it.sleep.copy(sleepNeed = Duration.ofMinutes(450))) }
        val env = Env(settings = FakeSettingsRepository(saved))

        assertThat(env.state.sleepNeed.needMinutes).isEqualTo(450)
    }

    // ------------------------------------------------------------ habits & finish

    @Test
    fun `habits start from the catalog defaults with reminders on`() {
        val env = Env()

        val habits = env.state.habits
        val defaults = HabitCatalog().all.filter { it.defaultEnabled }.map { it.type }
        assertThat(habits.options.filter { it.selected }.map { it.type }).containsExactlyElementsIn(defaults)
        assertThat(habits.options).hasSize(HabitType.entries.size)
        assertThat(habits.remind).isTrue()
        assertThat(habits.wantsNotifications).isTrue()
    }

    @Test
    fun `habit times come from the schedule when there is one`() {
        val env = Env(insights = FakeInsightsRepository(SampleData.readyState()))

        assertThat(env.state.habits.options.first { it.type == HabitType.CAFFEINE_CUTOFF }.time).isNotNull()
    }

    @Test
    fun `finish saves habits, marks onboarding done and starts background sync`() {
        val env = Env(handle = SavedStateHandle(mapOf("onboarding_step" to "HABITS")))

        env.viewModel.toggleHabit(HabitType.NAP_WINDOW)
        env.viewModel.toggleHabit(HabitType.CAFFEINE_CUTOFF)
        env.viewModel.setRemind(false)
        assertThat(env.state.habits.wantsNotifications).isFalse()
        env.viewModel.finish()

        val saved = env.habits.settingsState.value.associateBy { it.type }
        assertThat(saved.getValue(HabitType.NAP_WINDOW).enabled).isTrue()
        assertThat(saved.getValue(HabitType.CAFFEINE_CUTOFF).enabled).isFalse()
        assertThat(saved.getValue(HabitType.MORNING_LIGHT).enabled).isTrue()
        assertThat(saved.values.none { it.reminderEnabled }).isTrue()
        assertThat(env.settings.state.value.onboardingDone).isTrue()
        assertThat(env.fakeSync.periodicEnsured).isEqualTo(1)
        assertThat(env.state.finished).isTrue()
    }

    @Test
    fun `finish with reminders on turns them on for every habit`() {
        val env = Env(handle = SavedStateHandle(mapOf("onboarding_step" to "HABITS")))

        env.viewModel.finish()
        env.viewModel.finish()

        assertThat(env.habits.settingsState.value.all { it.reminderEnabled }).isTrue()
        assertThat(env.fakeSync.periodicEnsured).isEqualTo(1)
    }

    @Test
    fun `finish only works on the last step`() {
        val env = Env()

        env.viewModel.finish()

        assertThat(env.settings.state.value.onboardingDone).isFalse()
        assertThat(env.state.finished).isFalse()
    }

    @Test
    fun `the whole flow end to end`() {
        val env = Env()
        env.sleep.nightsState.value = SampleData.nights()

        env.viewModel.start()
        env.viewModel.continueFromConnect()
        env.viewModel.continueFromImport()
        env.viewModel.continueFromSleepNeed()
        env.viewModel.finish()

        assertThat(env.state.finished).isTrue()
        assertThat(env.settings.state.value.onboardingDone).isTrue()
        assertThat(env.settings.state.value.sleep.typicalWake).isEqualTo(medianWake(SampleData.nights()))
    }
}
