package com.sleepsaver.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.di.IoDispatcher
import com.sleepsaver.core.data.healthconnect.HealthConnectGateway
import com.sleepsaver.core.data.healthconnect.HealthConnectStatus
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.ClockFormat
import com.sleepsaver.core.data.repository.DataResetter
import com.sleepsaver.core.data.repository.DemoModeController
import com.sleepsaver.core.data.repository.ExportRepository
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SleepRepository
import com.sleepsaver.core.data.repository.SyncRepository
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.data.repository.SyncStatus
import com.sleepsaver.core.data.repository.SyncTrigger
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.SleepNeedEstimateResult
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.feature.settings.platform.SettingsPlatform
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.time.Duration
import java.time.LocalTime
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    sleepRepository: SleepRepository,
    private val syncRepository: SyncRepository,
    insightsRepository: InsightsRepository,
    private val demoModeController: DemoModeController,
    private val exportRepository: ExportRepository,
    private val dataResetter: DataResetter,
    private val healthConnect: HealthConnectGateway,
    private val platform: SettingsPlatform,
    private val timeProvider: TimeProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    /** State that only lives on this screen: system checks and work in flight. */
    private data class Local(
        val healthConnect: HealthConnectAccess = HealthConnectAccess.CHECKING,
        val notifications: NotificationAccess = NotificationAccess.ALLOWED,
        val appVersion: String = "",
        val demoSwitching: Boolean = false,
        val syncing: Boolean = false,
        val syncFeedback: SyncFeedback? = null,
        val exporting: ExportFormat? = null,
        val resetting: Boolean = false,
        val resetDone: Boolean = false,
        val message: SettingsMessage? = null,
    )

    private val local = MutableStateFlow(Local())

    /**
     * Garmin and "All sources" first, then every other app that wrote sleep, with its name. The current choice
     * stays listed even when its app no longer reports sleep.
     */
    private val sourceOptions = combine(
        sleepRepository.origins.map { it.distinct() }.distinctUntilChanged(),
        settingsRepository.settings.map { it.sleep.source }.distinctUntilChanged(),
    ) { origins, selected ->
        val others = (origins + listOfNotNull((selected as? SourceSelection.Single)?.packageName))
            .distinct()
            .filter { it != SleepConfig.GARMIN_CONNECT_PACKAGE && it != SleepConfig.DEMO_ORIGIN }
            .map { pkg ->
                SourceOption(SourceSelection.Single(pkg), SourceKind.APP, appLabel = labelOf(pkg), hasData = pkg in origins)
            }
            .sortedBy { (it.appLabel ?: it.packageName.orEmpty()).lowercase() }
        SourceOption.defaults(garminHasData = SleepConfig.GARMIN_CONNECT_PACKAGE in origins) + others
    }
        .flowOn(ioDispatcher)
        .catch { emit(SourceOption.defaults()) }

    private val insightsBits = insightsRepository.state.map { it.toInsightsBits() }.distinctUntilChanged()

    private val clock = timeProvider.minuteTicks.distinctUntilChanged()

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        sourceOptions,
        syncRepository.state,
        insightsBits,
        local,
    ) { settings, options, sync, insights, local ->
        build(settings, options, sync, insights, local)
    }
        .combine(clock) { state, now -> state.copy(now = now, zone = timeProvider.zone()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        refreshSystemStatus()
    }

    // ------------------------------------------------------------------ Sleep

    fun setSleepNeed(minutes: Int) {
        val need = minutes.roundTo(SettingsLimits.NEED_STEP_MINUTES)
            .coerceIn(SettingsLimits.NEED_MIN_MINUTES, SettingsLimits.NEED_MAX_MINUTES)
        viewModelScope.launch { settingsRepository.setSleepNeed(Duration.ofMinutes(need.toLong())) }
    }

    /** Applies the suggestion shown on screen (whatever the current estimate is). */
    fun useSuggestedNeed() {
        val suggestion = uiState.value.needSuggestion ?: return
        viewModelScope.launch { settingsRepository.setSleepNeed(suggestion.need) }
    }

    fun setTypicalWake(time: LocalTime) {
        val minuteOfDay = (time.hour * 60 + time.minute).roundTo(SettingsLimits.WAKE_STEP_MINUTES) % MINUTES_PER_DAY
        viewModelScope.launch { settingsRepository.setTypicalWake(LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)) }
    }

    // ------------------------------------------------------------------ Goals

    fun setStreakThreshold(minutes: Int) {
        val threshold = minutes.roundTo(SettingsLimits.STREAK_STEP_MINUTES)
            .coerceIn(SettingsLimits.STREAK_MIN_MINUTES, SettingsLimits.STREAK_MAX_MINUTES)
        viewModelScope.launch { settingsRepository.setStreakThreshold(Duration.ofMinutes(threshold.toLong())) }
    }

    /**
     * Saves the Low/High boundaries. Returns false (and saves nothing) unless
     * min ≤ [lowMaxMinutes] < [highMinMinutes] ≤ max.
     */
    fun setDebtLevels(lowMaxMinutes: Int, highMinMinutes: Int): Boolean {
        if (!isValidDebtLevels(lowMaxMinutes, highMinMinutes)) return false
        viewModelScope.launch {
            settingsRepository.setDebtThresholds(
                lowMax = Duration.ofMinutes(lowMaxMinutes.toLong()),
                highMin = Duration.ofMinutes(highMinMinutes.toLong()),
            )
        }
        return true
    }

    // ------------------------------------------------------------------ Data

    fun selectSource(source: SourceSelection) {
        viewModelScope.launch { settingsRepository.setSource(source) }
    }

    /** Loads or clears the demo nights. Not cancelled by leaving the screen: a half switch would mix data. */
    fun setDemoMode(enabled: Boolean) {
        if (local.value.demoSwitching) return
        local.update { it.copy(demoSwitching = true) }
        viewModelScope.launch {
            val ok = attempt { withContext(NonCancellable) { demoModeController.setDemoMode(enabled) } }
            local.update { it.copy(demoSwitching = false, message = if (ok) it.message else SettingsMessage.DEMO_FAILED) }
        }
    }

    fun syncNow() {
        if (local.value.syncing) return
        local.update { it.copy(syncing = true, syncFeedback = null) }
        viewModelScope.launch {
            val result = syncRepository.sync(SyncTrigger.MANUAL)
            val feedback = if (result.success) SyncFeedback.Success(result.sessionsChanged) else SyncFeedback.Failed(result.error)
            local.update { it.copy(syncing = false, syncFeedback = feedback) }
            refreshHealthConnect()
        }
    }

    /**
     * Writes an export into the stream [open] returns (null = nothing to write to), on the IO dispatcher, and
     * closes it. On failure [discard] is called so a half-written file doesn't stay behind.
     */
    fun export(format: ExportFormat, open: () -> OutputStream?, discard: () -> Unit = {}) {
        if (local.value.exporting != null) return
        local.update { it.copy(exporting = format) }
        viewModelScope.launch {
            val ok = attempt {
                withContext(ioDispatcher) {
                    val stream = checkNotNull(open()) { "No stream to write the export to" }
                    stream.use {
                        when (format) {
                            ExportFormat.CSV -> exportRepository.exportCsv(it)
                            ExportFormat.JSON -> exportRepository.exportJson(it)
                        }
                    }
                }
            }
            if (!ok) withContext(ioDispatcher) { attempt { discard() } }
            val message = when {
                !ok -> SettingsMessage.EXPORT_FAILED
                format == ExportFormat.CSV -> SettingsMessage.EXPORTED_CSV
                else -> SettingsMessage.EXPORTED_JSON
            }
            local.update { it.copy(exporting = null, message = message) }
        }
    }

    // ------------------------------------------------------------------ Notifications and display

    fun setMorningReviewEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setMorningReviewEnabled(enabled) }
    }

    fun setClockFormat(format: ClockFormat) {
        viewModelScope.launch { settingsRepository.setClockFormat(format) }
    }

    // ------------------------------------------------------------------ Reset

    /** Deletes everything, then sets [SettingsUiState.resetDone]. Not cancelled by leaving the screen. */
    fun resetAll() {
        if (local.value.resetting || local.value.resetDone) return
        local.update { it.copy(resetting = true) }
        viewModelScope.launch {
            val ok = attempt { withContext(NonCancellable) { dataResetter.resetAll() } }
            local.update {
                it.copy(resetting = false, resetDone = ok, message = if (ok) it.message else SettingsMessage.RESET_FAILED)
            }
        }
    }

    // ------------------------------------------------------------------ Screen plumbing

    fun messageShown() = local.update { it.copy(message = null) }

    /** For problems only the screen can see (e.g. no app to open a system page). */
    fun showMessage(message: SettingsMessage) = local.update { it.copy(message = message) }

    /** Re-reads Health Connect and notification access: call when the screen resumes. */
    fun refreshSystemStatus() {
        local.update {
            it.copy(
                notifications = attemptOr(it.notifications) { platform.notificationAccess() },
                appVersion = it.appVersion.ifEmpty { attemptOr("") { platform.appVersion() } },
            )
        }
        viewModelScope.launch { refreshHealthConnect() }
    }

    private suspend fun refreshHealthConnect() {
        val access = try {
            withContext(ioDispatcher) {
                when (healthConnect.status()) {
                    HealthConnectStatus.AVAILABLE ->
                        if (healthConnect.permissionState().sleepGranted) {
                            HealthConnectAccess.CONNECTED
                        } else {
                            HealthConnectAccess.PERMISSION_NEEDED
                        }
                    HealthConnectStatus.NOT_INSTALLED -> HealthConnectAccess.NOT_INSTALLED
                    HealthConnectStatus.UPDATE_REQUIRED -> HealthConnectAccess.UPDATE_REQUIRED
                    HealthConnectStatus.NOT_SUPPORTED -> HealthConnectAccess.NOT_SUPPORTED
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            HealthConnectAccess.NOT_SUPPORTED
        }
        local.update { it.copy(healthConnect = access) }
    }

    private fun labelOf(packageName: String): String? = attemptOr(null) { platform.appLabel(packageName) }

    private fun build(
        settings: AppSettings,
        options: List<SourceOption>,
        sync: SyncState,
        insights: InsightsBits,
        local: Local,
    ): SettingsUiState {
        val sleep = settings.sleep
        val suggestion = insights.suggestion?.takeIf { it.need.toMinutes() != sleep.sleepNeed.toMinutes() }
        return SettingsUiState(
            loading = false,
            sleepNeed = sleep.sleepNeed,
            typicalWake = sleep.typicalWake,
            learnedWake = insights.learnedWake,
            needSuggestion = suggestion,
            streakThreshold = sleep.streakThreshold,
            lowDebtMax = sleep.lowDebtMax,
            highDebtMin = sleep.highDebtMin,
            source = sleep.source,
            sourceOptions = options,
            demoMode = settings.demoMode,
            demoSwitching = local.demoSwitching,
            healthConnect = local.healthConnect,
            sync = SyncUi(
                lastSyncedAt = sync.lastSuccessAt,
                inProgress = local.syncing || sync.status != SyncStatus.Idle,
                feedback = local.syncFeedback,
                lastError = sync.lastError,
            ),
            exporting = local.exporting,
            morningReviewEnabled = settings.morningReviewEnabled,
            notifications = local.notifications,
            clockFormat = settings.clockFormat,
            appVersion = local.appVersion,
            resetting = local.resetting,
            resetDone = local.resetDone,
            message = local.message,
        )
    }

    private data class InsightsBits(val suggestion: NeedSuggestion?, val learnedWake: LocalTime?)

    private fun InsightsState.toInsightsBits(): InsightsBits {
        val insights = (this as? InsightsState.Ready)?.insights ?: return InsightsBits(null, null)
        val estimate = (insights.needEstimate as? SleepNeedEstimateResult.Available)?.estimate
        val anchor = insights.schedule.anchor
        return InsightsBits(
            suggestion = estimate?.let { NeedSuggestion(it.need, it.confidence, it.freeNightsUsed) },
            learnedWake = anchor.typicalWake.takeUnless { anchor.isFallback },
        )
    }

    private companion object {
        const val MINUTES_PER_DAY = 24 * 60

        fun isValidDebtLevels(low: Int, high: Int): Boolean =
            low >= SettingsLimits.DEBT_LEVEL_MIN_MINUTES && high <= SettingsLimits.DEBT_LEVEL_MAX_MINUTES && low < high

        fun Int.roundTo(step: Int): Int = Math.floorDiv(this + step / 2, step) * step

        /** Runs [block]; false when it threw (cancellation still propagates). */
        inline fun attempt(block: () -> Unit): Boolean = try {
            block()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

        inline fun <T> attemptOr(fallback: T, block: () -> T): T = try {
            block()
        } catch (_: Exception) {
            fallback
        }
    }
}
