package com.sleepsaver.feature.learn

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.feature.learn.data.Article
import com.sleepsaver.feature.learn.data.toArticle
import com.sleepsaver.feature.learn.markdown.MarkdownBlockView
import com.sleepsaver.feature.learn.markdown.MarkdownParser
import com.sleepsaver.feature.learn.markdown.markdownSpacingBefore
import com.sleepsaver.feature.learn.markdown.markdownStyle

/** Long lines are hard to read: text columns stop growing past this on tablets and in landscape. */
internal val ReadingWidth = 640.dp

@Composable
internal fun ArticleScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArticleViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ArticleContent(state, onBack, modifier)
}

@Composable
internal fun ArticleContent(
    state: ArticleUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.learn_back))
            }
            when (state) {
                ArticleUiState.Loading -> Unit
                ArticleUiState.NotFound -> Text(
                    text = stringResource(R.string.learn_article_missing),
                    style = MaterialTheme.typography.bodyLarge,
                    color = SleepSaverTheme.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
                is ArticleUiState.Ready -> ArticleBody(state.article)
            }
        }
    }
}

@Composable
private fun ArticleBody(article: Article) {
    val style = markdownStyle()
    val readable = Modifier.widthIn(max = ReadingWidth).fillMaxWidth()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "title") {
            Column(readable.padding(bottom = 24.dp)) {
                Text(
                    text = article.meta.title,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = pluralStringResource(R.plurals.learn_minutes_read, article.meta.minutes, article.meta.minutes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        itemsIndexed(article.blocks) { index, block ->
            val gap = markdownSpacingBefore(article.blocks.getOrNull(index - 1), block)
            MarkdownBlockView(block, readable.padding(top = gap), style)
        }
        item(key = "disclaimer") { WellnessNote(readable.padding(top = 32.dp)) }
        item(key = "insets") { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing)) }
    }
}

/** Stands in for core:ui's shared wellness disclaimer until that lands. */
@Composable
private fun WellnessNote(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(SleepSaverTheme.colors.card, MaterialTheme.shapes.small)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = SleepSaverTheme.colors.textTertiary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(R.string.learn_wellness_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = SleepSaverTheme.colors.textSecondary,
        )
    }
}

@Preview
@Composable
private fun ArticleContentPreview() {
    val article = MarkdownParser.parse(
        """
        ---
        title: Napping well
        minutes: 2
        ---
        A nap is one of the simplest ways to top up your energy on a **short** day.

        ## Short and early

        - Set an alarm for about *30 minutes*.
        - Lie down somewhere dim and quiet.

        > Resting quietly still helps.
        """.trimIndent(),
    ).toArticle("naps")
    SleepSaverTheme { ArticleContent(ArticleUiState.Ready(article), onBack = {}) }
}
