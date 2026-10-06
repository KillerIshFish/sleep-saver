package com.sleepsaver.feature.onboarding.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.data.healthconnect.HealthConnectStatus
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.data.repository.SyncStatus
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.ui.components.IconListItem
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.ScreenHeader
import com.sleepsaver.core.ui.components.SectionCard
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.formatShortDate
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.onboarding.R
import com.sleepsaver.feature.onboarding.connect.ConnectActions
import com.sleepsaver.feature.onboarding.connect.ConnectStage
import com.sleepsaver.feature.onboarding.connect.ConnectionUiState
import com.sleepsaver.feature.onboarding.connect.PermissionRows
import com.sleepsaver.feature.onboarding.connect.SourceCard
import com.sleepsaver.feature.onboarding.connect.SourceCheck
import com.sleepsaver.feature.onboarding.connect.StageCard
import com.sleepsaver.feature.onboarding.steps.syncErrorText
import com.sleepsaver.feature.onboarding.ui.LaunchGuard
import com.sleepsaver.feature.onboarding.ui.RowStatus
import com.sleepsaver.feature.onboarding.ui.StatusRow
import com.sleepsaver.feature.onboarding.ui.rememberAppLabel
import com.sleepsaver.feature.onboarding.ui.startFirst
import com.sleepsaver.feature.onboarding.ui.startSafely
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Everything the fix-it screen can trigger. */
@Stable
internal class SetupActions(
    val onBack: () -> Unit = {},
    val connect: ConnectActions = ConnectActions(),
    val onSyncNow: () -> Unit = {},
    val onLeaveDemo: () -> Unit = {},
)

@Composable
internal fun HealthConnectSetupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HealthConnectSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // One external screen at a time: a double tap must not stack two permission dialogs or two store pages.
    val launchGuard = remember { LaunchGuard() }
    val permissionLauncher = rememberLauncherForActivityResult(viewModel.permissionContract) { viewModel.onPermissionsResult() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val currentOnBack by rememberUpdatedState(onBack)
    val actions = remember(viewModel, context) {
        SetupActions(
            onBack = { currentOnBack() },
            connect = ConnectActions(
                onConnect = {
                    if (launchGuard.tryAcquire()) {
                        scope.launch {
                            val permissions = viewModel.permissionsToRequest()
                            val launched = permissions.isNotEmpty() && runCatching { permissionLauncher.launch(permissions) }.isSuccess
                            if (!launched) viewModel.onPermissionsUnavailable()
                        }
                    }
                },
                onInstall = {
                    if (launchGuard.tryAcquire() && !context.startSafely(viewModel.installOrUpdateIntent())) viewModel.checkAgain()
                },
                onOpenHealthConnect = { if (launchGuard.tryAcquire()) context.startSafely(viewModel.manageDataIntent()) },
                onOpenGarmin = { if (launchGuard.tryAcquire()) context.startFirst(viewModel.garminConnectIntents()) },
                onCheckAgain = viewModel::checkAgain,
                onUseSource = viewModel::useSource,
                onExploreDemo = viewModel::exploreDemo,
            ),
            onSyncNow = viewModel::syncNow,
            onLeaveDemo = viewModel::leaveDemo,
        )
    }
    HealthConnectSetupContent(state = state, actions = actions, modifier = modifier)
}

/**
 * Status rows (Health Connect, each permission, sleep data, last sync), each with its fix, then the same
 * explanation the onboarding Connect step gives for whatever still needs doing.
 */
