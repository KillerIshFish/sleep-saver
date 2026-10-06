package com.sleepsaver.core.testing

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import android.content.Context
import com.sleepsaver.core.data.healthconnect.HealthConnectGateway
import com.sleepsaver.core.data.healthconnect.HealthConnectStatus
import com.sleepsaver.core.data.healthconnect.HealthPermissionState
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.ClockFormat
import com.sleepsaver.core.data.repository.DataResetter
import com.sleepsaver.core.data.repository.DemoModeController
import com.sleepsaver.core.data.repository.ExportRepository
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.MorningReview
import com.sleepsaver.core.data.repository.ReviewRepository
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SleepRepository
import com.sleepsaver.core.data.repository.SyncRepository
import com.sleepsaver.core.data.repository.SyncResult
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.data.repository.SyncTrigger
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.habits.HabitCatalog
import com.sleepsaver.core.domain.model.DebtChangeBreakdown
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.NightOverride
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.domain.model.SleepDebt
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.model.VitalSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

// In-memory fakes for every repository interface. Each exposes its backing state so tests can drive it
// and records calls so tests can assert on them.

class FakeSleepRepository(nights: List<Night> = emptyList()) : SleepRepository {
    val nightsState = MutableStateFlow(nights)
    val originsState = MutableStateFlow(listOf(SleepConfig.GARMIN_CONNECT_PACKAGE))
    val vitalsState = MutableStateFlow<List<VitalSample>>(emptyList())
    val savedOverrides = mutableListOf<NightOverride>()
    val reverted = mutableListOf<LocalDate>()
    val addedNaps = mutableListOf<Pair<Instant, Instant>>()
    val addedAwake = mutableListOf<Triple<LocalDate, Instant, Instant>>()
    val manualNights = mutableListOf<Triple<LocalDate, Instant, Instant>>()
    val deletedNaps = mutableListOf<Long>()

    override val nights: Flow<List<Night>> = nightsState
    override fun night(wakeDate: LocalDate): Flow<Night?> = nightsState.map { list -> list.firstOrNull { it.wakeDate == wakeDate } }
    override fun rawNight(wakeDate: LocalDate): Flow<Night?> = night(wakeDate)
    override val origins: Flow<List<String>> = originsState
    override fun vitals(from: Instant, to: Instant): Flow<List<VitalSample>> =
        vitalsState.map { list -> list.filter { !it.time.isBefore(from) && !it.time.isAfter(to) } }

    override suspend fun saveOverride(override: NightOverride) { savedOverrides += override }
    override suspend fun revert(wakeDate: LocalDate) { reverted += wakeDate }
    override suspend fun addManualNight(wakeDate: LocalDate, fellAsleep: Instant, wokeUp: Instant, offset: ZoneOffset) {
        manualNights += Triple(wakeDate, fellAsleep, wokeUp)
    }
    override suspend fun addNap(start: Instant, end: Instant, offset: ZoneOffset): Long {
        addedNaps += start to end
        return addedNaps.size.toLong()
    }
    override suspend fun deleteNap(id: Long) { deletedNaps += id }
    override suspend fun addAwake(wakeDate: LocalDate, start: Instant, end: Instant): Long {
        addedAwake += Triple(wakeDate, start, end)
        return addedAwake.size.toLong()
    }
    override suspend fun deleteAwake(id: Long) = Unit
}

class FakeInsightsRepository(
    initial: InsightsState = InsightsState.Loading,
    var debtSeriesResult: List<SleepDebt> = emptyList(),
    var scheduleResult: EnergySchedule? = null,
    var breakdownResult: DebtChangeBreakdown? = null,
) : InsightsRepository {
    val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<InsightsState> = mutableState.asStateFlow()
    override fun debtSeries(from: LocalDate, to: LocalDate): Flow<List<SleepDebt>> =
        flowOf(debtSeriesResult.filter { !it.asOf.isBefore(from) && !it.asOf.isAfter(to) })
    override fun schedule(date: LocalDate): Flow<EnergySchedule?> = flowOf(scheduleResult)
    override suspend fun breakdown(wakeDate: LocalDate): DebtChangeBreakdown =
        breakdownResult ?: DebtChangeBreakdown(
            wakeDate = wakeDate,
            need = Duration.ofHours(8),
            slept = Duration.ofHours(8),
            before = Duration.ZERO,
            lastNightDelta = Duration.ZERO,
            fade = Duration.ZERO,
            after = Duration.ZERO,
        )
}

