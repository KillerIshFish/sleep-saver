package com.sleepsaver.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.repository.ClockFormat
import com.sleepsaver.core.data.repository.ReviewRepository
import com.sleepsaver.core.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import javax.inject.Inject

sealed interface MainUiState {
    data object Loading : MainUiState

    data class Ready(
        val onboardingDone: Boolean,
        val clockFormat: ClockFormat,
        /** A morning review to open automatically (once per app session). */
        val pendingReview: LocalDate?,
    ) : MainUiState
}

@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    reviewRepository: ReviewRepository,
) : ViewModel() {

    private val shownReviews = MutableStateFlow(emptySet<LocalDate>())

    val uiState: StateFlow<MainUiState> = combine(
        settingsRepository.settings,
        reviewRepository.pendingReview,
        shownReviews,
    ) { settings, pending, shown ->
        MainUiState.Ready(
            onboardingDone = settings.onboardingDone,
            clockFormat = settings.clockFormat,
            pendingReview = pending?.takeIf { it !in shown },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState.Loading)

    fun onReviewShown(wakeDate: LocalDate) {
        shownReviews.update { it + wakeDate }
    }
}
