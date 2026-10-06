package com.sleepsaver.feature.onboarding.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MobileOff
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.data.healthconnect.HealthPermissionState
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.ui.components.IconListItem
import com.sleepsaver.core.ui.components.InfoChip
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.SecondaryButton
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.onboarding.R
import com.sleepsaver.feature.onboarding.ui.NumberedStep
import com.sleepsaver.feature.onboarding.ui.RowStatus
import com.sleepsaver.feature.onboarding.ui.StatusIcon
import com.sleepsaver.feature.onboarding.ui.StatusRow
import com.sleepsaver.feature.onboarding.ui.rememberAppLabel

/** Every action the Connect content can trigger. The screens wire them to the ViewModel and to intents. */
@Stable
internal class ConnectActions(
    /** Launch Health Connect's permission screen. */
    val onConnect: () -> Unit = {},
    /** Play Store page to install or update Health Connect. */
    val onInstall: () -> Unit = {},
    val onOpenHealthConnect: () -> Unit = {},
    val onOpenGarmin: () -> Unit = {},
    val onCheckAgain: () -> Unit = {},
    val onUseSource: (SourceSelection) -> Unit = {},
    val onExploreDemo: () -> Unit = {},
)

/** Illustration, title and body for every stage that isn't "connected". */
internal data class StageCopy(val icon: ImageVector, val title: Int, val body: Int)

internal fun stageCopy(stage: ConnectStage): StageCopy? = when (stage) {
    ConnectStage.NOT_INSTALLED -> StageCopy(Icons.Rounded.HealthAndSafety, R.string.onboarding_missing_title, R.string.onboarding_missing_body)
    ConnectStage.UPDATE_REQUIRED -> StageCopy(Icons.Rounded.SystemUpdate, R.string.onboarding_update_title, R.string.onboarding_update_body)
    ConnectStage.NOT_SUPPORTED -> StageCopy(Icons.Rounded.MobileOff, R.string.onboarding_unsupported_title, R.string.onboarding_unsupported_body)
    ConnectStage.DENIED -> StageCopy(Icons.Rounded.Lock, R.string.onboarding_denied_title, R.string.onboarding_denied_body)
    ConnectStage.NEEDS_PERMISSION -> StageCopy(Icons.Rounded.HealthAndSafety, R.string.onboarding_connect_title, R.string.onboarding_connect_body)
    ConnectStage.CHECKING, ConnectStage.CONNECTED -> null
}

/** The four kinds of data we ask for, before asking. */
@Composable
internal fun AskListCard(modifier: Modifier = Modifier) {
    SleepSaverCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        Text(
            text = stringResource(R.string.onboarding_ask_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(bottom = Spacing.s)
                .semantics { heading() },
        )
        IconListItem(Icons.Rounded.Bedtime, stringResource(R.string.onboarding_ask_sleep), supporting = stringResource(R.string.onboarding_ask_sleep_body))
        IconListItem(Icons.Rounded.MonitorHeart, stringResource(R.string.onboarding_ask_heart), supporting = stringResource(R.string.onboarding_ask_heart_body))
        IconListItem(Icons.Rounded.History, stringResource(R.string.onboarding_ask_history), supporting = stringResource(R.string.onboarding_ask_history_body))
        IconListItem(Icons.Rounded.Sync, stringResource(R.string.onboarding_ask_background), supporting = stringResource(R.string.onboarding_ask_background_body))
    }
}

/**
 * What was granted: sleep (required), heart data (optional), history and background sync (granted, not granted,
 * or not available on this phone), each saying what's lost without it. [onAllow] re-opens the permission screen.
 */
@Composable
internal fun PermissionSummaryCard(
    permissions: HealthPermissionState,
    onAllow: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SleepSaverCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            PermissionRows(permissions, onAllow)
        }
        // Lined up with the row titles (icon 24 dp + 14 dp gap, minus the button's own 12 dp padding).
        TextLinkButton(
            text = stringResource(R.string.onboarding_manage_in_health_connect),
            onClick = onManage,
            modifier = Modifier.padding(top = Spacing.s, start = 26.dp),
        )
    }
}

