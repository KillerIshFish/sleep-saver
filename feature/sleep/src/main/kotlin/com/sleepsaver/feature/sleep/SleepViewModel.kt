package com.sleepsaver.feature.sleep

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.di.IoDispatcher
import com.sleepsaver.core.data.healthconnect.HealthConnectGateway
import com.sleepsaver.core.data.healthconnect.HealthConnectStatus
import com.sleepsaver.core.data.healthconnect.syncOpensGarmin
import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.DemoModeController
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.NightLogRepository
import com.sleepsaver.core.data.repository.NightStepsRepository
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.data.repository.ReviewRepository
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SyncRepository
import com.sleepsaver.core.data.repository.SyncTrigger
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.LastNightState
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.nightsteps.NightStepsSession
import com.sleepsaver.core.domain.schedule.NightRules
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * The home screen: insights + sync + settings + Health Connect status rolled into one [SleepUiState], plus tonight
 * (the upcoming alarms, tonight's Night steps and the night state's anchor) for the "Tonight" row and night mode.
 * One-shot actions are plain functions; the ones that leave the app return the [Intent] to launch.
 * Health Connect and PackageManager calls run on [ioDispatcher], never on the main thread.
 */
@HiltViewModel
class SleepViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val savedStateHandle: SavedStateHandle,
    insightsRepository: InsightsRepository,
    private val syncRepository: SyncRepository,
    private val settingsRepository: SettingsRepository,
    private val habitsRepository: HabitsRepository,
    reviewRepository: ReviewRepository,
    private val demoModeController: DemoModeController,
    private val healthConnect: HealthConnectGateway,
    scheduleRepository: ScheduleRepository,
    nightStepsRepository: NightStepsRepository,
    nightController: NightController,
    nightLogRepository: NightLogRepository,
    private val timeProvider: TimeProvider,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val packageManager: PackageManager = context.packageManager
    private val appLabels = ConcurrentHashMap<String, String>()

    private val healthConnectCheck = MutableStateFlow<HealthConnectCheck?>(null)
    private val refreshing = MutableStateFlow(false)

    /** "Try demo data" is loading the demo nights: show the loading state rather than an empty screen. */
    private val demoLoading = MutableStateFlow(false)

    /**
     * Set when "Sync now" sent the user to Garmin Connect: sync once they come back. Saved, so it survives the
     * process being killed while Garmin Connect is in front.
     */
    private var syncOnResume: Boolean
        get() = savedStateHandle[KEY_SYNC_ON_RESUME] ?: false
        set(value) {
            savedStateHandle[KEY_SYNC_ON_RESUME] = value
        }

    /**
     * Tonight's alarm and Night steps never hold the home screen back: if either can't be read, the row simply shows
     * less. Night mode reads the anchor from the night state (so "I'm up" ends it at once), never from the anchor table,
     * and ends as soon as this morning's planned alarm was stopped or timed out.
     */
    private val tonight = combine(
        scheduleRepository.upcomingAlarms.catch { emit(emptyList()) },
        nightStepsRepository.tonight.catch { emit(null) },
        nightController.state.map { it.night?.anchoredAt }.distinctUntilChanged(),
        timeProvider.minuteTicks.map { it.atZone(timeProvider.zone()).toLocalDate() }
            .distinctUntilChanged()
            .flatMapLatest { today -> nightLogRepository.alarmEvent(today).map { NightRules.morningStop(it, today) } }
            .catch { emit(null) },
        ::TonightInputs,
    )

    private val local = combine(healthConnectCheck, reviewRepository.pendingReview, refreshing, demoLoading, tonight, ::LocalState)

    val uiState: StateFlow<SleepUiState> = combine(
        insightsRepository.state,
        settingsRepository.settings,
        syncRepository.state,
        habitsRepository.habitSettings,
        local,
    ) { insights, settings, sync, habits, local ->
        when (insights) {
            InsightsState.Loading -> SleepUiState.Loading
            InsightsState.NoNights -> when {
                // Don't flash the wrong fix-it screen before Health Connect has been checked,
                // nor "no data" while the demo nights are being written.
                local.healthConnect == null && !settings.demoMode -> SleepUiState.Loading
                local.demoLoading -> SleepUiState.Loading
                else -> SleepUiState.Empty(
                    today = timeProvider.now().atZone(timeProvider.zone()).toLocalDate(),
                    reason = emptyReason(settings.demoMode, local.healthConnect ?: HealthConnectCheck(HealthConnectStatus.AVAILABLE, true)),
                    isSyncing = sync.isSyncing,
                    isRefreshing = local.refreshing,
                    syncOpensGarmin = settings.syncOpensGarmin(),
                    canTryDemo = !settings.demoMode,
                )
            }
            is InsightsState.Ready -> readyState(
                insights = insights.insights,
                settings = settings,
                sync = sync,
                habitSettings = habits,
                pendingReview = local.pendingReview,
                source = sourceLabel(settings) { packageName -> appLabel(packageName) },
                isRefreshing = local.refreshing,
                alarms = local.tonight.alarms,
                steps = local.tonight.steps,
                anchoredAt = local.tonight.anchoredAt,
                morningStop = local.tonight.morningStop,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SleepUiState.Loading)

    init {
        refreshHealthConnect()
        // While last night is expected but not in, keep retrying in the background (once per day is enough).
        viewModelScope.launch {
            insightsRepository.state
                .map { state ->
                    (state as? InsightsState.Ready)?.insights
                        ?.takeIf { it.lastNightState == LastNightState.WAITING }
                        ?.today
                }
                .distinctUntilChanged()
                .collect { waitingFor -> if (waitingFor != null) syncRepository.startWaitingRetries() }
        }
    }

    /** The screen came (back) to the foreground: re-check Health Connect, and run a sync "Sync now" left pending. */
    fun onResume() {
        refreshHealthConnect()
        if (syncOnResume) {
            syncOnResume = false
            syncNow()
        }
    }

    /** Pull to refresh. */
    fun onRefresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                syncRepository.sync(SyncTrigger.MANUAL)
            } finally {
                refreshing.value = false
            }
            refreshHealthConnect()
        }
    }

    /** A plain Health Connect sync ("Sync now" in the data-source sheet). */
    fun syncNow() {
        viewModelScope.launch { syncRepository.sync(SyncTrigger.MANUAL) }
    }

    /**
     * "Sync now" while waiting for last night (or with no data yet). When Garmin is the source this returns the
     * intent that opens Garmin Connect (or its store page) so the watch syncs, and the Health Connect sync runs when
     * the screen resumes. Otherwise it syncs right away and returns null.
     */
    fun syncNowIntent(): Intent? {
        val opensGarmin = when (val state = uiState.value) {
            is SleepUiState.Ready -> state.syncOpensGarmin
            is SleepUiState.Empty -> state.syncOpensGarmin
            SleepUiState.Loading -> false
        }
        if (!opensGarmin) {
            syncNow()
            return null
        }
        syncOnResume = true
        return runCatching { healthConnect.garminConnectLaunchIntent() }.getOrNull()
            ?: healthConnect.garminConnectStoreIntent()
    }

    /** The intent from [syncNowIntent] couldn't be started: sync directly instead. */
    fun onExternalLaunchFailed() {
        syncOnResume = false
        syncNow()
    }

    /** Play Store page to install or update Health Connect. The status is re-checked on resume. */
    fun installHealthConnectIntent(): Intent = healthConnect.installOrUpdateIntent()

    fun onTryDemo() {
        if (demoLoading.value) return
        demoLoading.value = true
        viewModelScope.launch {
            try {
                demoModeController.setDemoMode(true)
            } finally {
                demoLoading.value = false
            }
        }
    }

    fun onUseSuggestedNeed() {
        val suggestion = (uiState.value as? SleepUiState.Ready)?.needSuggestion ?: return
        viewModelScope.launch { settingsRepository.setSleepNeed(suggestion.need) }
    }

    fun onDismissNeedSuggestion() {
        val ready = uiState.value as? SleepUiState.Ready ?: return
        val suggestion = ready.needSuggestion ?: return
        viewModelScope.launch { settingsRepository.dismissNeedSuggestion(suggestion.need, ready.now) }
    }

    fun onReminderChange(type: HabitType, enabled: Boolean) {
        viewModelScope.launch { habitsRepository.setReminder(type, enabled) }
    }

    private fun refreshHealthConnect() {
        viewModelScope.launch {
            healthConnectCheck.value = withContext(ioDispatcher) {
                val status = runCatching { healthConnect.status() }.getOrDefault(HealthConnectStatus.AVAILABLE)
                val sleepGranted = status == HealthConnectStatus.AVAILABLE &&
                    runCatching { healthConnect.permissionState().sleepGranted }.getOrDefault(false)
                HealthConnectCheck(status, sleepGranted)
            }
        }
    }

    /** The other app's name as the launcher shows it (looked up once, off the main thread), or a readable fallback. */
    private suspend fun appLabel(packageName: String): String = appLabels[packageName]
        ?: withContext(ioDispatcher) {
            val installed = runCatching {
                @Suppress("DEPRECATION") // The ApplicationInfoFlags overload needs API 33.
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
            }.getOrNull()
            installed?.takeIf { it.isNotBlank() } ?: fallbackAppLabel(packageName)
        }.also { appLabels[packageName] = it }

    private data class LocalState(
        val healthConnect: HealthConnectCheck?,
        val pendingReview: LocalDate?,
        val refreshing: Boolean,
        val demoLoading: Boolean,
        val tonight: TonightInputs,
    )

    private data class TonightInputs(
        val alarms: List<PlannedAlarm>,
        val steps: NightStepsSession?,
        val anchoredAt: Instant?,
        val morningStop: Instant?,
    )

    private companion object {
        const val KEY_SYNC_ON_RESUME = "sleep.syncOnResume"
    }
}