@Composable
internal fun HealthConnectSetupContent(
    state: HealthConnectSetupUiState,
    actions: SetupActions,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    val connection = state.connection
    val stage = connection.stage
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            ) {
                IconButton(onClick = actions.onBack, modifier = Modifier.padding(start = Spacing.xs, top = Spacing.xs)) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.onboarding_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(
                    Modifier.padding(horizontal = Spacing.screenHorizontal),
                    verticalArrangement = Arrangement.spacedBy(Spacing.cardGap),
                ) {
                    ScreenHeader(
                        title = stringResource(R.string.setup_title),
                        subtitle = stringResource(R.string.setup_subtitle),
                        modifier = Modifier.padding(bottom = Spacing.m),
                    )
                    if (connection.demoMode) DemoCard(enabled = !state.busy, onLeaveDemo = actions.onLeaveDemo)
                    StatusCard(state, actions)
                    when {
                        stage == ConnectStage.CONNECTED -> if (connection.source is SourceCheck.Missing) {
                            SourceCard(connection.source, connection.garminInstalled, actions.connect)
                        }
                        stage != ConnectStage.CHECKING -> StageCard(stage, actions.connect, enabled = !state.busy)
                    }
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        // The "sleep access is off" card already has this as its second button.
                        if (connection.status == HealthConnectStatus.AVAILABLE && stage != ConnectStage.DENIED) {
                            TextLinkButton(stringResource(R.string.onboarding_open_health_connect), onClick = actions.connect.onOpenHealthConnect)
                        }
                        if (!connection.demoMode && stage != ConnectStage.NOT_SUPPORTED) {
                            TextLinkButton(
                                stringResource(R.string.onboarding_explore_demo),
                                onClick = actions.connect.onExploreDemo,
                                enabled = !state.busy,
                                color = SleepSaverTheme.colors.textSecondary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.l))
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
            }
        }
    }
}

@Composable
private fun DemoCard(enabled: Boolean, onLeaveDemo: () -> Unit) {
    SleepSaverCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
        IconListItem(
            icon = Icons.Rounded.Science,
            title = stringResource(R.string.setup_demo_title),
            supporting = stringResource(R.string.setup_demo_body),
        )
        Spacer(Modifier.height(Spacing.l))
        PrimaryButton(stringResource(R.string.setup_leave_demo), onClick = onLeaveDemo, enabled = enabled, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StatusCard(state: HealthConnectSetupUiState, actions: SetupActions) {
    val connection = state.connection
    SectionCard(title = stringResource(R.string.setup_status_title), modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = Spacing.xs)) {
            HealthConnectRow(connection, actions.connect)
            val permissions = connection.permissions
            if (connection.status == HealthConnectStatus.AVAILABLE && permissions != null) {
                PermissionRows(permissions, onAllow = actions.connect.onConnect)
                if (permissions.sleepGranted) SleepDataRow(connection, actions.connect)
            }
            LastSyncRow(
                sync = state.sync,
                demoMode = connection.demoMode,
                // A sync can only help once Health Connect is there and sleep is allowed; until then the fixes above do.
                canSync = connection.isConnected,
                now = state.now,
                zone = state.zone,
                onSyncNow = actions.onSyncNow,
            )
        }
    }
}

@Composable
private fun HealthConnectRow(connection: ConnectionUiState, actions: ConnectActions) {
    val title = stringResource(R.string.setup_row_health_connect)
    when (connection.status) {
        null -> StatusRow(title, stringResource(R.string.setup_checking), RowStatus.CHECKING)
        HealthConnectStatus.AVAILABLE -> StatusRow(title, stringResource(R.string.setup_hc_ok), RowStatus.OK)
        HealthConnectStatus.NOT_INSTALLED -> StatusRow(
            title, stringResource(R.string.setup_hc_missing), RowStatus.NEEDS_ACTION,
            actionLabel = stringResource(R.string.setup_install), onAction = actions.onInstall,
        )
        HealthConnectStatus.UPDATE_REQUIRED -> StatusRow(
            title, stringResource(R.string.setup_hc_update), RowStatus.NEEDS_ACTION,
            actionLabel = stringResource(R.string.setup_update), onAction = actions.onInstall,
        )
        HealthConnectStatus.NOT_SUPPORTED -> StatusRow(title, stringResource(R.string.setup_hc_unsupported), RowStatus.UNAVAILABLE)
    }
}

