package com.sleepsaver.feature.learn.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.feature.learn.markdown.MarkdownBlock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArticleRepositoryTest {

    @Test
    fun `articles are sorted by order`() = runTest {
        val repository = ArticleRepository(ApplicationProvider.getApplicationContext(), StandardTestDispatcher(testScheduler))

        val articles = repository.articles()

        assertThat(articles).hasSize(10)
        assertThat(articles.map { it.meta.order }).isInStrictOrder()
        assertThat(articles.first().meta.slug).isEqualTo("sleep-debt")
    }

    @Test
    fun `article by slug has front matter and body`() = runTest {
        val repository = ArticleRepository(ApplicationProvider.getApplicationContext(), StandardTestDispatcher(testScheduler))

        val article = repository.article("caffeine")!!

        assertThat(article.meta.title).isEqualTo("Caffeine timing")
        assertThat(article.meta.minutes).isEqualTo(2)
        assertThat(article.blocks.filterIsInstance<MarkdownBlock.Heading>()).isNotEmpty()
    }

    @Test
    fun `unknown slug is null`() = runTest {
        val repository = ArticleRepository(ApplicationProvider.getApplicationContext(), StandardTestDispatcher(testScheduler))

        assertThat(repository.article("nope")).isNull()
    }
}