class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun current(): AppSettings = state.value
    override suspend fun setSleepNeed(need: Duration) = state.update { it.copy(sleep = it.sleep.copy(sleepNeed = need)) }
    override suspend fun setTypicalWake(time: LocalTime) = state.update { it.copy(sleep = it.sleep.copy(typicalWake = time)) }
    override suspend fun setStreakThreshold(threshold: Duration) = state.update { it.copy(sleep = it.sleep.copy(streakThreshold = threshold)) }
    override suspend fun setDebtThresholds(lowMax: Duration, highMin: Duration) =
        state.update { it.copy(sleep = it.sleep.copy(lowDebtMax = lowMax, highDebtMin = highMin)) }
    override suspend fun setSource(source: SourceSelection) = state.update { it.copy(sleep = it.sleep.copy(source = source)) }
    override suspend fun setDemoModeFlag(enabled: Boolean, startedAt: Instant?) =
        state.update { it.copy(demoMode = enabled, demoStartedAt = startedAt) }
    override suspend fun setClockFormat(format: ClockFormat) = state.update { it.copy(clockFormat = format) }
    override suspend fun setMorningReviewEnabled(enabled: Boolean) = state.update { it.copy(morningReviewEnabled = enabled) }
    override suspend fun setOnboardingDone(done: Boolean) = state.update { it.copy(onboardingDone = done) }
    override suspend fun dismissNeedSuggestion(value: Duration, at: Instant) =
        state.update { it.copy(needSuggestionDismissedValue = value, needSuggestionDismissedAt = at) }
}

class FakeSyncRepository(var nextResult: SyncResult = SyncResult(success = true)) : SyncRepository {
    val mutableState = MutableStateFlow(SyncState())
    val syncCalls = mutableListOf<SyncTrigger>()
    var importCalls = 0
    var periodicEnsured = 0
    var retriesStarted = 0
    var resets = 0
    override val state: StateFlow<SyncState> = mutableState.asStateFlow()
    override suspend fun sync(trigger: SyncTrigger): SyncResult { syncCalls += trigger; return nextResult }
    override suspend fun importHistory(): SyncResult { importCalls++; return nextResult }
    override fun ensurePeriodicSync() { periodicEnsured++ }
    override fun startWaitingRetries() { retriesStarted++ }
    override suspend fun resetSyncState() { resets++ }
}

class FakeDemoModeController(private val settings: FakeSettingsRepository? = null) : DemoModeController {
    val calls = mutableListOf<Boolean>()
    override suspend fun setDemoMode(enabled: Boolean) {
        calls += enabled
        settings?.setDemoModeFlag(enabled, if (enabled) Instant.EPOCH else null)
    }
}

class FakeHabitsRepository(
    settings: List<HabitSettings> = HabitCatalog().defaultSettings(),
    activities: List<RoutineActivity> = DEFAULT_ACTIVITIES,
) : HabitsRepository {
    val settingsState = MutableStateFlow(settings)
    val activitiesState = MutableStateFlow(activities)
    override val habitSettings: Flow<List<HabitSettings>> = settingsState
    override val routineActivities: Flow<List<RoutineActivity>> = activitiesState

    private fun edit(type: HabitType, block: (HabitSettings) -> HabitSettings) =
        settingsState.update { list -> list.map { if (it.type == type) block(it) else it } }

    override suspend fun setEnabled(type: HabitType, enabled: Boolean) = edit(type) { it.copy(enabled = enabled) }
    override suspend fun setReminder(type: HabitType, enabled: Boolean) = edit(type) { it.copy(reminderEnabled = enabled) }
    override suspend fun setOffset(type: HabitType, offset: Duration) = edit(type) { it.copy(offset = offset) }
    override suspend fun resetOffset(type: HabitType) = edit(type) { it.copy(offset = HabitCatalog().definition(type).defaultOffset) }
    override suspend fun setActivitySelected(id: Long, selected: Boolean) =
        activitiesState.update { list -> list.map { if (it.id == id) it.copy(selected = selected) else it } }
    override suspend fun addCustomActivity(label: String, emoji: String): Long {
        val id = (activitiesState.value.maxOfOrNull { it.id } ?: 0) + 1
        activitiesState.update { it + RoutineActivity(id, label, emoji, isCustom = true, selected = true) }
        return id
    }
    override suspend fun deleteActivity(id: Long) = activitiesState.update { list -> list.filterNot { it.id == id } }

    companion object {
        val DEFAULT_ACTIVITIES = listOf(
            RoutineActivity(1, "Reading", "📖", isCustom = false, selected = true),
            RoutineActivity(2, "Stretching", "🧘", isCustom = false, selected = false),
            RoutineActivity(3, "Warm shower", "🚿", isCustom = false, selected = false),
            RoutineActivity(4, "Podcast", "🎧", isCustom = false, selected = true),
            RoutineActivity(5, "Journaling", "✍️", isCustom = false, selected = false),
            RoutineActivity(6, "TV", "📺", isCustom = false, selected = false),
            RoutineActivity(7, "Gaming", "🎮", isCustom = false, selected = false),
        )
    }
}

