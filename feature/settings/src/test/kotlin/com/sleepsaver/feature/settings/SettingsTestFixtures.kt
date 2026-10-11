package com.sleepsaver.feature.settings

import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.ClockFormat
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Confidence
import com.sleepsaver.core.domain.model.LearnedSleepTiming
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.feature.settings.platform.SettingsPlatform
import java.time.DayOfWeek
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

internal class FakeTimeProvider(var now: Instant = SampleData.now, private val zone: ZoneId = SampleData.zone) : TimeProvider {
    override fun now(): Instant = now
    override fun zone(): ZoneId = zone
    override val minuteTicks: Flow<Instant> get() = flowOf(now)
}

internal class FakeSettingsPlatform(
    var version: String = "1.0.0 (1)",
    val labels: MutableMap<String, String> = mutableMapOf(),
    var notifications: NotificationAccess = NotificationAccess.ALLOWED,
) : SettingsPlatform {
    val labelLookups = mutableListOf<String>()
    override fun appVersion(): String = version
    override fun appLabel(packageName: String): String? {
        labelLookups += packageName
        return labels[packageName]
    }
    override fun notificationAccess(): NotificationAccess = notifications
}

/** Realistic states for screenshot and UI tests. */
internal object SettingsSamples {
    const val OURA = "com.ouraring.oura"
    const val SLEEP_APP = "com.example.sleeplog"

    val options = SourceOption.defaults() + listOf(
        SourceOption(SourceSelection.Single(OURA), SourceKind.APP, appLabel = "Oura"),
        SourceOption(SourceSelection.Single(SLEEP_APP), SourceKind.APP, appLabel = null, hasData = false),
    )

    val ready = SettingsUiState(
        loading = false,
        sleepNeed = Duration.ofHours(8),
        typicalWake = LocalTime.of(7, 0),
        learned = LearnedSleepTiming(
            weekdayWake = LocalTime.of(6, 52),
            weekendWake = LocalTime.of(8, 35),
            weekdayFellAsleep = LocalTime.of(23, 10),
            weekendFellAsleep = LocalTime.of(0, 20),
        ),
        usualWake = LocalTime.of(6, 52),
        usualBedtime = LocalTime.of(22, 55),
        needSuggestion = NeedSuggestion(Duration.ofMinutes(515), Confidence.MEDIUM, nightsUsed = 9),
        weekPlan = WeekPlanSummary.OneTime(
            setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            LocalTime.of(6, 30),
        ),
        streakThreshold = Duration.ofHours(5),
        lowDebtMax = Duration.ofHours(2),
        highDebtMin = Duration.ofHours(5),
        source = SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE),
        sourceOptions = options,
        demoMode = false,
        healthConnect = HealthConnectAccess.CONNECTED,
        sync = SyncUi(lastSyncedAt = SampleData.now),
        morningReviewEnabled = true,
        notifications = NotificationAccess.ALLOWED,
        clockFormat = ClockFormat.SYSTEM,
        appVersion = "1.0.0 (1)",
        now = SampleData.now,
        zone = SampleData.zone,
    )

    /** Fresh install: no estimate yet, onboarding wake time, never synced, notifications not granted. */
    val fresh = ready.copy(
        learned = LearnedSleepTiming(),
        usualWake = null,
        usualBedtime = null,
        needSuggestion = null,
        weekPlan = WeekPlanSummary.NoAlarms,
        healthConnect = HealthConnectAccess.PERMISSION_NEEDED,
        sync = SyncUi(lastSyncedAt = null, lastError = SyncError.PERMISSION_DENIED),
        notifications = NotificationAccess.PERMISSION_NEEDED,
        sourceOptions = SourceOption.defaults(garminHasData = false),
    )

    /** A phone without Health Connect: nothing synced, the row offers to install it. */
    val healthConnectMissing = fresh.copy(
        healthConnect = HealthConnectAccess.NOT_INSTALLED,
        sync = SyncUi(lastSyncedAt = null, lastError = SyncError.HEALTH_CONNECT_NOT_INSTALLED),
        notifications = NotificationAccess.ALLOWED,
    )

    /** Demo mode, an export running, a sync running and notifications blocked in system settings. */
    val busy = ready.copy(
        demoMode = true,
        source = SourceSelection.All,
        exporting = ExportFormat.JSON,
        sync = SyncUi(lastSyncedAt = SampleData.now.minus(Duration.ofDays(1)), inProgress = true),
        notifications = NotificationAccess.BLOCKED,
        clockFormat = ClockFormat.H24,
        needSuggestion = null,
        weekPlan = WeekPlanSummary.Varied,
    )

    /** A test ring set for a minute from now. */
    val testRingSet = ready.copy(testRingAt = SampleData.now + Duration.ofMinutes(1))
}
