package com.sleepsaver.feature.learn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
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
import com.sleepsaver.feature.learn.data.ArticleMeta

@Composable
internal fun LearnScreen(
    onOpenArticle: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LearnViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LearnContent(state, onOpenArticle, modifier)
}

@Composable
internal fun LearnContent(
    state: LearnUiState,
    onOpenArticle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier.widthIn(max = ReadingWidth).fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header") { LearnHeader(Modifier.padding(bottom = 8.dp)) }
                if (state is LearnUiState.Ready) {
                    items(state.articles, key = { it.slug }) { article ->
                        ArticleCard(article, onClick = { onOpenArticle(article.slug) })
                    }
                }
            }
        }
    }
}

/** Stands in for core:ui's shared screen header until that lands. */
@Composable
private fun LearnHeader(modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = stringResource(R.string.learn_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.learn_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = SleepSaverTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun ArticleCard(article: ArticleMeta, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = SleepSaverTheme.colors.card,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(article.title, style = MaterialTheme.typography.titleMedium)
                if (article.summary.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = article.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SleepSaverTheme.colors.textSecondary,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = pluralStringResource(R.plurals.learn_minutes_read, article.minutes, article.minutes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = SleepSaverTheme.colors.textTertiary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Preview
@Composable
private fun LearnContentPreview() {
    SleepSaverTheme {
        LearnContent(
            state = LearnUiState.Ready(
                listOf(
                    ArticleMeta("sleep-debt", "Sleep debt", "How short nights add up, and how steady nights pay them back.", 2, 1),
                    ArticleMeta("naps", "Napping well", "A short, well-timed nap can lift your energy.", 2, 2),
                ),
            ),
            onOpenArticle = {},
        )
    }
}
