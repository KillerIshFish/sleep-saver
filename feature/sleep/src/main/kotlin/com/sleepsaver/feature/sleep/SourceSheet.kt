package com.sleepsaver.feature.sleep

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.SecondaryButton
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import kotlinx.coroutines.launch

/** Opened from the data-source chip: where the nights come from, when they last synced, and how to change it. */
@Composable
internal fun SourceSheet(state: SleepUiState.Ready, actions: SleepActions, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        SourceSheetContent(
            state = state,
            onSyncNow = actions.onSyncNow,
            onChangeInSettings = {
                scope.launch { sheetState.hide() }.invokeOnCompletion {
                    onDismiss()
                    actions.onOpenSettings()
                }
            },
        )
    }
}

/** The sheet's body, separate so it can be screenshot-tested (a sheet is its own window). */
@Composable
internal fun SourceSheetContent(
    state: SleepUiState.Ready,
    onSyncNow: () -> Unit,
    onChangeInSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDemo = state.source == SourceLabel.Demo
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.xl),
    ) {
        Text(
            text = stringResource(R.string.sleep_source_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.l))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(state.source.icon)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
            ) {
                Text(
                    text = state.source.longName(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(if (isDemo) R.string.sleep_source_demo_body else R.string.sleep_source_via),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepSaverTheme.colors.textSecondary,
                )
            }
        }
        if (!isDemo) {
            Spacer(Modifier.height(Spacing.l))
            if (state.isSyncing) {
                SyncingIndicator()
            } else {
                SheetLine(Icons.Rounded.Schedule, lastSyncedText(state.lastSyncAt, state.now, state.zone))
            }
            state.lastSyncError?.let { error ->
                Spacer(Modifier.height(Spacing.s))
                SheetLine(Icons.Rounded.Info, stringResource(error.messageRes))
            }
        }
        Spacer(Modifier.height(Spacing.xl))
        if (!isDemo) {
            PrimaryButton(
                // While syncing, the line above already says so next to the orb.
                text = stringResource(R.string.sleep_sync_now),
                onClick = onSyncNow,
                enabled = !state.isSyncing,
                icon = Icons.Rounded.Sync,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
        }
        SecondaryButton(
            text = stringResource(R.string.sleep_source_change),
            onClick = onChangeInSettings,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SheetLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = SleepSaverTheme.colors.textTertiary, modifier = Modifier.size(18.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = SleepSaverTheme.colors.textSecondary,
            modifier = Modifier.padding(start = Spacing.s),
        )
    }
}

@Composable
private fun SourceLabel.longName(): String = when (this) {
    SourceLabel.Garmin -> stringResource(R.string.sleep_source_garmin_long)
    SourceLabel.AllSources -> stringResource(R.string.sleep_source_all_long)
    SourceLabel.Demo -> stringResource(R.string.sleep_source_demo_long)
    is SourceLabel.App -> name
}

private val SyncError.messageRes: Int
    get() = when (this) {
        SyncError.HEALTH_CONNECT_NOT_INSTALLED -> R.string.sleep_sync_error_not_installed
        SyncError.HEALTH_CONNECT_UPDATE_REQUIRED -> R.string.sleep_sync_error_update
        SyncError.PERMISSION_DENIED -> R.string.sleep_sync_error_permission
        SyncError.RATE_LIMITED -> R.string.sleep_sync_error_rate_limited
        SyncError.BACKGROUND_NOT_ALLOWED, SyncError.UNKNOWN -> R.string.sleep_sync_error_generic
    }