/** The data-source chip's label; [appLabel] names any other app. */
internal suspend fun sourceLabel(settings: AppSettings, appLabel: suspend (String) -> String): SourceLabel = when {
    settings.demoMode -> SourceLabel.Demo
    else -> when (val source = settings.sleep.source) {
        SourceSelection.All -> SourceLabel.AllSources
        is SourceSelection.Single ->
            if (source.packageName == SleepConfig.GARMIN_CONNECT_PACKAGE) SourceLabel.Garmin else SourceLabel.App(appLabel(source.packageName))
    }
}

/** For apps we can't see (Android 11+ package visibility) or that were uninstalled. */
internal fun fallbackAppLabel(packageName: String): String = KNOWN_APPS[packageName]
    ?: packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }.ifBlank { packageName }

private val KNOWN_APPS = mapOf(
    "com.sec.android.app.shealth" to "Samsung Health",
    "com.fitbit.FitbitMobile" to "Fitbit",
    "com.google.android.apps.fitness" to "Google Fit",
    "com.ouraring.oura" to "Oura",
    "com.urbandroid.sleep" to "Sleep as Android",
    "com.withings.wiscale2" to "Withings",
    "fi.polar.polarflow" to "Polar Flow",
    "com.huawei.health" to "Huawei Health",
    "com.xiaomi.wearable" to "Mi Fitness",
)
