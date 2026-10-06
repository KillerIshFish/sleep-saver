package com.sleepsaver.feature.energy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SleepRepository
import com.sleepsaver.core.domain.model.HabitType
import dagger.hilt.android.lifecycle.HiltViewModel
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
 * night that just synced is drawn right away) and the minute clock for the now-line.
 */
@HiltViewModel
class EnergyViewModel @Inject constructor(
    insightsRepository: InsightsRepository,
    private val sleepRepository: SleepRepository,
    settingsRepository: SettingsRepository,
    private val habitsRepository: HabitsRepository,
    timeProvider: TimeProvider,
) : ViewModel() {

    val uiState: StateFlow<EnergyUiState> = combine(
        insightsRepository.state,
        sleepRepository.nights,
        settingsRepository.settings.map { it.sleep.sleepNeed }.distinctUntilChanged(),
        timeProvider.minuteTicks,
    ) { insights, nights, need, now ->
        when (insights) {
            InsightsState.Loading -> EnergyUiState.Loading
            InsightsState.NoNights -> EnergyUiState.NoNights
            is InsightsState.Ready -> energyReadyState(insights.insights, nights, need, now)
        }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EnergyUiState.Loading)

    /** Long-press on last night's block: subtracts [start]..[end] from that night's sleep. */
    fun addAwake(wakeDate: LocalDate, start: Instant, end: Instant) {
        if (!end.isAfter(start)) return
        viewModelScope.launch { sleepRepository.addAwake(wakeDate, start, end) }
    }

    fun setReminder(type: HabitType, enabled: Boolean) {
        viewModelScope.launch { habitsRepository.setReminder(type, enabled) }
    }
}
