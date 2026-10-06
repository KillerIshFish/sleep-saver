package com.sleepsaver.feature.review.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.sleepsaver.core.ui.components.IconListItem
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.review.R
import kotlinx.coroutines.launch

/** "How we measure sleep" as a bottom sheet. [onChangeSource] adds a link to the data-source setting. */
@Composable
internal fun HowWeMeasureSheet(
    source: NightSource?,
    onDismiss: () -> Unit,
    onChangeSource: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun hideThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        HowWeMeasureContent(
            source = source,
            onGotIt = { hideThen(onDismiss) },
            onChangeSource = onChangeSource?.let { change -> { hideThen { onDismiss(); change() } } },
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        )
    }
}

/** The sheet's body, stateless so it can be screenshot-tested on its own. */
@Composable
internal fun HowWeMeasureContent(
    source: NightSource?,
    onGotIt: () -> Unit,
    modifier: Modifier = Modifier,
    onChangeSource: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl)
            .padding(bottom = Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            text = stringResource(R.string.review_hwm_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .padding(bottom = Spacing.xs)
                .semantics { heading() },
        )
        IconListItem(
            icon = Icons.Rounded.Watch,
            title = stringResource(R.string.review_hwm_watch_title),
            supporting = stringResource(R.string.review_hwm_watch_body),
        )
        IconListItem(
            icon = Icons.Rounded.PhoneAndroid,
            title = stringResource(R.string.review_hwm_hc_title),
            supporting = stringResource(R.string.review_hwm_hc_body),
        )
        IconListItem(
            icon = Icons.Rounded.Calculate,
            title = stringResource(R.string.review_hwm_count_title),
            supporting = stringResource(R.string.review_hwm_count_body),
        )
        if (source != null) {
            HorizontalDivider(color = SleepSaverTheme.colors.gridLine)
            val from = when (source.kind) {
                NightSourceKind.HEALTH_CONNECT ->
                    stringResource(R.string.review_source_app_via_hc, originLabel(source.origin))
                NightSourceKind.DEMO -> originLabel(source.origin)
                NightSourceKind.MANUAL -> stringResource(R.string.review_source_manual)
            }
            IconListItem(
                icon = Icons.Rounded.Public,
                title = stringResource(R.string.review_hwm_source_title),
                supporting = stringResource(R.string.review_hwm_source_body, from, formatGmtOffset(source.offset)),
                tint = SleepSaverTheme.colors.textSecondary,
            )
        }
        if (onChangeSource != null) {
            TextLinkButton(
                text = stringResource(R.string.review_hwm_change_source),
                onClick = onChangeSource,
                modifier = Modifier.alignTextStart(),
            )
        }
        PrimaryButton(
            text = stringResource(R.string.review_hwm_got_it),
            onClick = onGotIt,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.s),
        )
    }
}
