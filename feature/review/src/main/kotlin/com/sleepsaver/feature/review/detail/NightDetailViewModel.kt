package com.sleepsaver.feature.review.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.SleepRepository
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.feature.review.common.NightSource
import com.sleepsaver.feature.review.common.parseWakeDate
import com.sleepsaver.feature.review.common.today
import com.sleepsaver.feature.review.navigation.NightDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class NightDetailUiState(
    val wakeDate: LocalDate,
    val today: LocalDate,
    val loading: Boolean = true,
    /** null (or not present) = nothing recorded for this night. */
    val night: Night? = null,
    val source: NightSource? = null,
) {
    val isMissing: Boolean get() = !loading && night?.isPresent != true
}

@HiltViewModel
class NightDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sleepRepository: SleepRepository,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val wakeDate: LocalDate = timeProvider.parseWakeDate(savedStateHandle.toRoute<NightDetailRoute>().wakeDate)
    private val initial = NightDetailUiState(wakeDate = wakeDate, today = timeProvider.today())

    val uiState: StateFlow<NightDetailUiState> = sleepRepository.night(wakeDate)
        .map { night ->
            initial.copy(
                today = timeProvider.today(),
                loading = false,
                night = night,
                source = night?.let { NightSource.of(it, timeProvider.zone(), timeProvider.now()) },
            )
        }
        .catch { emit(initial.copy(loading = false)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    /** Deletes a nap the user added (manual nap blocks carry "manual-nap:<id>"). */
    fun onDeleteNap(id: Long) {
        viewModelScope.launch { runCatching { sleepRepository.deleteNap(id) } }
    }

    /** Back to what the data source recorded (removes a manual night). */
    fun onRevert() {
        viewModelScope.launch { runCatching { sleepRepository.revert(wakeDate) } }
    }
}
