package com.sleepsaver.feature.sleep

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MobileOff
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.components.EmptyState
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.SecondaryButton
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing

/** No nights yet: one calm fix-it screen per cause, centred in the space under the header. */
@Composable
internal fun ColumnScope.EmptyBody(state: SleepUiState.Empty, actions: SleepActions) {
    Spacer(Modifier.height(Spacing.xl))
    Spacer(Modifier.weight(1f))
    val modifier = Modifier.padding(horizontal = Spacing.screenHorizontal)
    val tryDemo = stringResource(R.string.sleep_try_demo)
    when (state.reason) {
        EmptyReason.INSTALL_HEALTH_CONNECT -> EmptyState(
            icon = Icons.Rounded.HealthAndSafety,
            title = stringResource(R.string.sleep_empty_install_title),
            body = stringResource(R.string.sleep_empty_install_body),
            primaryActionLabel = stringResource(R.string.sleep_empty_install_action),
            onPrimaryAction = actions.onInstallHealthConnect,
            secondaryActionLabel = tryDemo,
            onSecondaryAction = actions.onTryDemo,
            modifier = modifier,
        )
        EmptyReason.UPDATE_HEALTH_CONNECT -> EmptyState(
            icon = Icons.Rounded.SystemUpdate,
            title = stringResource(R.string.sleep_empty_update_title),
            body = stringResource(R.string.sleep_empty_update_body),
            primaryActionLabel = stringResource(R.string.sleep_empty_update_action),
            onPrimaryAction = actions.onInstallHealthConnect,
            secondaryActionLabel = tryDemo,
            onSecondaryAction = actions.onTryDemo,
            modifier = modifier,
        )
        EmptyReason.NOT_SUPPORTED -> EmptyState(
            icon = Icons.Rounded.MobileOff,
            title = stringResource(R.string.sleep_empty_unsupported_title),
            body = stringResource(R.string.sleep_empty_unsupported_body),
            primaryActionLabel = tryDemo,
            onPrimaryAction = actions.onTryDemo,
            modifier = modifier,
        )
        EmptyReason.PERMISSION_DENIED -> EmptyState(
            icon = Icons.Rounded.Lock,
            title = stringResource(R.string.sleep_empty_permission_title),
            body = stringResource(R.string.sleep_empty_permission_body),
            primaryActionLabel = stringResource(R.string.sleep_empty_permission_action),
            onPrimaryAction = actions.onOpenHealthConnectSetup,
            secondaryActionLabel = tryDemo,
            onSecondaryAction = actions.onTryDemo,
            modifier = modifier,
        )
        EmptyReason.NO_DATA -> NoDataBody(state, actions, modifier)
    }
    Spacer(Modifier.weight(1f))
    Spacer(Modifier.height(Spacing.xl))
}

@Composable
private fun NoDataBody(state: SleepUiState.Empty, actions: SleepActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(
            icon = Icons.Rounded.Bedtime,
            title = stringResource(R.string.sleep_empty_no_data_title),
            body = stringResource(R.string.sleep_empty_no_data_body),
        )
        if (state.syncOpensGarmin) {
            Spacer(Modifier.height(Spacing.xl))
            GarminSteps(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(Spacing.xl))
        PrimaryButton(
            text = stringResource(if (state.isSyncing) R.string.sleep_syncing else R.string.sleep_sync_now),
            onClick = actions.onSyncNowViaGarmin,
            enabled = !state.isSyncing,
            icon = Icons.Rounded.Sync,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        SecondaryButton(
            text = stringResource(R.string.sleep_empty_add_night),
            onClick = { actions.onAddMissingNight(state.today) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.canTryDemo) {
            Spacer(Modifier.height(Spacing.xs))
            TextLinkButton(text = stringResource(R.string.sleep_try_demo), onClick = actions.onTryDemo)
        }
    }
}

/** Watch → Garmin Connect → Health Connect, as three numbered steps. */
@Composable
private fun GarminSteps(modifier: Modifier = Modifier) {
    val steps = listOf(R.string.sleep_empty_step_1, R.string.sleep_empty_step_2, R.string.sleep_empty_step_3)
    SleepSaverCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.sleep_empty_steps_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.m))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            steps.forEachIndexed { index, step ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = (index + 1).toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = stringResource(step),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SleepSaverTheme.colors.textSecondary,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = Spacing.m, top = 2.dp),
                    )
                }
            }
        }
    }
}
