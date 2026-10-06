package com.sleepsaver.feature.review.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import com.sleepsaver.core.ui.components.IconListItem
import com.sleepsaver.core.ui.components.SectionCard
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.format.formatNightLabel
import com.sleepsaver.core.ui.format.formatShortDate
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.review.R
import java.time.LocalDate

enum class NavIcon { CLOSE, BACK }

/**
 * A full-screen page outside the tab bar: a top bar with a close/back button, [content] (scrolling when
 * [scrollState] is set) and optional [bottomBar] actions pinned above the navigation bar. Handles the
 * status-bar and navigation-bar insets itself.
 */
@Composable
internal fun ReviewPage(
    navIcon: NavIcon,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState? = rememberScrollState(),
    topBarActions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onNavigate) {
                    when (navIcon) {
                        NavIcon.CLOSE -> Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.review_close))
                        NavIcon.BACK -> Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.review_back))
                    }
                }
                Spacer(Modifier.weight(1f))
                topBarActions()
            }
            val body = Modifier
                .weight(1f)
                .fillMaxWidth()
            if (scrollState != null) {
                Column(
                    body
                        .verticalScroll(scrollState)
                        .padding(horizontal = Spacing.screenHorizontal)
                        .padding(top = Spacing.xs, bottom = Spacing.xl),
                    content = content,
                )
            } else {
                Column(body.padding(horizontal = Spacing.screenHorizontal), content = content)
            }
            if (bottomBar != null) {
                if (scrollState != null && scrollState.canScrollForward) {
                    HorizontalDivider(color = SleepSaverTheme.colors.gridLine)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                        .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.m),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = bottomBar,
                )
            } else {
                Spacer(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)))
            }
        }
    }
}

/** Page title with the night's dates underneath. */
@Composable
internal fun PageHeader(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    com.sleepsaver.core.ui.components.ScreenHeader(title = title, subtitle = subtitle, modifier = modifier)
}

/** "Last night's sleep", "Saturday night's sleep" or "Night of Mon, Sep 28". */
@Composable
@ReadOnlyComposable
internal fun nightTitle(wakeDate: LocalDate, today: LocalDate): String {
    val label = formatNightLabel(wakeDate, today)
    return if (label.endsWith(" night")) {
        stringResource(R.string.review_title_named, label)
    } else {
        stringResource(R.string.review_title_dated, label)
    }
}

/** "Mon, Oct 5 into Tue, Oct 6". */
@Composable
@ReadOnlyComposable
internal fun nightDates(wakeDate: LocalDate, today: LocalDate): String =
    stringResource(
        R.string.review_night_dates,
        formatShortDate(wakeDate.minusDays(1), today),
        formatShortDate(wakeDate, today),
    )

/** A calm yes/no dialog. */
@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = stringResource(R.string.review_cancel),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SleepSaverTheme.colors.cardRaised,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = SleepSaverTheme.colors.textSecondary,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(body, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = { TextLinkButton(confirmLabel, onClick = onConfirm) },
        dismissButton = { TextLinkButton(dismissLabel, onClick = onDismiss, color = SleepSaverTheme.colors.textSecondary) },
    )
}

/** Where the night came from, the zone it was recorded in, and whether it was edited. */
@Composable
internal fun DataSourceCard(
    source: NightSource,
    onHowWeMeasure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = stringResource(R.string.review_source_title), modifier = modifier.fillMaxWidth()) {
        val zone = formatGmtOffset(source.offset)
        when (source.kind) {
            NightSourceKind.HEALTH_CONNECT -> IconListItem(
                icon = Icons.Rounded.Watch,
                title = originLabel(source.origin),
                supporting = stringResource(
                    R.string.review_joined,
                    stringResource(R.string.review_source_via_hc),
                    stringResource(R.string.review_source_recorded_in, zone),
                ),
            )
            NightSourceKind.DEMO -> IconListItem(
                icon = Icons.Rounded.Science,
                title = originLabel(source.origin),
                supporting = stringResource(
                    R.string.review_joined,
                    stringResource(R.string.review_source_demo_body),
                    stringResource(R.string.review_source_recorded_in, zone),
                ),
            )
            NightSourceKind.MANUAL -> IconListItem(
                icon = Icons.Rounded.EditNote,
                title = stringResource(R.string.review_source_manual),
                supporting = stringResource(R.string.review_source_manual_body),
            )
        }
        if (source.isEdited && source.kind != NightSourceKind.MANUAL) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = null,
                    tint = SleepSaverTheme.colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.review_source_edited),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepSaverTheme.colors.textSecondary,
                    modifier = Modifier.padding(start = Spacing.s),
                )
            }
        }
        TextLinkButton(
            text = stringResource(R.string.review_how_we_measure),
            onClick = onHowWeMeasure,
            modifier = Modifier.alignTextStart(),
        )
    }
}

/** Lines a text button's label up with the content's start edge (its touch target reaches 12 dp beyond). */
internal fun Modifier.alignTextStart(): Modifier = offset(x = (-12).dp)

/** A short note in a soft tide panel: validation hints and "couldn't save" messages. Announced when it appears. */
@Composable
internal fun InlineNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
            .padding(horizontal = Spacing.l, vertical = Spacing.m)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = Spacing.m),
        )
    }
}

/**
 * Calls [onFinished] once [finished] is true and the screen is resumed. A close that lands while the app is in the
 * background (a save finishing late) waits for the user to come back instead of being dropped.
 */
@Composable
internal fun CloseWhenFinished(finished: Boolean, onFinished: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latest by rememberUpdatedState(onFinished)
    LaunchedEffect(finished, lifecycle) {
        if (finished) lifecycle.withResumed { latest() }
    }
}
