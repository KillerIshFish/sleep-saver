package com.sleepsaver.feature.learn

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.sleepsaver.feature.learn.data.Article
import com.sleepsaver.feature.learn.data.ArticleRepository
import com.sleepsaver.feature.learn.navigation.ArticleRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface ArticleUiState {
    data object Loading : ArticleUiState
    data object NotFound : ArticleUiState
    data class Ready(val article: Article) : ArticleUiState
}

@HiltViewModel
class ArticleViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: ArticleRepository,
) : ViewModel() {
    private val slug = savedStateHandle.toRoute<ArticleRoute>().slug

    val uiState: StateFlow<ArticleUiState> =
        flow { emit(repository.article(slug)?.let(ArticleUiState::Ready) ?: ArticleUiState.NotFound) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArticleUiState.Loading)
}