/** The four permission rows, shared with the fix-it screen's status card. */
@Composable
internal fun PermissionRows(permissions: HealthPermissionState, onAllow: () -> Unit) {
    val allow = stringResource(R.string.onboarding_allow)
    StatusRow(
        title = stringResource(R.string.onboarding_perm_sleep),
        detail = stringResource(if (permissions.sleepGranted) R.string.onboarding_perm_sleep_granted else R.string.onboarding_perm_sleep_missing),
        status = if (permissions.sleepGranted) RowStatus.OK else RowStatus.NEEDS_ACTION,
        actionLabel = allow.takeUnless { permissions.sleepGranted },
        onAction = onAllow,
    )
    GrantRow(
        grant = permissions.heartData,
        title = R.string.onboarding_perm_heart,
        granted = R.string.onboarding_perm_heart_granted,
        missing = R.string.onboarding_perm_heart_missing,
        unavailable = R.string.onboarding_perm_heart_missing,
        onAllow = onAllow,
    )
    GrantRow(
        grant = permissions.history,
        title = R.string.onboarding_perm_history,
        granted = R.string.onboarding_perm_history_granted,
        missing = R.string.onboarding_perm_history_missing,
        unavailable = R.string.onboarding_perm_history_unavailable,
        onAllow = onAllow,
    )
    GrantRow(
        grant = permissions.background,
        title = R.string.onboarding_perm_background,
        granted = R.string.onboarding_perm_background_granted,
        missing = R.string.onboarding_perm_background_missing,
        unavailable = R.string.onboarding_perm_background_unavailable,
        onAllow = onAllow,
    )
}

@Composable
private fun GrantRow(grant: Grant, title: Int, granted: Int, missing: Int, unavailable: Int, onAllow: () -> Unit) {
    StatusRow(
        title = stringResource(title),
        detail = stringResource(
            when (grant) {
                Grant.GRANTED -> granted
                Grant.NOT_GRANTED -> missing
                Grant.UNAVAILABLE -> unavailable
            },
        ),
        status = when (grant) {
            Grant.GRANTED -> RowStatus.OK
            Grant.NOT_GRANTED -> RowStatus.OFF
            Grant.UNAVAILABLE -> RowStatus.UNAVAILABLE
        },
        actionLabel = stringResource(R.string.onboarding_allow).takeIf { grant == Grant.NOT_GRANTED },
        onAction = onAllow,
    )
}

/**
 * Is the chosen source (Garmin Connect by default) actually sharing sleep? If not: how to turn it on inside
 * Garmin Connect, a way to open it, "Check again", and the other apps that do share sleep.
 */
@Composable
internal fun SourceCard(
    source: SourceCheck,
    garminInstalled: Boolean,
    actions: ConnectActions,
    modifier: Modifier = Modifier,
) {
    when (source) {
        SourceCheck.NotChecked -> Unit
        SourceCheck.Checking -> SleepSaverCard(modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIcon(RowStatus.CHECKING, Modifier.padding(end = 14.dp))
                Text(
                    text = stringResource(R.string.onboarding_source_checking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepSaverTheme.colors.textSecondary,
                )
            }
        }
        is SourceCheck.Sharing -> SleepSaverCard(modifier.fillMaxWidth()) {
            val (title, body) = when (val selection = source.selection) {
                SourceSelection.All ->
                    stringResource(R.string.onboarding_source_all_ok) to stringResource(R.string.onboarding_source_all_ok_body)
                is SourceSelection.Single ->
                    if (selection.packageName == SleepConfig.GARMIN_CONNECT_PACKAGE) {
                        stringResource(R.string.onboarding_source_garmin_ok) to stringResource(R.string.onboarding_source_garmin_ok_body)
                    } else {
                        stringResource(R.string.onboarding_source_other_ok, rememberAppLabel(selection.packageName)) to
                            stringResource(R.string.onboarding_source_other_ok_body)
                    }
            }
            StatusRow(title = title, detail = body, status = RowStatus.OK)
        }
        is SourceCheck.Missing -> MissingSourceCard(source, garminInstalled, actions, modifier)
    }
}

