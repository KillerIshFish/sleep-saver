package com.sleepsaver.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing

/** The default content padding of [SleepSaverCard]. */
val CardPadding = PaddingValues(20.dp)

/**
 * The basic surface: a 24 dp rounded card one step lighter than the background.
 * Pass [onClick] to make the whole card tappable.
 */
@Composable
fun SleepSaverCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = CardPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(SleepSaverTheme.colors.card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * A [SleepSaverCard] with a [SectionHeader] on top, e.g. "Next up" with a "See all" action.
 * [content] children are stacked 12 dp apart.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // A text action is a 48 dp touch target: trim the padding around it so the title keeps its usual place.
    val hasAction = actionLabel != null && onAction != null
    val actionSlack = if (hasAction) 12.dp else 0.dp
    SleepSaverCard(
        modifier = modifier,
        onClick = onClick,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp, top = 20.dp - actionSlack),
    ) {
        SectionHeader(title = title, actionLabel = actionLabel, onAction = onAction)
        Column(
            modifier = Modifier.padding(top = Spacing.m - actionSlack),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
            content = content,
        )
    }
}

/**
 * A section title with an optional trailing text action. Sits inside a card or directly on the background
 * above a row of cards; the action's text ends at the row's end edge (its touch target reaches 12 dp beyond).
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (actionLabel != null && onAction != null) {
            // Shifted by the button's inner padding so the label's text ends at the content edge.
            TextLinkButton(
                actionLabel,
                onClick = onAction,
                modifier = Modifier
                    .padding(start = Spacing.s)
                    .offset(x = 12.dp),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun SectionCardPreview() = SleepSaverTheme {
    SectionCard("Next up", actionLabel = "See all", onAction = {}, modifier = Modifier.padding(16.dp)) {
        Text("Caffeine cutoff at 2:15 PM", style = MaterialTheme.typography.bodyLarge)
    }
}
