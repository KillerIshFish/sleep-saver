package com.sleepsaver.feature.onboarding

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.healthconnect.HealthConnectGateway
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.DemoModeController
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SleepRepository
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.data.repository.SyncRepository
import com.sleepsaver.core.data.repository.SyncResult
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.data.repository.SyncStatus
import com.sleepsaver.core.data.repository.SyncTrigger
import com.sleepsaver.core.domain.habits.HabitCatalog
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepNeedEstimateResult
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.feature.onboarding.SleepNeedUiState.Companion.DEFAULT_NEED_MINUTES
import com.sleepsaver.feature.onboarding.SleepNeedUiState.Companion.MAX_NEED_MINUTES
import com.sleepsaver.feature.onboarding.SleepNeedUiState.Companion.MIN_NEED_MINUTES
import com.sleepsaver.feature.onboarding.connect.ConnectionModel
import com.sleepsaver.feature.onboarding.connect.guarded
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * The five onboarding steps. Writes settings only when the user moves on (sleep need and wake time on
 * "Continue", habits and "onboarding done" on "Finish"), so going back and forth never half-saves anything.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    gateway: HealthConnectGateway,
    private val settingsRepository: SettingsRepository,
    private val syncRepository: SyncRepository,
    sleepRepository: SleepRepository,
    insightsRepository: InsightsRepository,
    private val habitsRepository: HabitsRepository,
    private val demoModeController: DemoModeController,
    private val timeProvider: TimeProvider,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private enum class ImportPhase { NOT_STARTED, RUNNING, DONE, FAILED, DEMO }

    /** What the user has done here; everything else is derived from the repositories. */
    private data class Local(
        val step: OnboardingStep,
        val importPhase: ImportPhase = ImportPhase.NOT_STARTED,
        val importError: SyncError? = null,
        /** null = not touched: follow the saved setting. */
        val needMinutes: Int? = null,
        /** null = not touched: follow the recent nights (or the saved setting). */
        val wake: LocalTime? = null,
        /** null = not touched: follow the saved habit settings. */
        val selected: Set<HabitType>? = null,
        val remind: Boolean? = null,
        val busy: Boolean = false,
        val finished: Boolean = false,
    )

    private data class Sources(
        val nights: List<Night>,
        val insights: InsightsState,
        val habits: List<HabitSettings>,
        val settings: AppSettings,
    )

    private val connection = ConnectionModel(gateway, settingsRepository, viewModelScope)
    private val zone: ZoneId = guarded(ZoneId.systemDefault()) { timeProvider.zone() }
    private val today: LocalDate = guarded(LocalDate.now(zone)) { timeProvider.now().atZone(zone).toLocalDate() }
    private val local = MutableStateFlow(restoreLocal())
    private var importJob: Job? = null
    private var needSaveJob: Job? = null

    private val sources = combine(
        sleepRepository.nights.catch { emit(emptyList()) },
        // StateFlows never fail; the other three are database/DataStore flows that can.
        insightsRepository.state,
        habitsRepository.habitSettings.catch { emit(HabitCatalog().defaultSettings()) },
        settingsRepository.settings.catch { emit(AppSettings()) },
        ::Sources,
    )

    val uiState: StateFlow<OnboardingUiState> = combine(
        local,
        connection.state,
        syncRepository.state,
        sources,
    ) { local, connection, sync, sources ->
        OnboardingUiState(
            step = local.step,
            connection = connection,
            import = importState(local, sync, sources.nights),
            sleepNeed = sleepNeedState(local, sources),
            habits = habitsState(local, sources),
            busy = local.busy,
            finished = local.finished,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, OnboardingUiState(step = local.value.step))

    val permissionContract: ActivityResultContract<Set<String>, Set<String>> get() = connection.permissionContract

    init {
        // What the user picked survives process death too, not only the step.
        local.onEach(::saveLocal).launchIn(viewModelScope)
        connection.refresh()
        // Restored on the import step after process death: pick the import back up.
        if (local.value.step == OnboardingStep.IMPORT) resumeImport()
    }

    // ------------------------------------------------------------ navigation

    fun start() = goTo(OnboardingStep.WELCOME, OnboardingStep.CONNECT)

    /** One step back; false on the first step (the system then handles back). */
    fun back(): Boolean {
        val step = local.value.step
        if (step == OnboardingStep.WELCOME || local.value.busy) return false
        val previous = OnboardingStep.entries[step.ordinal - 1]
        setStep(previous)
        // Back onto an import that never ran in this process (restored further on): run it rather than spin.
        if (previous == OnboardingStep.IMPORT) resumeImport()
        return true
    }

    fun onResume() {
        if (local.value.step == OnboardingStep.CONNECT) connection.refresh()
    }

    // ------------------------------------------------------------ step 2: connect

    suspend fun permissionsToRequest(): Set<String> = connection.permissionsToRequest()

    fun onPermissionsResult() {
        connection.onPermissionsResult()
    }

    fun onPermissionsUnavailable() {
        connection.onPermissionsUnavailable()
    }

    fun checkAgain() {
        connection.refresh(showProgress = true)
    }

    fun useSource(selection: SourceSelection) = connection.useSource(selection)

    fun installOrUpdateIntent(): Intent? = connection.installOrUpdateIntent()

    fun manageDataIntent(): Intent? = connection.manageDataIntent()

    /** Garmin Connect, then its store page as the fallback. */
    fun garminConnectIntents(): List<Intent> = connection.garminConnectIntents()

    /** Continue with the user's own Health Connect data (leaving demo mode if it was on). */
    fun continueFromConnect() {
        if (local.value.step != OnboardingStep.CONNECT || local.value.busy) return
        if (!uiState.value.connection.isConnected) return
        viewModelScope.launch {
            if (connection.state.value.demoMode) {
                local.update { it.copy(busy = true) }
                guarded(Unit) { demoModeController.setDemoMode(false) }
                local.update { it.copy(busy = false, importPhase = ImportPhase.NOT_STARTED) }
            }
            setStep(OnboardingStep.IMPORT)
            if (local.value.importPhase != ImportPhase.DONE) startImport()
        }
    }

    /** Skip Health Connect: load the demo nights and go straight on (nothing to import). */
    fun exploreDemo() {
        if (local.value.step != OnboardingStep.CONNECT || local.value.busy) return
        viewModelScope.launch {
            local.update { it.copy(busy = true) }
            guarded(Unit) { demoModeController.setDemoMode(true) }
            importJob?.cancel()
            local.update { it.copy(busy = false, importPhase = ImportPhase.DEMO, importError = null) }
            setStep(OnboardingStep.IMPORT)
        }
    }

    // ------------------------------------------------------------ step 3: import

    fun retryImport() {
        if (local.value.importPhase == ImportPhase.FAILED) startImport()
    }

    fun continueFromImport() {
        if (local.value.step != OnboardingStep.IMPORT || local.value.importPhase == ImportPhase.RUNNING) return
        setStep(OnboardingStep.SLEEP_NEED)
    }

    /** The import step with nothing run yet in this process: demo data is instant, otherwise import. */
    private fun resumeImport() {
        if (local.value.importPhase != ImportPhase.NOT_STARTED || importJob?.isActive == true) return
        viewModelScope.launch {
            if (guarded(false) { settingsRepository.current().demoMode }) {
                local.update { it.copy(importPhase = ImportPhase.DEMO) }
            } else {
                startImport()
            }
        }
    }

    private fun startImport() {
        if (importJob?.isActive == true) return
        local.update { it.copy(importPhase = ImportPhase.RUNNING, importError = null) }
        importJob = viewModelScope.launch {
            val result = guarded(SyncResult(success = false, error = SyncError.UNKNOWN)) { syncRepository.importHistory() }
            if (!result.success) {
                local.update { it.copy(importPhase = ImportPhase.FAILED, importError = result.error) }
                return@launch
            }
            local.update { it.copy(importPhase = ImportPhase.DONE) }
            // Catch anything that arrived during the import (and set up the changes feed).
            guarded(null) { syncRepository.sync(SyncTrigger.ONBOARDING) }
        }
    }

    // ------------------------------------------------------------ step 4: sleep need & wake time

    fun setNeed(minutes: Int) = local.update { it.copy(needMinutes = minutes.coerceIn(MIN_NEED_MINUTES, MAX_NEED_MINUTES)) }

    fun useSuggestion() {
        val suggestion = uiState.value.sleepNeed.suggestion ?: return
        setNeed(suggestion.minutes)
    }

    fun setWake(time: LocalTime) = local.update { it.copy(wake = time.withSecond(0).withNano(0)) }

    fun continueFromSleepNeed() {
        if (local.value.step != OnboardingStep.SLEEP_NEED) return
        val need = uiState.value.sleepNeed
        // Keep what's on screen even if the repositories change while saving.
        local.update { it.copy(needMinutes = need.needMinutes, wake = need.wake) }
        val previousSave = needSaveJob
        needSaveJob = viewModelScope.launch {
            previousSave?.join()
            guarded(Unit) { settingsRepository.setSleepNeed(Duration.ofMinutes(need.needMinutes.toLong())) }
            guarded(Unit) { settingsRepository.setTypicalWake(need.wake) }
        }
        setStep(OnboardingStep.HABITS)
    }

    // ------------------------------------------------------------ step 5: habits

    fun toggleHabit(type: HabitType) {
        val current = uiState.value.habits.options.filter { it.selected }.map { it.type }.toSet()
        local.update { it.copy(selected = if (type in current) current - type else current + type) }
    }

    fun setRemind(enabled: Boolean) = local.update { it.copy(remind = enabled) }

    /** Saves the habits, marks onboarding done and starts background sync. Then [OnboardingUiState.finished]. */
    fun finish() {
        if (local.value.step != OnboardingStep.HABITS || local.value.busy || local.value.finished) return
        val habits = uiState.value.habits
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            // Finishing leaves the screen and clears this ViewModel: the sleep need must be stored by then.
            needSaveJob?.join()
            habits.options.forEach { option ->
                guarded(Unit) { habitsRepository.setEnabled(option.type, option.selected) }
                guarded(Unit) { habitsRepository.setReminder(option.type, habits.remind) }
            }
            guarded(Unit) { settingsRepository.setOnboardingDone(true) }
            guarded(Unit) { syncRepository.ensurePeriodicSync() }
            local.update { it.copy(busy = false, finished = true) }
        }
    }

    // ------------------------------------------------------------ state

    private fun goTo(from: OnboardingStep, to: OnboardingStep) {
        if (local.value.step == from) setStep(to)
    }

    private fun setStep(step: OnboardingStep) {
        local.update { it.copy(step = step) }
        if (step == OnboardingStep.CONNECT) connection.refresh()
    }

    /** Mirrors the user's choices into [savedStateHandle]. A running or failed import is simply run again. */
    private fun saveLocal(local: Local) {
        savedStateHandle[KEY_STEP] = local.step.name
        savedStateHandle[KEY_IMPORT] = local.importPhase.takeIf { it == ImportPhase.DONE || it == ImportPhase.DEMO }?.name
        savedStateHandle[KEY_NEED] = local.needMinutes
        savedStateHandle[KEY_WAKE] = local.wake?.let { it.hour * 60 + it.minute }
        savedStateHandle[KEY_SELECTED] = local.selected?.mapTo(ArrayList()) { it.name }
        savedStateHandle[KEY_REMIND] = local.remind
    }

    private fun restoreLocal(): Local = guarded(Local(step = OnboardingStep.WELCOME)) {
        Local(
            step = savedStateHandle.get<String>(KEY_STEP)?.let { enumOrNull<OnboardingStep>(it) } ?: OnboardingStep.WELCOME,
            importPhase = savedStateHandle.get<String>(KEY_IMPORT)?.let { enumOrNull<ImportPhase>(it) } ?: ImportPhase.NOT_STARTED,
            needMinutes = savedStateHandle.get<Int>(KEY_NEED),
            wake = savedStateHandle.get<Int>(KEY_WAKE)?.takeIf { it in 0 until 24 * 60 }?.let { LocalTime.of(it / 60, it % 60) },
            selected = savedStateHandle.get<ArrayList<String>>(KEY_SELECTED)?.mapNotNullTo(mutableSetOf()) { enumOrNull<HabitType>(it) },
            remind = savedStateHandle.get<Boolean>(KEY_REMIND),
        )
    }

    private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }

    private fun importState(local: Local, sync: SyncState, nights: List<Night>): ImportUiState = when (local.importPhase) {
        ImportPhase.NOT_STARTED -> ImportUiState.NotStarted
        ImportPhase.RUNNING -> ImportUiState.Importing((sync.status as? SyncStatus.Importing)?.progress)
        ImportPhase.DONE -> ImportUiState.Done(summarize(nights, today))
        ImportPhase.DEMO -> ImportUiState.Demo(summarize(nights, today))
        ImportPhase.FAILED -> ImportUiState.Failed(local.importError)
    }

    private fun sleepNeedState(local: Local, sources: Sources): SleepNeedUiState {
        val saved = sources.settings.sleep
        val nightsWake = medianWake(sources.nights)
        val estimate = ((sources.insights as? InsightsState.Ready)?.insights?.needEstimate as? SleepNeedEstimateResult.Available)?.estimate
        return SleepNeedUiState(
            needMinutes = (local.needMinutes ?: saved.sleepNeed.toMinutes().toInt().takeIf { it > 0 } ?: DEFAULT_NEED_MINUTES)
                .coerceIn(MIN_NEED_MINUTES, MAX_NEED_MINUTES),
            suggestion = estimate?.let {
                NeedSuggestion(
                    minutes = it.need.toMinutes().toInt().coerceIn(MIN_NEED_MINUTES, MAX_NEED_MINUTES),
                    confidence = it.confidence,
                    freeNights = it.freeNightsUsed,
                )
            },
            wake = local.wake ?: nightsWake ?: saved.typicalWake,
            wakeFromNights = local.wake == null && nightsWake != null,
            date = today,
            zone = zone,
        )
    }

    private fun habitsState(local: Local, sources: Sources): HabitsUiState {
        val saved = sources.habits.associateBy { it.type }
        val catalog = HabitCatalog()
        val enabled = local.selected ?: HabitType.entries.filterTo(mutableSetOf()) { type ->
            saved[type]?.enabled ?: catalog.definition(type).defaultEnabled
        }
        val times = (sources.insights as? InsightsState.Ready)?.insights?.habits?.associate { it.type to it.time }.orEmpty()
        val remind = local.remind ?: saved.values.filter { it.enabled }.let { list -> list.isEmpty() || list.any { it.reminderEnabled } }
        return HabitsUiState(
            options = catalog.all.map { HabitOption(it.type, selected = it.type in enabled, time = times[it.type]) },
            remind = remind,
            zone = (sources.insights as? InsightsState.Ready)?.insights?.zone ?: zone,
        )
    }

    private companion object {
        const val KEY_STEP = "onboarding_step"
        const val KEY_IMPORT = "onboarding_import"
        const val KEY_NEED = "onboarding_need_minutes"
        const val KEY_WAKE = "onboarding_wake_minute"
        const val KEY_SELECTED = "onboarding_habits"
        const val KEY_REMIND = "onboarding_remind"
    }
}
