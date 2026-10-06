package com.sleepsaver.feature.onboarding.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.theme.Spacing

/**
 * One onboarding step: scrollable [content] above actions pinned to the bottom ([bottom], centred).
 * [centered] puts short content in the middle of the space when it fits (it still scrolls when it doesn't).
 */
@Composable
internal fun StepLayout(
    modifier: Modifier = Modifier,
    centered: Boolean = false,
    scrollState: ScrollState = rememberScrollState(),
    bottom: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val viewport = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .heightIn(min = viewport)
                    .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.s),
                verticalArrangement = if (centered) Arrangement.Center else Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
            // More below: let the content fade out above the actions instead of being cut off by them.
            if (scrollState.canScrollForward) {
                val background = MaterialTheme.colorScheme.background
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(FadeHeight)
                        .background(Brush.verticalGradient(listOf(background.copy(alpha = 0f), background))),
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenHorizontal)
                .padding(top = Spacing.m, bottom = Spacing.l),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = bottom,
        )
    }
}

private val FadeHeight = 32.dp
