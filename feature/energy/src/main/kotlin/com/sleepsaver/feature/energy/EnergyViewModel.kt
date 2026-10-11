package com.sleepsaver.feature.energy

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.di.IoDispatcher
import com.sleepsaver.core.data.healthconnect.HealthConnectGateway
import com.sleepsaver.core.data.healthconnect.garminSyncIntent
import com.sleepsaver.core.data.healthconnect.syncOpensGarmin
import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SleepRepository
import com.sleepsaver.core.data.repository.SyncRepository
import com.sleepsaver.core.data.repository.SyncTrigger
import com.sleepsaver.core.domain.model.HabitType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/**
 * The Energy tab: today's schedule from [InsightsRepository], the freshest nights from [SleepRepository] (so a
 * night that just synced is drawn right away), the minute clock for the now-line and the sync state (for the
 * empty state's reason and the "Sync now" action while last night is on its way). The upcoming alarms and the night
 * state's anchor give night mode, where the now pill shows no time.
 */
@HiltViewModel
class EnergyViewModel @Inject constructor(
    insightsRepository: InsightsRepository,
    private val sleepRepository: SleepRepository,
    settingsRepository: SettingsRepository,
    private val habitsRepository: HabitsRepository,
    private val syncRepository: SyncRepository,
    private val healthConnect: HealthConnectGateway,
    scheduleRepository: ScheduleRepository,
    nightController: NightController,
    private val savedStateHandle: SavedStateHandle,
    timeProvider: TimeProvider,
    /** Writes and syncs run here: never database or Health Connect work on the main thread. */
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val sources = combine(
        insightsRepository.state,
        sleepRepository.nights,
        settingsRepository.settings.map { it.sleep.sleepNeed }.distinctUntilChanged(),
    ) { insights, nights, need -> Triple(insights, nights, need) }

    /** Night mode only: tomorrow's alarm (if it can't be read, the night ends at the wake zone) and the anchor. */
    private val night = combine(
        scheduleRepository.upcomingAlarms.catch { emit(emptyList()) },
        nightController.state.map { it.night?.anchoredAt }.distinctUntilChanged(),
    ) { alarms, anchoredAt -> alarms to anchoredAt }

    val uiState: StateFlow<EnergyUiState> = combine(
        sources,
        timeProvider.minuteTicks,
        syncRepository.state,
        night,
    ) { (insights, nights, need), now, sync, (alarms, anchoredAt) ->
        when (insights) {
            InsightsState.Loading -> EnergyUiState.Loading
            InsightsState.NoNights -> EnergyUiState.NoNights(sync.emptyReason())
            is InsightsState.Ready ->
                energyReadyState(insights.insights, nights, need, now, sync.isSyncing, alarms = alarms, anchoredAt = anchoredAt)
        }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EnergyUiState.Loading)

    /** Long-press on last night's block: subtracts [start]..[end] from that night's sleep. */
    fun addAwake(wakeDate: LocalDate, start: Instant, end: Instant) {
        if (!end.isAfter(start)) return
        viewModelScope.launch(ioDispatcher) { sleepRepository.addAwake(wakeDate, start, end) }
    }

    fun setReminder(type: HabitType, enabled: Boolean) {
        viewModelScope.launch(ioDispatcher) { habitsRepository.setReminder(type, enabled) }
    }

    private val opensGarmin: StateFlow<Boolean> = settingsRepository.settings
        .map { it.syncOpensGarmin() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var syncOnResume: Boolean
        get() = savedStateHandle[KEY_SYNC_ON_RESUME] ?: false
        set(value) {
            savedStateHandle[KEY_SYNC_ON_RESUME] = value
        }

    /**
     * "Sync now" while last night hasn't arrived. With Garmin as the source this returns the intent that opens
     * Garmin Connect (so the watch syncs) and the Health Connect sync runs when the screen resumes; otherwise it
     * syncs right away and returns null.
     */
    fun syncNowIntent(): Intent? {
        if (!opensGarmin.value) {
            syncNow()
            return null
        }
        syncOnResume = true
        return healthConnect.garminSyncIntent()
    }

    /** The intent from [syncNowIntent] couldn't be started: sync directly instead. */
    fun onExternalLaunchFailed() {
        syncOnResume = false
        syncNow()
    }

    /** Back from Garmin Connect: run the sync "Sync now" left pending. */
    fun onResume() {
        if (syncOnResume) {
            syncOnResume = false
            syncNow()
        }
    }

    /** Syncs Health Connect now. Ignored while a sync is already running. */
    fun syncNow() {
        if (syncRepository.state.value.isSyncing) return
        viewModelScope.launch(ioDispatcher) { syncRepository.sync(SyncTrigger.MANUAL) }
    }

    private companion object {
        const val KEY_SYNC_ON_RESUME = "energy.syncOnResume"
    }
}
