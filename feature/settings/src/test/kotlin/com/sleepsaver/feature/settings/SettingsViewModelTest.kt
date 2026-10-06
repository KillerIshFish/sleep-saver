package com.sleepsaver.feature.settings

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.healthconnect.HealthConnectStatus
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.ClockFormat
import com.sleepsaver.core.data.repository.DataResetter
import com.sleepsaver.core.data.repository.DemoModeController
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.data.repository.SyncResult
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.data.repository.SyncStatus
import com.sleepsaver.core.data.repository.SyncTrigger
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Confidence
import com.sleepsaver.core.domain.model.SleepNeedEstimate
import com.sleepsaver.core.domain.model.SleepNeedEstimateResult
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.testing.FakeDataResetter
import com.sleepsaver.core.testing.FakeDemoModeController
import com.sleepsaver.core.testing.FakeExportRepository
import com.sleepsaver.core.testing.FakeHealthConnectGateway
import com.sleepsaver.core.testing.FakeInsightsRepository
import com.sleepsaver.core.testing.FakeSettingsRepository
import com.sleepsaver.core.testing.FakeSleepRepository
import com.sleepsaver.core.testing.FakeSyncRepository
import com.sleepsaver.core.testing.SampleData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Duration
import java.time.LocalTime

class SettingsViewModelTest {