class FakeReviewRepository : ReviewRepository {
    val reviews = MutableStateFlow<Map<LocalDate, MorningReview>>(emptyMap())
    val pending = MutableStateFlow<LocalDate?>(null)
    override fun review(wakeDate: LocalDate): Flow<MorningReview?> = reviews.map { it[wakeDate] }
    override suspend fun record(review: MorningReview) = reviews.update { it + (review.wakeDate to review) }
    override val pendingReview: Flow<LocalDate?> = pending
}

class FakeExportRepository : ExportRepository {
    var csvExports = 0
    var jsonExports = 0
    override suspend fun exportCsv(out: OutputStream) { csvExports++; out.write("wake_date\n".toByteArray()) }
    override suspend fun exportJson(out: OutputStream) { jsonExports++; out.write("{}".toByteArray()) }
}

class FakeDataResetter : DataResetter {
    var resets = 0
    override suspend fun resetAll() { resets++ }
}

/** Configurable Health Connect. Intents are empty; the contract echoes back [grantOnRequest]. */
class FakeHealthConnectGateway(
    var status: HealthConnectStatus = HealthConnectStatus.AVAILABLE,
    var permissions: HealthPermissionState = ALL_GRANTED,
    var garminInstalled: Boolean = true,
    var origins: List<String> = listOf(SleepConfig.GARMIN_CONNECT_PACKAGE),
    var grantOnRequest: Set<String> = emptySet(),
) : HealthConnectGateway {
    override fun status(): HealthConnectStatus = status
    override suspend fun permissionsToRequest(): Set<String> = setOf(SLEEP_PERMISSION)
    override suspend fun permissionState(): HealthPermissionState = permissions
    override fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        object : ActivityResultContract<Set<String>, Set<String>>() {
            override fun createIntent(context: Context, input: Set<String>) = Intent()
            override fun parseResult(resultCode: Int, intent: Intent?) = grantOnRequest
        }
    override fun installOrUpdateIntent(): Intent = Intent()
    override fun manageDataIntent(): Intent = Intent()
    override fun isGarminConnectInstalled(): Boolean = garminInstalled
    override fun garminConnectLaunchIntent(): Intent? = if (garminInstalled) Intent() else null
    override fun garminConnectStoreIntent(): Intent = Intent()
    override suspend fun sleepOrigins(lookbackDays: Long): List<String> = origins

    companion object {
        const val SLEEP_PERMISSION = "android.permission.health.READ_SLEEP"
        val ALL_GRANTED = HealthPermissionState(
            granted = setOf(SLEEP_PERMISSION),
            sleepGranted = true,
            optionalGranted = emptySet(),
            historyGranted = true,
            backgroundGranted = true,
            historyFeatureAvailable = true,
            backgroundFeatureAvailable = true,
        )
        val NONE_GRANTED = ALL_GRANTED.copy(
            granted = emptySet(),
            sleepGranted = false,
            historyGranted = false,
            backgroundGranted = false,
        )
    }
}

/** Convenience: wait for the first Ready state. */
suspend fun InsightsRepository.awaitReady(): InsightsState.Ready =
    state.first { it is InsightsState.Ready } as InsightsState.Ready
