package com.sleepsaver.feature.sleep

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.healthconnect.HealthConnectGateway
import com.sleepsaver.core.data.healthconnect.HealthConnectStatus
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.DemoModeController
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.ReviewRepository
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SyncRepository
import com.sleepsaver.core.data.repository.SyncTrigger
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.LastNightState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The home screen: insights + sync + settings + Health Connect status rolled into one [SleepUiState].
 * One-shot actions are plain functions; the ones that leave the app return the [Intent] to launch.
 */
@HiltViewModel
class SleepViewModel @Inject constructor(
    @ApplicationContext context: Context,
    insightsRepository: InsightsRepository,
    private val syncRepository: SyncRepository,
    private val settingsRepository: SettingsRepository,
    private val habitsRepository: HabitsRepository,
    reviewRepository: ReviewRepository,
    private val demoModeController: DemoModeController,
    private val healthConnect: HealthConnectGateway,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val packageManager: PackageManager = context.packageManager
    private val appLabels = mutableMapOf<String, String>()

    private val healthConnectCheck = MutableStateFlow<HealthConnectCheck?>(null)
    private val refreshing = MutableStateFlow(false)

    /** Set when "Sync now" sent the user to Garmin Connect: sync once they come back. */
    private var syncOnResume = false

    private val extras = combine(healthConnectCheck, reviewRepository.pendingReview, refreshing, ::Triple)

    val uiState: StateFlow<SleepUiState> = combine(
        insightsRepository.state,
        settingsRepository.settings,
        syncRepository.state,
        habitsRepository.habitSettings,
        extras,
    ) { insights, settings, sync, habits, (check, pendingReview, isRefreshing) ->
        when (insights) {
            InsightsState.Loading -> SleepUiState.Loading
            InsightsState.NoNights -> when {
                // Don't flash the wrong fix-it screen before Health Connect has been checked.
                check == null && !settings.demoMode -> SleepUiState.Loading
                else -> SleepUiState.Empty(
                    today = timeProvider.now().atZone(timeProvider.zone()).toLocalDate(),
                    reason = emptyReason(settings.demoMode, check ?: HealthConnectCheck(HealthConnectStatus.AVAILABLE, true)),
                    isSyncing = sync.isSyncing,
                    isRefreshing = isRefreshing,
                    syncOpensGarmin = settings.syncOpensGarmin(),
                )
            }
            is InsightsState.Ready -> readyState(
                insights = insights.insights,
                settings = settings,
                sync = sync,
                habitSettings = habits,
                pendingReview = pendingReview,
                source = sourceLabel(settings, ::appLabel),
                isRefreshing = isRefreshing,
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

    /** The screen came (back) to the foreground. */
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
        return healthConnect.garminConnectLaunchIntent() ?: healthConnect.garminConnectStoreIntent()
    }

    /** The intent from [syncNowIntent] couldn't be started: sync directly instead. */
    fun onExternalLaunchFailed() {
        syncOnResume = false
        syncNow()
    }

    /** Play Store page to install or update Health Connect. The status is re-checked on resume. */
    fun installHealthConnectIntent(): Intent = healthConnect.installOrUpdateIntent()

    fun onTryDemo() {
        viewModelScope.launch { demoModeController.setDemoMode(true) }
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
            val status = runCatching { healthConnect.status() }.getOrDefault(HealthConnectStatus.AVAILABLE)
            val sleepGranted = status == HealthConnectStatus.AVAILABLE &&
                runCatching { healthConnect.permissionState().sleepGranted }.getOrDefault(false)
            healthConnectCheck.value = HealthConnectCheck(status, sleepGranted)
        }
    }

    /** The other app's name as the launcher shows it, falling back to a readable package name. */
    private fun appLabel(packageName: String): String = appLabels.getOrPut(packageName) {
        val installed = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()
        installed?.takeIf { it.isNotBlank() } ?: fallbackAppLabel(packageName)
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
