package com.sleepsaver.feature.learn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.feature.learn.data.ArticleMeta
import com.sleepsaver.feature.learn.data.ArticleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface LearnUiState {
    data object Loading : LearnUiState
    data class Ready(val articles: List<ArticleMeta>) : LearnUiState
}

@HiltViewModel
class LearnViewModel @Inject constructor(repository: ArticleRepository) : ViewModel() {
    val uiState: StateFlow<LearnUiState> =
        flow { emit(LearnUiState.Ready(repository.articles().map { it.meta })) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LearnUiState.Loading)
}
