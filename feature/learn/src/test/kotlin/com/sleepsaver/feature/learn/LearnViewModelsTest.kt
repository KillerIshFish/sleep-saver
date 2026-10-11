package com.sleepsaver.feature.learn

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.feature.learn.data.ArticleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearnViewModelsTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.repository() =
        ArticleRepository(ApplicationProvider.getApplicationContext(), UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `learn lists every article in order`() = runTest {
        val viewModel = LearnViewModel(repository())

        val state = viewModel.uiState.first { it is LearnUiState.Ready } as LearnUiState.Ready

        assertThat(state.articles).hasSize(13)
        assertThat(state.articles.map { it.order }).isInStrictOrder()
    }

    @Test
    fun `article is loaded from the route slug`() = runTest {
        val viewModel = ArticleViewModel(SavedStateHandle(mapOf("slug" to "naps")), repository())

        val state = viewModel.uiState.first { it != ArticleUiState.Loading }

        assertThat(state).isInstanceOf(ArticleUiState.Ready::class.java)
        assertThat((state as ArticleUiState.Ready).article.meta.title).isEqualTo("Napping well")
    }

    @Test
    fun `unknown slug is not found`() = runTest {
        val viewModel = ArticleViewModel(SavedStateHandle(mapOf("slug" to "missing")), repository())

        assertThat(viewModel.uiState.first { it != ArticleUiState.Loading }).isEqualTo(ArticleUiState.NotFound)
    }
}