@Composable
private fun SleepDataRow(connection: ConnectionUiState, actions: ConnectActions) {
    val title = stringResource(R.string.setup_row_sleep_data)
    when (val source = connection.source) {
        SourceCheck.NotChecked, SourceCheck.Checking -> StatusRow(title, stringResource(R.string.setup_checking), RowStatus.CHECKING)
        is SourceCheck.Sharing -> StatusRow(title, sourceText(source.selection, missing = false), RowStatus.OK)
        is SourceCheck.Missing -> StatusRow(
            title, sourceText(source.selection, missing = true), RowStatus.NEEDS_ACTION,
            actionLabel = stringResource(R.string.onboarding_check_again), onAction = actions.onCheckAgain,
        )
    }
}

@Composable
private fun sourceText(selection: SourceSelection, missing: Boolean): String = when (selection) {
    SourceSelection.All -> stringResource(if (missing) R.string.setup_sleep_data_all_missing else R.string.setup_sleep_data_all)
    is SourceSelection.Single -> when {
        selection.packageName == SleepConfig.GARMIN_CONNECT_PACKAGE ->
            stringResource(if (missing) R.string.setup_sleep_data_missing else R.string.setup_sleep_data_garmin)
        else -> {
            val label = rememberAppLabel(selection.packageName)
            stringResource(if (missing) R.string.setup_sleep_data_other_missing else R.string.setup_sleep_data_other, label)
        }
    }
}

@Composable
private fun LastSyncRow(sync: SyncState, demoMode: Boolean, canSync: Boolean, now: Instant, zone: ZoneId, onSyncNow: () -> Unit) {
    val title = stringResource(R.string.setup_row_last_sync)
    val syncNow = stringResource(R.string.setup_sync_now).takeIf { canSync }
    val running = sync.status != SyncStatus.Idle
    val lastError = sync.lastError
    val lastSuccess = sync.lastSuccessAt
    when {
        demoMode -> StatusRow(title, stringResource(R.string.setup_sync_demo), RowStatus.OK)
        running -> StatusRow(title, stringResource(R.string.setup_syncing), RowStatus.CHECKING)
        lastError != null -> StatusRow(
            title, stringResource(R.string.setup_sync_failed, syncErrorText(lastError)), RowStatus.NEEDS_ACTION,
            actionLabel = syncNow, onAction = onSyncNow,
        )
        lastSuccess == null -> StatusRow(
            title, stringResource(R.string.setup_sync_never), RowStatus.OFF,
            actionLabel = syncNow, onAction = onSyncNow,
        )
        else -> StatusRow(
            title, syncAgoText(syncAgo(lastSuccess, now, zone), zone), RowStatus.OK,
            actionLabel = syncNow, onAction = onSyncNow,
        )
    }
}

/** How long ago the last sync was, in the coarsest unit that reads naturally. */
internal sealed interface SyncAgo {
    data object JustNow : SyncAgo
    data class Minutes(val minutes: Int) : SyncAgo
    data class Hours(val hours: Int) : SyncAgo
    data class On(val at: Instant, val today: java.time.LocalDate) : SyncAgo
}

internal fun syncAgo(at: Instant, now: Instant, zone: ZoneId): SyncAgo {
    val elapsed = Duration.between(at, now)
    return when {
        elapsed < Duration.ofMinutes(1) -> SyncAgo.JustNow
        elapsed < Duration.ofHours(1) -> SyncAgo.Minutes(elapsed.toMinutes().toInt())
        elapsed < Duration.ofHours(12) -> SyncAgo.Hours(elapsed.toHours().toInt())
        else -> SyncAgo.On(at, now.atZone(zone).toLocalDate())
    }
}

@Composable
private fun syncAgoText(ago: SyncAgo, zone: ZoneId): String = when (ago) {
    SyncAgo.JustNow -> stringResource(R.string.setup_sync_just_now)
    is SyncAgo.Minutes -> pluralStringResource(R.plurals.setup_sync_minutes_ago, ago.minutes, ago.minutes)
    is SyncAgo.Hours -> pluralStringResource(R.plurals.setup_sync_hours_ago, ago.hours, ago.hours)
    is SyncAgo.On -> stringResource(
        R.string.setup_sync_on,
        formatShortDate(ago.at.atZone(zone).toLocalDate(), ago.today),
        clockTime(ago.at, zone),
    )
}
