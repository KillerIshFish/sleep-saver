package com.sleepsaver.feature.settings

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.healthconnect.HealthConnectStatus
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.ClockFormat
import com.sleepsaver.core.data.repository.DataResetter
import com.sleepsaver.core.data.repository.DemoModeController
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.data.repository.SyncRepository
import com.sleepsaver.core.data.repository.SyncResult
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.data.repository.SyncStatus
import com.sleepsaver.core.data.repository.SyncTrigger
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.BedtimeMode
import com.sleepsaver.core.domain.model.Confidence
import com.sleepsaver.core.domain.model.LearnedSleepTiming
import com.sleepsaver.core.domain.model.SleepNeedEstimate
import com.sleepsaver.core.domain.model.SleepNeedEstimateResult
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.model.WakeTimeMode
import com.sleepsaver.core.domain.patterns.PersonalLatency
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.AlarmSettings
import com.sleepsaver.core.domain.schedule.MorningPlan
import com.sleepsaver.core.domain.schedule.ScheduleTimes
import com.sleepsaver.core.testing.FakeDataResetter
import com.sleepsaver.core.testing.FakeDemoModeController
import com.sleepsaver.core.testing.FakeExportRepository
import com.sleepsaver.core.testing.FakeHealthConnectGateway
import com.sleepsaver.core.testing.FakeInsightsRepository
import com.sleepsaver.core.testing.FakeNightController
import com.sleepsaver.core.testing.FakePatternsRepository
import com.sleepsaver.core.testing.FakeScheduleRepository
import com.sleepsaver.core.testing.FakeSettingsRepository
import com.sleepsaver.core.testing.FakeSleepRepository
import com.sleepsaver.core.testing.FakeSyncRepository
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.SampleData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.DayOfWeek
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
    private val schedule = FakeScheduleRepository()
    private val patterns = FakePatternsRepository()
    private val night = FakeNightController()
    private var demo: DemoModeController = FakeDemoModeController(settings)
    private var resetter: DataResetter = FakeDataResetter()
    private var settingsRepository: SettingsRepository = settings
    private var syncRepository: SyncRepository = sync

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): SettingsViewModel {
        val io = UnconfinedTestDispatcher(testScheduler)
        val viewModel = SettingsViewModel(
            settingsRepository = settingsRepository,
            sleepRepository = sleep,
            syncRepository = syncRepository,
            insightsRepository = insights,
            demoModeController = demo,
            exportRepository = export,
            dataResetter = resetter,
            healthConnect = gateway,
            platform = platform,
            scheduleRepository = schedule,
            patternsRepository = patterns,
            nightController = night,
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
    fun `wake time mode and own time are saved, the time rounded to 5 minutes`() = runTest {
        val viewModel = viewModel()
        assertThat(viewModel.state.wakeTimeMode).isEqualTo(WakeTimeMode.FROM_DATA)

        viewModel.setWakeTime(WakeTimeMode.FIXED, LocalTime.of(6, 45))
        assertThat(stored.wakeTimeMode).isEqualTo(WakeTimeMode.FIXED)
        assertThat(stored.typicalWake).isEqualTo(LocalTime.of(6, 45))
        assertThat(viewModel.state.typicalWake).isEqualTo(LocalTime.of(6, 45))
        assertThat(viewModel.state.wakeTimeMode).isEqualTo(WakeTimeMode.FIXED)

        viewModel.setWakeTime(WakeTimeMode.FROM_DATA, LocalTime.of(6, 48))
        assertThat(stored.wakeTimeMode).isEqualTo(WakeTimeMode.FROM_DATA)
        assertThat(stored.typicalWake).isEqualTo(LocalTime.of(6, 50))

        viewModel.setWakeTime(WakeTimeMode.FIXED, LocalTime.of(23, 58))
        assertThat(stored.typicalWake).isEqualTo(LocalTime.MIDNIGHT)
    }

    @Test
    fun `bedtime mode and own time are saved`() = runTest {
        val viewModel = viewModel()
        assertThat(viewModel.state.bedtimeMode).isEqualTo(BedtimeMode.FROM_DATA)

        viewModel.setBedtime(BedtimeMode.SLEEP_NEED, LocalTime.of(22, 33))
        assertThat(stored.bedtimeMode).isEqualTo(BedtimeMode.SLEEP_NEED)
        assertThat(stored.bedtime).isEqualTo(LocalTime.of(22, 35))

        viewModel.setBedtime(BedtimeMode.FIXED, LocalTime.of(0, 30))
        assertThat(viewModel.state.bedtimeMode).isEqualTo(BedtimeMode.FIXED)
        assertThat(viewModel.state.bedtime).isEqualTo(LocalTime.of(0, 30))
    }

    @Test
    fun `a sleep need estimate is offered while it differs from the setting`() = runTest {
        val estimate = estimate(Duration.ofMinutes(515), Confidence.MEDIUM, nights = 9)
        insights.mutableState.value = readyWith(estimate)
        val viewModel = viewModel()

        assertThat(viewModel.state.needSuggestion)
            .isEqualTo(NeedSuggestion(Duration.ofMinutes(515), Confidence.MEDIUM, nightsUsed = 9, wakeDelay = Duration.ofMinutes(45)))

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
        assertThat(viewModel.state.learned).isEqualTo(LearnedSleepTiming())
    }

    @Test
    fun `learned times come from the insights`() = runTest {
        val ready = SampleData.readyState() as InsightsState.Ready
        insights.mutableState.value = ready

        val viewModel = viewModel()

        assertThat(viewModel.state.learned).isEqualTo(ready.insights.learnedTiming)
        assertThat(viewModel.state.learned.weekdayWake).isNotNull()
    }

    @Test
    fun `without nights the rows show the user's own wake time and bedtime`() = runTest {
        val viewModel = viewModel()

        assertThat(viewModel.state.usualWake).isEqualTo(LocalTime.of(7, 0))
        assertThat(viewModel.state.usualBedtime).isEqualTo(LocalTime.of(23, 0))
    }

    @Test
    fun `the wake time and bedtime rows show the usual times, which an early alarm tomorrow doesn't move`() = runTest {
        val nights = SampleData.nights()
        sleep.nightsState.value = nights
        settings.state.update { it.copy(sleep = it.sleep.copy(bedtimeMode = BedtimeMode.SLEEP_NEED)) }
        insights.mutableState.value = SampleData.readyState()
        val viewModel = viewModel()
        val wake = viewModel.state.usualWake
        val bedtime = viewModel.state.usualBedtime
        // Learned from the nights, and the bedtime leaves room for the 8h need plus 15 min to fall asleep.
        assertThat(wake).isNotEqualTo(LocalTime.of(7, 0))
        assertThat(bedtime).isEqualTo(wake!!.minusHours(8).minusMinutes(15))

        // Tomorrow gets its own 5:30 alarm: the plan, the settings it's merged into and the schedule all move...
        val tomorrow = SampleData.today.plusDays(1).dayOfWeek
        schedule.updateDay(tomorrow) {
            it.copy(morning = MorningPlan(wakeTime = LocalTime.of(5, 30), alarm = AlarmSettings(mode = AlarmMode.FIXED)))
        }
        val withPlan = settings.state.value.sleep.copy(week = schedule.weekState.value)
        settings.state.update { it.copy(sleep = withPlan) }
        insights.mutableState.value = InsightsState.Ready(SampleData.insights(settings = withPlan, nights = nights))
        val planBedtime = ScheduleTimes().bedtime(SampleData.today, SampleData.zone, nights, withPlan.sleepNeed, withPlan.timing).first
        assertThat(planBedtime.atZone(SampleData.zone).toLocalTime()).isEqualTo(LocalTime.of(21, 15))

        // ...but neither row does.
        assertThat(viewModel.state.usualWake).isEqualTo(wake)
        assertThat(viewModel.state.usualBedtime).isEqualTo(bedtime)
    }

    @Test
    fun `the usual bedtime uses the personal time to fall asleep once there is one`() = runTest {
        sleep.nightsState.value = SampleData.nights()
        settings.state.update { it.copy(sleep = it.sleep.copy(bedtimeMode = BedtimeMode.SLEEP_NEED)) }
        val viewModel = viewModel()
        val wake = viewModel.state.usualWake!!

        patterns.personalLatencyState.value = PersonalLatency(values = 9, median = Duration.ofMinutes(35), active = true)

        assertThat(viewModel.state.usualBedtime).isEqualTo(wake.minusHours(8).minusMinutes(35))
    }

    @Test
    fun `the week plan row sums up the alarm days`() = runTest {
        val viewModel = viewModel()
        assertThat(viewModel.state.weekPlan).isEqualTo(WeekPlanSummary.NoAlarms)

        schedule.weekState.value = PlanSamples.weekPlan()
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        assertThat(viewModel.state.weekPlan).isEqualTo(WeekPlanSummary.OneTime(weekdays, LocalTime.of(6, 45)))

        schedule.updateDay(DayOfWeek.FRIDAY) { it.copy(morning = it.morning.copy(wakeTime = LocalTime.of(7, 30))) }
        assertThat(viewModel.state.weekPlan).isEqualTo(WeekPlanSummary.Varied)
    }

    @Test
    fun `a change that can't be stored says so instead of crashing`() = runTest {
        settingsRepository = object : SettingsRepository by settings {
            override suspend fun setSleepNeed(need: Duration) = throw IOException("disk full")
            override suspend fun setClockFormat(format: ClockFormat) = throw IOException("disk full")
        }
        val viewModel = viewModel()

        viewModel.setSleepNeed(500)

        assertThat(stored.sleepNeed).isEqualTo(Duration.ofHours(8))
        assertThat(viewModel.state.message).isEqualTo(SettingsMessage.SAVE_FAILED)

        viewModel.messageShown()
        viewModel.setClockFormat(ClockFormat.H24)
        assertThat(viewModel.state.message).isEqualTo(SettingsMessage.SAVE_FAILED)
    }

    // ------------------------------------------------------------------ Alarms

    @Test
    fun `a test ring is set through the night controller, and a busy one says so`() = runTest {
        val viewModel = viewModel()

        viewModel.testRing()

        assertThat(night.nightControllerCalls).containsExactly("testRing")
        assertThat(viewModel.state.message).isEqualTo(SettingsMessage.TEST_RING_SET)
        assertThat(viewModel.state.testRingAt).isEqualTo(SampleData.now + SleepConfig.DEFAULT.alarm.testRingDelay)

        viewModel.messageShown()
        night.testRingAllowed = false
        viewModel.testRing()
        assertThat(viewModel.state.message).isEqualTo(SettingsMessage.TEST_RING_BUSY)
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
    fun `a second tap while syncing doesn't start another sync`() = runTest {
        val done = CompletableDeferred<SyncResult>()
        val calls = mutableListOf<SyncTrigger>()
        syncRepository = object : SyncRepository by sync {
            override suspend fun sync(trigger: SyncTrigger): SyncResult {
                calls += trigger
                return done.await()
            }
        }
        val viewModel = viewModel()

        viewModel.syncNow()
        viewModel.syncNow()
        assertThat(viewModel.state.sync.inProgress).isTrue()

        done.complete(SyncResult(success = true))
        advanceUntilIdle()

        assertThat(calls).containsExactly(SyncTrigger.MANUAL)
        assertThat(viewModel.state.sync.inProgress).isFalse()
        assertThat(viewModel.state.sync.feedback).isEqualTo(SyncFeedback.Success(0))
    }

    @Test
    fun `a sync that throws anyway is shown as failed`() = runTest {
        syncRepository = object : SyncRepository by sync {
            override suspend fun sync(trigger: SyncTrigger): SyncResult = throw IllegalStateException("binder died")
        }
        val viewModel = viewModel()

        viewModel.syncNow()

        assertThat(viewModel.state.sync.inProgress).isFalse()
        assertThat(viewModel.state.sync.feedback).isEqualTo(SyncFeedback.Failed(SyncError.UNKNOWN))
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

    @Test
    fun `only one export runs at a time`() = runTest {
        val viewModel = viewModel()
        val out = TrackingStream()
        var opened = 0

        viewModel.export(ExportFormat.JSON, open = {
            opened++
            // A second request while this one is still writing is ignored.
            viewModel.export(ExportFormat.CSV, open = { error("not expected") })
            out
        })

        assertThat(opened).isEqualTo(1)
        assertThat(export.jsonExports).isEqualTo(1)
        assertThat(export.csvExports).isEqualTo(0)
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