@Composable
private fun MissingSourceCard(
    source: SourceCheck.Missing,
    garminInstalled: Boolean,
    actions: ConnectActions,
    modifier: Modifier = Modifier,
) {
    val selection = source.selection
    val otherApp = (selection as? SourceSelection.Single)?.packageName?.takeIf { it != SleepConfig.GARMIN_CONNECT_PACKAGE }
    val otherLabel = otherApp?.let { rememberAppLabel(it) }
    SleepSaverCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Watch, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            Text(
                text = when {
                    otherLabel != null -> stringResource(R.string.onboarding_source_other_missing, otherLabel)
                    selection == SourceSelection.All -> stringResource(R.string.onboarding_source_all_missing)
                    else -> stringResource(R.string.onboarding_source_garmin_missing)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f)
                    .semantics { heading() },
            )
        }
        Spacer(Modifier.height(Spacing.m))
        Text(
            text = if (otherLabel != null) {
                stringResource(R.string.onboarding_source_other_missing_body, otherLabel)
            } else {
                stringResource(R.string.onboarding_source_garmin_missing_body)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = SleepSaverTheme.colors.textSecondary,
        )
        if (otherLabel == null) {
            Column(Modifier.padding(top = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                NumberedStep(1, stringResource(R.string.onboarding_garmin_step_1))
                NumberedStep(2, stringResource(R.string.onboarding_garmin_step_2))
                NumberedStep(3, stringResource(R.string.onboarding_garmin_step_3))
            }
        }
        // A lone "Check again" link sits closer to the text than the Garmin button does.
        Spacer(Modifier.height(if (otherLabel == null) Spacing.xl else Spacing.s))
        if (otherLabel == null) {
            SecondaryButton(
                text = stringResource(if (garminInstalled) R.string.onboarding_open_garmin else R.string.onboarding_get_garmin),
                onClick = actions.onOpenGarmin,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.xs))
        }
        TextLinkButton(
            text = stringResource(R.string.onboarding_check_again),
            onClick = actions.onCheckAgain,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        if (source.alternatives.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = Spacing.m), color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = stringResource(R.string.onboarding_alternatives_title),
                style = MaterialTheme.typography.labelLarge,
                color = SleepSaverTheme.colors.textSecondary,
            )
            FlowRow(
                modifier = Modifier.padding(top = Spacing.m),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                source.alternatives.take(MAX_ALTERNATIVES).forEach { pkg ->
                    InfoChip(
                        text = stringResource(R.string.onboarding_use_app_instead, rememberAppLabel(pkg)),
                        icon = Icons.Rounded.SwapHoriz,
                        iconTint = MaterialTheme.colorScheme.primary,
                        onClick = { actions.onUseSource(SourceSelection.Single(pkg)) },
                    )
                }
                if (selection != SourceSelection.All) {
                    InfoChip(
                        text = stringResource(R.string.onboarding_use_all_sources),
                        icon = Icons.Rounded.AllInclusive,
                        iconTint = MaterialTheme.colorScheme.primary,
                        onClick = { actions.onUseSource(SourceSelection.All) },
                    )
                }
            }
        }
    }
}

/**
 * The fix-it screen's version of a stage that isn't "connected": the same explanation as the onboarding step,
 * with its actions inside the card.
 */
@Composable
internal fun StageCard(stage: ConnectStage, actions: ConnectActions, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val copy = stageCopy(stage) ?: return
    SleepSaverCard(modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(copy.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        }
        Text(
            text = stringResource(copy.title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(top = Spacing.l)
                .semantics { heading() },
        )
        Text(
            text = stringResource(copy.body),
            style = MaterialTheme.typography.bodyMedium,
            color = SleepSaverTheme.colors.textSecondary,
            modifier = Modifier.padding(top = Spacing.s),
        )
        Spacer(Modifier.height(Spacing.xl))
        StageActions(stage, actions, enabled, demoAsLink = false)
    }
}

/**
 * The buttons for a stage: a primary action, sometimes a secondary one, and the demo way out
 * (as a link when [demoAsLink], otherwise left to the caller).
 */
@Composable
internal fun StageActions(stage: ConnectStage, actions: ConnectActions, enabled: Boolean, demoAsLink: Boolean, onContinue: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val primary: Pair<Int, () -> Unit>? = when (stage) {
            ConnectStage.CHECKING -> null
            ConnectStage.NOT_INSTALLED -> R.string.onboarding_missing_action to actions.onInstall
            ConnectStage.UPDATE_REQUIRED -> R.string.onboarding_update_action to actions.onInstall
            ConnectStage.NOT_SUPPORTED -> R.string.onboarding_explore_demo to actions.onExploreDemo
            ConnectStage.NEEDS_PERMISSION -> R.string.onboarding_connect_action to actions.onConnect
            ConnectStage.DENIED -> R.string.onboarding_try_again to actions.onConnect
            ConnectStage.CONNECTED -> R.string.onboarding_continue to onContinue
        }
        if (primary != null) {
            PrimaryButton(stringResource(primary.first), onClick = primary.second, enabled = enabled, modifier = Modifier.fillMaxWidth())
        }
        if (stage == ConnectStage.DENIED) {
            Spacer(Modifier.height(10.dp))
            SecondaryButton(
                stringResource(R.string.onboarding_open_health_connect),
                onClick = actions.onOpenHealthConnect,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (demoAsLink && stage != ConnectStage.NOT_SUPPORTED) {
            Spacer(Modifier.height(Spacing.xs))
            TextLinkButton(stringResource(R.string.onboarding_explore_demo), onClick = actions.onExploreDemo, enabled = enabled)
        }
    }
}

private const val MAX_ALTERNATIVES = 3
