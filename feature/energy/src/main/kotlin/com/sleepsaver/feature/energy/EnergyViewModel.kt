package com.sleepsaver.feature.energy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.di.IoDispatcher
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SleepRepository
import com.sleepsaver.core.data.repository.SyncRepository
import com.sleepsaver.core.data.repository.SyncTrigger
import com.sleepsaver.core.domain.model.HabitType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
 * empty state's reason and the "Sync now" action while last night is on its way).
 */
@HiltViewModel
class EnergyViewModel @Inject constructor(
    insightsRepository: InsightsRepository,
    private val sleepRepository: SleepRepository,
    settingsRepository: SettingsRepository,
    private val habitsRepository: HabitsRepository,
    private val syncRepository: SyncRepository,
    timeProvider: TimeProvider,
    /** Writes and syncs run here: never database or Health Connect work on the main thread. */
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val sources = combine(
        insightsRepository.state,
        sleepRepository.nights,
        settingsRepository.settings.map { it.sleep.sleepNeed }.distinctUntilChanged(),
    ) { insights, nights, need -> Triple(insights, nights, need) }

    val uiState: StateFlow<EnergyUiState> = combine(
        sources,
        timeProvider.minuteTicks,
        syncRepository.state,
    ) { (insights, nights, need), now, sync ->
        when (insights) {
            InsightsState.Loading -> EnergyUiState.Loading
            InsightsState.NoNights -> EnergyUiState.NoNights(sync.emptyReason())
            is InsightsState.Ready -> energyReadyState(insights.insights, nights, need, now, sync.isSyncing)
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

    /** "Sync now" while last night hasn't arrived. Ignored while a sync is already running. */
    fun syncNow() {
        if (syncRepository.state.value.isSyncing) return
        viewModelScope.launch(ioDispatcher) { syncRepository.sync(SyncTrigger.MANUAL) }
    }
}