    private val settings = FakeSettingsRepository()
    private val sleep = FakeSleepRepository()
    private val sync = FakeSyncRepository()
    private val insights = FakeInsightsRepository()
    private val export = FakeExportRepository()
    private val gateway = FakeHealthConnectGateway()
    private val platform = FakeSettingsPlatform()
    private val time = FakeTimeProvider()
    private var demo: DemoModeController = FakeDemoModeController(settings)
    private var resetter: DataResetter = FakeDataResetter()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): SettingsViewModel {
        val io = UnconfinedTestDispatcher(testScheduler)
        val viewModel = SettingsViewModel(
            settingsRepository = settings,
            sleepRepository = sleep,
            syncRepository = sync,
            insightsRepository = insights,
            demoModeController = demo,
            exportRepository = export,
            dataResetter = resetter,
            healthConnect = gateway,
            platform = platform,
            timeProvider = time,
            ioDispatcher = io,
        )
        backgroundScope.launch(io) { viewModel.uiState.collect {} }
        return viewModel
    }

    private val SettingsViewModel.state get() = uiState.value
    private val stored get() = settings.state.value.sleep

    // ------------------------------------------------------------------ Initial state

    @Test
    fun `shows the stored settings and the device status`() = runTest {
        settings.state.value = AppSettings(
            sleep = UserSleepSettings(
                sleepNeed = Duration.ofMinutes(470),
                typicalWake = LocalTime.of(6, 30),
                streakThreshold = Duration.ofHours(4),
                lowDebtMax = Duration.ofMinutes(90),
                highDebtMin = Duration.ofHours(4),
            ),
            clockFormat = ClockFormat.H24,
            morningReviewEnabled = false,
        )
        platform.notifications = NotificationAccess.BLOCKED
        sync.mutableState.value = SyncState(lastSuccessAt = SampleData.now.minusSeconds(600))

        val state = viewModel().state

        assertThat(state.loading).isFalse()
        assertThat(state.sleepNeed).isEqualTo(Duration.ofMinutes(470))
        assertThat(state.typicalWake).isEqualTo(LocalTime.of(6, 30))
        assertThat(state.streakThreshold).isEqualTo(Duration.ofHours(4))
        assertThat(state.lowDebtMax).isEqualTo(Duration.ofMinutes(90))
        assertThat(state.highDebtMin).isEqualTo(Duration.ofHours(4))
        assertThat(state.clockFormat).isEqualTo(ClockFormat.H24)
        assertThat(state.morningReviewEnabled).isFalse()
        assertThat(state.appVersion).isEqualTo("1.0.0 (1)")
        assertThat(state.notifications).isEqualTo(NotificationAccess.BLOCKED)
        assertThat(state.healthConnect).isEqualTo(HealthConnectAccess.CONNECTED)
        assertThat(state.sync.lastSyncedAt).isEqualTo(SampleData.now.minusSeconds(600))
        assertThat(state.now).isEqualTo(SampleData.now)
        assertThat(state.zone).isEqualTo(SampleData.zone)
    }

    @Test
    fun `health connect status covers every case`() = runTest {
        val viewModel = viewModel()

        gateway.permissions = FakeHealthConnectGateway.NONE_GRANTED
        viewModel.refreshSystemStatus()
        assertThat(viewModel.state.healthConnect).isEqualTo(HealthConnectAccess.PERMISSION_NEEDED)

        gateway.status = HealthConnectStatus.NOT_INSTALLED
        viewModel.refreshSystemStatus()
        assertThat(viewModel.state.healthConnect).isEqualTo(HealthConnectAccess.NOT_INSTALLED)

        gateway.status = HealthConnectStatus.UPDATE_REQUIRED
        viewModel.refreshSystemStatus()
        assertThat(viewModel.state.healthConnect).isEqualTo(HealthConnectAccess.UPDATE_REQUIRED)

        gateway.status = HealthConnectStatus.NOT_SUPPORTED
        viewModel.refreshSystemStatus()
        assertThat(viewModel.state.healthConnect).isEqualTo(HealthConnectAccess.NOT_SUPPORTED)
    }

    @Test
    fun `returning to the screen picks up notification changes`() = runTest {
        platform.notifications = NotificationAccess.PERMISSION_NEEDED
        val viewModel = viewModel()
        assertThat(viewModel.state.notifications).isEqualTo(NotificationAccess.PERMISSION_NEEDED)

        platform.notifications = NotificationAccess.ALLOWED
        viewModel.refreshSystemStatus()

        assertThat(viewModel.state.notifications).isEqualTo(NotificationAccess.ALLOWED)
    }

    // ------------------------------------------------------------------ Sleep

    @Test
    fun `sleep need is saved in 5 minute steps within 6 to 11 hours`() = runTest {
        val viewModel = viewModel()

        viewModel.setSleepNeed(500)
        assertThat(stored.sleepNeed).isEqualTo(Duration.ofMinutes(500))
        assertThat(viewModel.state.sleepNeed).isEqualTo(Duration.ofMinutes(500))

        viewModel.setSleepNeed(487)
        assertThat(stored.sleepNeed).isEqualTo(Duration.ofMinutes(485))

        viewModel.setSleepNeed(5 * 60)
        assertThat(stored.sleepNeed).isEqualTo(Duration.ofHours(6))

        viewModel.setSleepNeed(12 * 60)
        assertThat(stored.sleepNeed).isEqualTo(Duration.ofHours(11))
    }

    @Test
    fun `typical wake is saved rounded to 5 minutes`() = runTest {
        val viewModel = viewModel()

        viewModel.setTypicalWake(LocalTime.of(6, 45))
        assertThat(stored.typicalWake).isEqualTo(LocalTime.of(6, 45))
        assertThat(viewModel.state.typicalWake).isEqualTo(LocalTime.of(6, 45))

        viewModel.setTypicalWake(LocalTime.of(6, 48))
        assertThat(stored.typicalWake).isEqualTo(LocalTime.of(6, 50))

        viewModel.setTypicalWake(LocalTime.of(23, 58))
        assertThat(stored.typicalWake).isEqualTo(LocalTime.MIDNIGHT)
    }

    @Test
    fun `a sleep need estimate is offered while it differs from the setting`() = runTest {
        val estimate = estimate(Duration.ofMinutes(515), Confidence.MEDIUM, nights = 9)
        insights.mutableState.value = readyWith(estimate)
        val viewModel = viewModel()

        assertThat(viewModel.state.needSuggestion)
            .isEqualTo(NeedSuggestion(Duration.ofMinutes(515), Confidence.MEDIUM, nightsUsed = 9))

        viewModel.useSuggestedNeed()

        assertThat(stored.sleepNeed).isEqualTo(Duration.ofMinutes(515))
        assertThat(viewModel.state.needSuggestion).isNull()
    }

    @Test
    fun `no suggestion without an estimate`() = runTest {
        insights.mutableState.value = readyWith(
            SleepNeedEstimateResult.NotEnoughData(nightsSoFar = 5, nightsRequired = 14, freeNightsSoFar = 1, freeNightsRequired = 4),
        )
        val viewModel = viewModel()

        assertThat(viewModel.state.needSuggestion).isNull()
        viewModel.useSuggestedNeed()
        assertThat(stored.sleepNeed).isEqualTo(Duration.ofHours(8))

        insights.mutableState.value = InsightsState.NoNights
        assertThat(viewModel.state.needSuggestion).isNull()
        assertThat(viewModel.state.learnedWake).isNull()
    }

    @Test
    fun `the learned wake time shows once the rhythm is known`() = runTest {
        val ready = SampleData.readyState() as InsightsState.Ready
        insights.mutableState.value = ready
        val anchor = ready.insights.schedule.anchor

        val viewModel = viewModel()

        assertThat(anchor.isFallback).isFalse()
        assertThat(viewModel.state.learnedWake).isEqualTo(anchor.typicalWake)
    }

    // ------------------------------------------------------------------ Goals

    @Test
    fun `streak threshold is saved in 30 minute steps within 2 to 10 hours`() = runTest {
        val viewModel = viewModel()

        viewModel.setStreakThreshold(270)
        assertThat(stored.streakThreshold).isEqualTo(Duration.ofMinutes(270))

        viewModel.setStreakThreshold(280)
        assertThat(stored.streakThreshold).isEqualTo(Duration.ofMinutes(270))

        viewModel.setStreakThreshold(60)
        assertThat(stored.streakThreshold).isEqualTo(Duration.ofHours(2))

        viewModel.setStreakThreshold(11 * 60)
        assertThat(stored.streakThreshold).isEqualTo(Duration.ofHours(10))
        assertThat(viewModel.state.streakThreshold).isEqualTo(Duration.ofHours(10))
    }

    @Test
    fun `debt levels are saved when low is below high`() = runTest {
        val viewModel = viewModel()

        assertThat(viewModel.setDebtLevels(90, 240)).isTrue()

        assertThat(stored.lowDebtMax).isEqualTo(Duration.ofMinutes(90))
        assertThat(stored.highDebtMin).isEqualTo(Duration.ofMinutes(240))
        assertThat(viewModel.state.lowDebtMax).isEqualTo(Duration.ofMinutes(90))
        assertThat(viewModel.state.highDebtMin).isEqualTo(Duration.ofMinutes(240))
    }

    @Test
    fun `invalid debt levels are rejected and nothing changes`() = runTest {
        val viewModel = viewModel()

        assertThat(viewModel.setDebtLevels(300, 300)).isFalse()
        assertThat(viewModel.setDebtLevels(360, 240)).isFalse()
        assertThat(viewModel.setDebtLevels(0, 240)).isFalse()
        assertThat(viewModel.setDebtLevels(120, 11 * 60)).isFalse()

        assertThat(stored.lowDebtMax).isEqualTo(Duration.ofHours(2))
        assertThat(stored.highDebtMin).isEqualTo(Duration.ofHours(5))
    }

    // ------------------------------------------------------------------ Data source

    @Test
    fun `source list has Garmin, all sources, then other apps by name`() = runTest {
        sleep.originsState.value = listOf(
            "com.example.sleeplog",
            SleepConfig.GARMIN_CONNECT_PACKAGE,
            "com.ouraring.oura",
            SleepConfig.DEMO_ORIGIN,
        )
        platform.labels["com.ouraring.oura"] = "Oura"
        platform.labels["com.example.sleeplog"] = "Sleep Log"

        val options = viewModel().state.sourceOptions

        assertThat(options.map { it.kind }).containsExactly(SourceKind.GARMIN, SourceKind.ALL, SourceKind.APP, SourceKind.APP).inOrder()
        assertThat(options[0].selection).isEqualTo(SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE))
        assertThat(options[0].hasData).isTrue()
        assertThat(options[1].selection).isEqualTo(SourceSelection.All)
        assertThat(options.drop(2).map { it.appLabel }).containsExactly("Oura", "Sleep Log").inOrder()
        assertThat(options.drop(2).map { it.packageName }).containsExactly("com.ouraring.oura", "com.example.sleeplog").inOrder()
        assertThat(platform.labelLookups).doesNotContain(SleepConfig.DEMO_ORIGIN)
    }

    @Test
    fun `garmin is listed even before it wrote any sleep, and unknown apps keep their package name`() = runTest {
        sleep.originsState.value = listOf("com.example.unknown")

        val options = viewModel().state.sourceOptions

        assertThat(options[0].kind).isEqualTo(SourceKind.GARMIN)
        assertThat(options[0].hasData).isFalse()
        assertThat(options[2].appLabel).isNull()
        assertThat(options[2].packageName).isEqualTo("com.example.unknown")
    }

    @Test
    fun `selecting a source saves it, including all sources`() = runTest {
        sleep.originsState.value = listOf(SleepConfig.GARMIN_CONNECT_PACKAGE, "com.ouraring.oura")
        val viewModel = viewModel()

        viewModel.selectSource(SourceSelection.Single("com.ouraring.oura"))
        assertThat(stored.source).isEqualTo(SourceSelection.Single("com.ouraring.oura"))
        assertThat(viewModel.state.source).isEqualTo(SourceSelection.Single("com.ouraring.oura"))

        viewModel.selectSource(SourceSelection.All)
        assertThat(stored.source).isEqualTo(SourceSelection.All)
        assertThat(viewModel.state.source).isEqualTo(SourceSelection.All)

        viewModel.selectSource(SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE))
        assertThat(stored.source).isEqualTo(SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE))
    }

    @Test
    fun `a selected app that stopped writing sleep stays in the list`() = runTest {
        sleep.originsState.value = listOf(SleepConfig.GARMIN_CONNECT_PACKAGE)
        settings.setSource(SourceSelection.Single("com.fitbit.FitbitMobile"))

        val options = viewModel().state.sourceOptions

        val stale = options.single { it.packageName == "com.fitbit.FitbitMobile" }
        assertThat(stale.hasData).isFalse()
        assertThat(stale.kind).isEqualTo(SourceKind.APP)
    }

    // ------------------------------------------------------------------ Demo

    @Test
    fun `demo mode is switched through the controller`() = runTest {
        val controller = FakeDemoModeController(settings)
        demo = controller
        val viewModel = viewModel()

        viewModel.setDemoMode(true)
        assertThat(viewModel.state.demoMode).isTrue()
        assertThat(viewModel.state.demoSwitching).isFalse()

        viewModel.setDemoMode(false)
        assertThat(viewModel.state.demoMode).isFalse()

        assertThat(controller.calls).containsExactly(true, false).inOrder()
        assertThat(settings.state.value.demoMode).isFalse()
    }

    @Test
    fun `a failed demo switch says so`() = runTest {
        demo = object : DemoModeController {
            override suspend fun setDemoMode(enabled: Boolean) = throw IOException("disk full")
        }
        val viewModel = viewModel()

        viewModel.setDemoMode(true)

        assertThat(viewModel.state.demoMode).isFalse()
        assertThat(viewModel.state.demoSwitching).isFalse()
        assertThat(viewModel.state.message).isEqualTo(SettingsMessage.DEMO_FAILED)

        viewModel.messageShown()
        assertThat(viewModel.state.message).isNull()
    }

    // ------------------------------------------------------------------ Sync

    @Test
    fun `sync now runs a manual sync and reports how much changed`() = runTest {
        sync.nextResult = SyncResult(success = true, sessionsChanged = 3)
        val viewModel = viewModel()

        viewModel.syncNow()

        assertThat(sync.syncCalls).containsExactly(SyncTrigger.MANUAL)
        assertThat(viewModel.state.sync.feedback).isEqualTo(SyncFeedback.Success(3))
        assertThat(viewModel.state.sync.inProgress).isFalse()
    }

    @Test
    fun `a failed sync shows its error`() = runTest {
        sync.nextResult = SyncResult(success = false, error = SyncError.PERMISSION_DENIED)
        val viewModel = viewModel()

        viewModel.syncNow()

        assertThat(viewModel.state.sync.feedback).isEqualTo(SyncFeedback.Failed(SyncError.PERMISSION_DENIED))
    }

    @Test
    fun `a sync running elsewhere shows as in progress`() = runTest {
        val viewModel = viewModel()

        sync.mutableState.update { it.copy(status = SyncStatus.Importing(0.4f)) }
        assertThat(viewModel.state.sync.inProgress).isTrue()

        sync.mutableState.update { it.copy(status = SyncStatus.Idle, lastError = SyncError.RATE_LIMITED) }
        assertThat(viewModel.state.sync.inProgress).isFalse()
        assertThat(viewModel.state.sync.lastError).isEqualTo(SyncError.RATE_LIMITED)
    }

    // ------------------------------------------------------------------ Export

    @Test
    fun `export as CSV writes into the chosen file and closes it`() = runTest {
        val out = TrackingStream()
        val viewModel = viewModel()

        viewModel.export(ExportFormat.CSV, open = { out })

        assertThat(export.csvExports).isEqualTo(1)
        assertThat(out.toString(Charsets.UTF_8)).isEqualTo("wake_date\n")
        assertThat(out.closed).isTrue()
        assertThat(viewModel.state.exporting).isNull()
        assertThat(viewModel.state.message).isEqualTo(SettingsMessage.EXPORTED_CSV)
    }

    @Test
    fun `export as JSON writes into the chosen file`() = runTest {
        val out = TrackingStream()
        val viewModel = viewModel()

        viewModel.export(ExportFormat.JSON, open = { out })

        assertThat(export.jsonExports).isEqualTo(1)
        assertThat(out.toString(Charsets.UTF_8)).isEqualTo("{}")
        assertThat(viewModel.state.message).isEqualTo(SettingsMessage.EXPORTED_JSON)
    }

    @Test
    fun `a failed export removes the half-written file and says so`() = runTest {
        var discarded = 0
        val viewModel = viewModel()

        viewModel.export(ExportFormat.CSV, open = { throw IOException("no space") }, discard = { discarded++ })
        assertThat(viewModel.state.message).isEqualTo(SettingsMessage.EXPORT_FAILED)

        viewModel.messageShown()
        viewModel.export(ExportFormat.JSON, open = { null }, discard = { discarded++ })

        assertThat(discarded).isEqualTo(2)
        assertThat(export.jsonExports).isEqualTo(0)
        assertThat(viewModel.state.message).isEqualTo(SettingsMessage.EXPORT_FAILED)
        assertThat(viewModel.state.exporting).isNull()
    }

    // ------------------------------------------------------------------ Notifications and display

    @Test
    fun `morning review and time format are saved`() = runTest {
        val viewModel = viewModel()

        viewModel.setMorningReviewEnabled(false)
        viewModel.setClockFormat(ClockFormat.H12)

        assertThat(settings.state.value.morningReviewEnabled).isFalse()
        assertThat(settings.state.value.clockFormat).isEqualTo(ClockFormat.H12)
        assertThat(viewModel.state.morningReviewEnabled).isFalse()
        assertThat(viewModel.state.clockFormat).isEqualTo(ClockFormat.H12)
    }

    // ------------------------------------------------------------------ Reset

    @Test
    fun `reset deletes everything once and then hands over`() = runTest {
        val fake = FakeDataResetter()
        resetter = fake
        val viewModel = viewModel()

        viewModel.resetAll()
        viewModel.resetAll()

        assertThat(fake.resets).isEqualTo(1)
        assertThat(viewModel.state.resetDone).isTrue()
        assertThat(viewModel.state.resetting).isFalse()
    }

    @Test
    fun `a failed reset stays on the screen and says so`() = runTest {
        resetter = object : DataResetter {
            override suspend fun resetAll() = throw IllegalStateException("database locked")
        }
        val viewModel = viewModel()

        viewModel.resetAll()

        assertThat(viewModel.state.resetDone).isFalse()
        assertThat(viewModel.state.resetting).isFalse()
        assertThat(viewModel.state.message).isEqualTo(SettingsMessage.RESET_FAILED)
    }

    // ------------------------------------------------------------------ Helpers

    private fun estimate(need: Duration, confidence: Confidence, nights: Int) = SleepNeedEstimateResult.Available(
        SleepNeedEstimate(
            need = need,
            freeNightsUsed = nights,
            interquartileRange = Duration.ofMinutes(40),
            confidence = confidence,
            typicalWeekdayWake = LocalTime.of(6, 50),
        ),
    )

    private fun readyWith(result: SleepNeedEstimateResult): InsightsState {
        val ready = SampleData.readyState() as InsightsState.Ready
        return InsightsState.Ready(ready.insights.copy(needEstimate = result))
    }

    private class TrackingStream : ByteArrayOutputStream() {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }
}
