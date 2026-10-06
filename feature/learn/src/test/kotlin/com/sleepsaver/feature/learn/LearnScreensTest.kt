package com.sleepsaver.feature.learn

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.feature.learn.data.Article
import com.sleepsaver.feature.learn.data.ArticleMeta
import com.sleepsaver.feature.learn.data.ArticleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Plain Robolectric Compose tests. Swap for Roborazzi screenshots once the shared screenshot setup is merged.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class LearnScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val articles = listOf(
        ArticleMeta("sleep-debt", "Sleep debt", "How short nights add up.", 2, 1),
        ArticleMeta("naps", "Napping well", "Short and early.", 3, 2),
    )

    private fun realArticle(slug: String): Article = runBlocking {
        ArticleRepository(ApplicationProvider.getApplicationContext(), Dispatchers.Unconfined).article(slug)!!
    }

    @Test
    fun `learn shows the header and one card per article`() {
        compose.setContent { SleepSaverTheme { LearnContent(LearnUiState.Ready(articles), onOpenArticle = {}) } }

        compose.onNodeWithText("Learn").assertIsDisplayed()
        compose.onNodeWithText("Short reads on how sleep and energy work").assertIsDisplayed()
        compose.onNodeWithText("Sleep debt").assertIsDisplayed()
        compose.onNodeWithText("How short nights add up.").assertIsDisplayed()
        compose.onNodeWithText("2 min read").assertIsDisplayed()
        compose.onNodeWithText("3 min read").assertIsDisplayed()
    }

    @Test
    fun `tapping a card opens that article`() {
        var opened: String? = null
        compose.setContent { SleepSaverTheme { LearnContent(LearnUiState.Ready(articles), onOpenArticle = { opened = it }) } }

        compose.onNodeWithText("Napping well").performClick()

        assertThat(opened).isEqualTo("naps")
    }

    @Test
    fun `article renders its body and ends with the disclaimer`() {
        compose.setContent { SleepSaverTheme { ArticleContent(ArticleUiState.Ready(realArticle("caffeine")), onBack = {}) } }

        compose.onNodeWithText("Caffeine timing").assertIsDisplayed()
        compose.onNodeWithText("2 min read").assertIsDisplayed()
        compose.onNodeWithText("Why it lingers").assertIsDisplayed()
        compose.onNodeWithText("Decaf still has a small amount.").assertExists()

        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText("In Sleep Saver"))
        compose.onNodeWithText("In Sleep Saver").assertIsDisplayed()
        list.performScrollToNode(hasText("Sleep Saver is a wellness tool, not medical advice."))
        compose.onNodeWithText("Sleep Saver is a wellness tool, not medical advice.").assertIsDisplayed()
    }

    @Test
    fun `back arrow calls back`() {
        var backs = 0
        compose.setContent { SleepSaverTheme { ArticleContent(ArticleUiState.Ready(realArticle("naps")), onBack = { backs++ }) } }

        compose.onNodeWithContentDescription("Back").performClick()

        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `missing article says so`() {
        compose.setContent { SleepSaverTheme { ArticleContent(ArticleUiState.NotFound, onBack = {}) } }

        compose.onNodeWithText("This article isn't available.").assertIsDisplayed()
    }
}
