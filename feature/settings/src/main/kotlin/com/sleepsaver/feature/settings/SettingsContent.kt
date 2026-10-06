package com.sleepsaver.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.data.repository.ClockFormat
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.domain.model.Confidence
import com.sleepsaver.core.ui.components.LoadingOrb
import com.sleepsaver.core.ui.components.ScreenHeader
import com.sleepsaver.core.ui.components.SegmentedControl
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.WellnessDisclaimer
import com.sleepsaver.core.ui.format.LocalUse24HourClock
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.formatClockTime
import com.sleepsaver.core.ui.format.formatDuration
import com.sleepsaver.core.ui.format.formatShortDate
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing

/** The screen's groups, in order. Each is one list item, right after the title. */
internal enum class SettingsSection { SLEEP, GOALS, DATA, NOTIFICATIONS, DISPLAY, ABOUT, RESET }

/** The list index of [section] (the title is item 0), e.g. to open the screen scrolled to it. */
internal fun SettingsSection.listIndex(): Int = ordinal + 1

/** Content wider than this (tablets, landscape) stays centred instead of stretching. */
private val MaxContentWidth = 640.dp

/**
 * The settings screen without its ViewModel: [state] in, [actions] out. Which dialog is open is UI state kept
 * here; [initialDialog] opens one straight away (for tests).
 */
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    initialDialog: SettingsDialog? = null,
) {
    var dialog by rememberSaveable { mutableStateOf(initialDialog) }
    val snackbar = remember { SnackbarHostState() }
    val currentActions by rememberUpdatedState(actions)
    val messageText = state.message?.let { stringResource(it.textRes) }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            currentActions.onMessageShown()
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
            TopBar(showTitle = scrolled && !state.loading, onBack = actions::onBack)
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingOrb(label = stringResource(R.string.settings_loading))
                }
            } else {
                SettingsList(state, actions, listState, openDialog = { dialog = it })
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(Spacing.l),
        ) { data ->
            Snackbar(
                snackbarData = data,
                shape = MaterialTheme.shapes.medium,
                containerColor = SleepSaverTheme.colors.cardRaised,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    SettingsDialogs(
        dialog = if (state.resetting) SettingsDialog.RESET else dialog,
        state = state,
        actions = actions,
        close = { dialog = null },
    )
}

@Composable
private fun TopBar(showTitle: Boolean, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = Spacing.xs, end = Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.settings_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        AnimatedVisibility(visible = showTitle, enter = fadeIn(), exit = fadeOut()) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = Spacing.xs),
            )
        }
    }
}

@Composable
private fun SettingsList(
    state: SettingsUiState,
    actions: SettingsActions,
    listState: LazyListState,
    openDialog: (SettingsDialog) -> Unit,
) {
    val groupModifier = Modifier
        .widthIn(max = MaxContentWidth)
        .fillMaxWidth()
        .padding(top = Spacing.xl)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "title") {
            ScreenHeader(
                title = stringResource(R.string.settings_title),
                modifier = Modifier
                    .widthIn(max = MaxContentWidth)
                    .padding(start = Spacing.xs, top = Spacing.xs),
            )
        }
        SettingsSection.entries.forEach { section ->
            item(key = section) {
                when (section) {
                    SettingsSection.SLEEP -> SleepGroup(state, actions, openDialog, groupModifier)
                    SettingsSection.GOALS -> GoalsGroup(state, openDialog, groupModifier)
                    SettingsSection.DATA -> DataGroup(state, actions, openDialog, groupModifier)
                    SettingsSection.NOTIFICATIONS -> NotificationsGroup(state, actions, groupModifier)
                    SettingsSection.DISPLAY -> DisplayGroup(state, actions, groupModifier)
                    SettingsSection.ABOUT -> AboutGroup(state, groupModifier)
                    SettingsSection.RESET -> ResetGroup(state, openDialog, groupModifier)
                }
            }
        }
        item(key = "end") {
            Spacer(
                Modifier
                    .padding(bottom = Spacing.xxl)
                    .windowInsetsBottomHeight(WindowInsets.safeDrawing),
            )
        }
    }
}

// ---------------------------------------------------------------- Groups

@Composable
private fun SleepGroup(
    state: SettingsUiState,
    actions: SettingsActions,
    openDialog: (SettingsDialog) -> Unit,
    modifier: Modifier,
) {
    val use24h = LocalUse24HourClock.current
    SettingsGroup(stringResource(R.string.settings_group_sleep), modifier) {
        SettingsRow(
            icon = Icons.Rounded.Bedtime,
            title = stringResource(R.string.settings_sleep_need),
            supporting = stringResource(R.string.settings_sleep_need_body),
            onClick = { openDialog(SettingsDialog.SLEEP_NEED) },
            value = formatDuration(state.sleepNeed),
        )
        state.needSuggestion?.let { suggestion ->
            SettingsNote(
                icon = Icons.Rounded.AutoAwesome,
                title = stringResource(
                    R.string.settings_need_suggestion,
                    formatDuration(suggestion.need),
                    stringResource(suggestion.confidence.textRes),
                ),
                body = pluralStringResource(R.plurals.settings_need_suggestion_body, suggestion.nightsUsed, suggestion.nightsUsed),
                inlineAction = true,
                action = { TextLinkButton(stringResource(R.string.settings_use), onClick = actions::onUseSuggestedNeed) },
            )
        }
        RowDivider()
        SettingsRow(
            icon = Icons.Rounded.WbSunny,
            title = stringResource(R.string.settings_typical_wake),
            supporting = state.learnedWake
                ?.let { stringResource(R.string.settings_typical_wake_learned, formatClockTime(it, use24h)) }
                ?: stringResource(R.string.settings_typical_wake_caption),
            onClick = { openDialog(SettingsDialog.TYPICAL_WAKE) },
            value = formatClockTime(state.typicalWake, use24h),
        )
    }
}

@Composable
private fun GoalsGroup(state: SettingsUiState, openDialog: (SettingsDialog) -> Unit, modifier: Modifier) {
    SettingsGroup(stringResource(R.string.settings_group_goals), modifier) {
        SettingsRow(
            icon = Icons.Rounded.Waves,
            title = stringResource(R.string.settings_streak),
            supporting = stringResource(R.string.settings_streak_body),
            onClick = { openDialog(SettingsDialog.STREAK) },
            value = formatDuration(state.streakThreshold),
        )
        RowDivider()
        SettingsRow(
            icon = Icons.Rounded.Speed,
            title = stringResource(R.string.settings_debt_levels),
            supporting = stringResource(
                R.string.settings_debt_levels_value,
                formatDuration(state.lowDebtMax),
                formatDuration(state.highDebtMin),
            ),
            onClick = { openDialog(SettingsDialog.DEBT_LEVELS) },
            trailing = { ValueChevron() },
        )
    }
}

@Composable
private fun DataGroup(
    state: SettingsUiState,
    actions: SettingsActions,
    openDialog: (SettingsDialog) -> Unit,
    modifier: Modifier,
) {
    SettingsGroup(stringResource(R.string.settings_group_data), modifier) {
        val selected = state.sourceOptions.firstOrNull { it.selection == state.source }
        SettingsRow(
            icon = Icons.Rounded.Watch,
            title = stringResource(R.string.settings_source),
            supporting = if (state.demoMode) {
                stringResource(R.string.settings_source_demo)
            } else {
                selected?.title(short = true) ?: stringResource(R.string.settings_source_garmin)
            },
            onClick = { openDialog(SettingsDialog.SOURCE) },
            trailing = { ValueChevron() },
        )
        RowDivider()
        SwitchRow(
            icon = Icons.Rounded.Science,
            title = stringResource(R.string.settings_demo),
            supporting = stringResource(if (state.demoSwitching) R.string.settings_demo_switching else R.string.settings_demo_body),
            checked = state.demoMode,
            enabled = !state.demoSwitching,
            onCheckedChange = { on -> if (on) actions.onDemoModeChange(true) else openDialog(SettingsDialog.DEMO_OFF) },
        )
        RowDivider()
        HealthConnectRow(state.healthConnect, actions::onOpenHealthConnect)
        RowDivider()
        SyncRow(state.sync, state, actions::onSyncNow)
        RowDivider()
        ExportRow(ExportFormat.CSV, state.exporting, actions)
        RowDivider()
        ExportRow(ExportFormat.JSON, state.exporting, actions)
        RowDivider()
        SettingsRow(
            icon = Icons.Rounded.PrivacyTip,
            title = stringResource(R.string.settings_privacy),
            supporting = stringResource(R.string.settings_privacy_body),
            onClick = actions::onOpenPrivacyPolicy,
            trailing = { ValueChevron() },
        )
    }
}

/** Health Connect's status; when something needs fixing, a short action ("Allow", "Install") replaces the chevron. */
@Composable
private fun HealthConnectRow(access: HealthConnectAccess, onOpen: () -> Unit) {
    val fixLabel = access.fixLabelRes?.let { stringResource(it) }
    SettingsRow(
        icon = Icons.Rounded.MonitorHeart,
        title = stringResource(R.string.settings_health_connect),
        supporting = stringResource(access.textRes),
        onClick = onOpen,
        trailing = {
            if (fixLabel != null) {
                // Shifted by its own inner padding so the label ends where the chevrons do.
                TextLinkButton(fixLabel, onClick = onOpen, modifier = Modifier.offset(x = 10.dp))
            } else {
                ValueChevron()
            }
        },
    )
}

@Composable
private fun SyncRow(sync: SyncUi, state: SettingsUiState, onSyncNow: () -> Unit) {
    val feedback: String? = when {
        state.demoMode -> stringResource(R.string.settings_sync_demo)
        sync.inProgress -> stringResource(R.string.settings_syncing)
        sync.feedback is SyncFeedback.Success -> if (sync.feedback.sessionsChanged == 0) {
            stringResource(R.string.settings_sync_up_to_date)
        } else {
            pluralStringResource(R.plurals.settings_sync_updated, sync.feedback.sessionsChanged, sync.feedback.sessionsChanged)
        }
        sync.feedback is SyncFeedback.Failed -> stringResource(sync.feedback.error.textRes)
        sync.lastError != null -> stringResource(sync.lastError.textRes)
        else -> null
    }
    SettingsRow(
        icon = Icons.Rounded.Sync,
        title = lastSyncedText(sync, state),
        supporting = feedback,
        // Nothing syncs while demo nights stand in for the real ones.
        trailing = if (state.demoMode) null else @Composable { SyncTrailing(sync.inProgress, onSyncNow) },
    )
}

@Composable
private fun SyncTrailing(inProgress: Boolean, onSyncNow: () -> Unit) {
    if (inProgress) {
        RowProgress()
    } else {
        // Shifted by its own inner padding so the label ends where the chevrons do.
        TextLinkButton(
            stringResource(R.string.settings_sync_now),
            onClick = onSyncNow,
            modifier = Modifier.offset(x = 10.dp),
        )
    }
}

@Composable
private fun lastSyncedText(sync: SyncUi, state: SettingsUiState): String {
    val at = sync.lastSyncedAt ?: return stringResource(R.string.settings_never_synced)
    val time = clockTime(at, state.zone)
    val date = at.atZone(state.zone).toLocalDate()
    val today = state.now.atZone(state.zone).toLocalDate()
    return when (date) {
        today -> stringResource(R.string.settings_last_synced, time)
        today.minusDays(1) -> stringResource(R.string.settings_last_synced_yesterday, time)
        else -> stringResource(R.string.settings_last_synced_date, formatShortDate(date, today), time)
    }
}

@Composable
private fun ExportRow(format: ExportFormat, exporting: ExportFormat?, actions: SettingsActions) {
    val busy = exporting == format
    SettingsRow(
        icon = if (format == ExportFormat.CSV) Icons.Rounded.TableChart else Icons.Rounded.DataObject,
        title = stringResource(if (format == ExportFormat.CSV) R.string.settings_export_csv else R.string.settings_export_json),
        supporting = when {
            busy -> stringResource(R.string.settings_exporting)
            format == ExportFormat.CSV -> stringResource(R.string.settings_export_csv_body)
            else -> stringResource(R.string.settings_export_json_body)
        },
        enabled = exporting == null || busy,
        onClick = { actions.onExport(format) },
        trailing = {
            if (busy) {
                RowProgress()
            } else {
                Icon(
                    Icons.Rounded.FileDownload,
                    contentDescription = null,
                    tint = SleepSaverTheme.colors.textTertiary,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
    )
}

@Composable
private fun NotificationsGroup(state: SettingsUiState, actions: SettingsActions, modifier: Modifier) {
    SettingsGroup(stringResource(R.string.settings_group_notifications), modifier) {
        if (state.notifications != NotificationAccess.ALLOWED) {
            val canAsk = state.notifications == NotificationAccess.PERMISSION_NEEDED
            SettingsNote(
                icon = Icons.Rounded.NotificationsOff,
                title = stringResource(R.string.settings_notifications_off),
                body = stringResource(R.string.settings_notifications_off_body),
                action = {
                    TextLinkButton(
                        text = stringResource(
                            if (canAsk) R.string.settings_notifications_allow else R.string.settings_notifications_open_settings,
                        ),
                        onClick = if (canAsk) actions::onAllowNotifications else actions::onOpenNotificationSettings,
                    )
                },
            )
        }
        SwitchRow(
            icon = Icons.Rounded.WbTwilight,
            title = stringResource(R.string.settings_morning_review),
            supporting = stringResource(R.string.settings_morning_review_body),
            checked = state.morningReviewEnabled,
            onCheckedChange = actions::onMorningReviewChange,
        )
        RowDivider()
        SettingsRow(
            icon = Icons.Rounded.NotificationsActive,
            title = stringResource(R.string.settings_habit_reminders),
            supporting = stringResource(R.string.settings_habit_reminders_body),
            onClick = actions::onOpenHabits,
            trailing = { ValueChevron() },
        )
        RowDivider()
        SettingsRow(
            icon = Icons.Rounded.Tune,
            title = stringResource(R.string.settings_system_notifications),
            supporting = stringResource(R.string.settings_system_notifications_body),
            onClick = actions::onOpenNotificationSettings,
            trailing = {
                Icon(
                    Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    tint = SleepSaverTheme.colors.textTertiary,
                    modifier = Modifier
                        .padding(end = 2.dp)
                        .size(20.dp),
                )
            },
        )
    }
}

@Composable
private fun DisplayGroup(state: SettingsUiState, actions: SettingsActions, modifier: Modifier) {
    val formats = ClockFormat.entries
    SettingsGroup(stringResource(R.string.settings_group_display), modifier) {
        SettingsRow(
            icon = Icons.Rounded.Schedule,
            title = stringResource(R.string.settings_time_format),
            supporting = stringResource(R.string.settings_time_format_example, clockTime(state.now, state.zone)),
        )
        SegmentedControl(
            options = formats.map { stringResource(it.textRes) },
            selectedIndex = formats.indexOf(state.clockFormat),
            onSelectedChange = { actions.onClockFormatChange(formats[it]) },
            modifier = Modifier.padding(start = Spacing.screenHorizontal, end = Spacing.screenHorizontal, top = Spacing.s, bottom = Spacing.m),
        )
    }
}

@Composable
private fun AboutGroup(state: SettingsUiState, modifier: Modifier) {
    SettingsGroup(stringResource(R.string.settings_group_about), modifier) {
        SettingsRow(
            icon = Icons.Rounded.NightsStay,
            title = stringResource(R.string.settings_app_name),
            supporting = state.appVersion.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.settings_version, it) },
        )
        RowDivider()
        SettingsRow(
            icon = Icons.Rounded.Lock,
            title = stringResource(R.string.settings_private_title),
            supporting = stringResource(R.string.settings_on_device),
        )
        WellnessDisclaimer(
            Modifier.padding(start = Spacing.screenHorizontal, end = Spacing.screenHorizontal, top = Spacing.s, bottom = Spacing.m),
        )
    }
}

@Composable
private fun ResetGroup(state: SettingsUiState, openDialog: (SettingsDialog) -> Unit, modifier: Modifier) {
    SettingsGroup(stringResource(R.string.settings_group_reset), modifier) {
        SettingsRow(
            icon = Icons.Rounded.DeleteForever,
            title = stringResource(R.string.settings_reset),
            supporting = stringResource(if (state.resetting) R.string.settings_resetting else R.string.settings_reset_body),
            tint = MaterialTheme.colorScheme.error,
            enabled = !state.resetting,
            onClick = { openDialog(SettingsDialog.RESET) },
            trailing = { if (state.resetting) RowProgress() else ValueChevron() },
        )
    }
}

// ---------------------------------------------------------------- Dialogs

@Composable
private fun SettingsDialogs(
    dialog: SettingsDialog?,
    state: SettingsUiState,
    actions: SettingsActions,
    close: () -> Unit,
) {
    if (dialog == null || state.loading) return
    val dismissible = !(dialog == SettingsDialog.RESET && state.resetting)
    SettingsDialogWindow(onDismissRequest = { if (dismissible) close() }, dismissible = dismissible) {
        when (dialog) {
            SettingsDialog.SLEEP_NEED -> SleepNeedDialogCard(
                current = state.sleepNeed,
                suggestion = state.needSuggestion,
                onSave = { actions.onSleepNeedChange(it); close() },
                onDismiss = close,
            )
            SettingsDialog.TYPICAL_WAKE -> TypicalWakeDialogCard(
                current = state.typicalWake,
                learnedWake = state.learnedWake,
                onSave = { actions.onTypicalWakeChange(it); close() },
                onDismiss = close,
            )
            SettingsDialog.STREAK -> StreakDialogCard(
                current = state.streakThreshold,
                onSave = { actions.onStreakThresholdChange(it); close() },
                onDismiss = close,
            )
            SettingsDialog.DEBT_LEVELS -> DebtLevelsDialogCard(
                lowMax = state.lowDebtMax,
                highMin = state.highDebtMin,
                onSave = { low, high -> actions.onDebtLevelsChange(low, high); close() },
                onDismiss = close,
            )
            SettingsDialog.SOURCE -> SourceDialogCard(
                options = state.sourceOptions,
                selected = state.source,
                demoMode = state.demoMode,
                onSave = { actions.onSourceSelected(it); close() },
                onDismiss = close,
            )
            SettingsDialog.DEMO_OFF -> DemoOffDialogCard(
                onConfirm = { actions.onDemoModeChange(false); close() },
                onDismiss = close,
            )
            SettingsDialog.RESET -> ResetDialogCard(
                resetting = state.resetting,
                onConfirm = { actions.onResetAll(); close() },
                onDismiss = close,
            )
        }
    }
}

// ---------------------------------------------------------------- Text for enums

private val SettingsMessage.textRes: Int
    get() = when (this) {
        SettingsMessage.EXPORTED_CSV -> R.string.settings_exported_csv
        SettingsMessage.EXPORTED_JSON -> R.string.settings_exported_json
        SettingsMessage.EXPORT_FAILED -> R.string.settings_export_failed
        SettingsMessage.EXPORT_UNAVAILABLE -> R.string.settings_export_unavailable
        SettingsMessage.DEMO_FAILED -> R.string.settings_demo_failed
        SettingsMessage.RESET_FAILED -> R.string.settings_reset_failed
        SettingsMessage.SAVE_FAILED -> R.string.settings_save_failed
        SettingsMessage.SETTINGS_UNAVAILABLE -> R.string.settings_no_settings_app
    }

private val Confidence.textRes: Int
    get() = when (this) {
        Confidence.LOW -> R.string.settings_confidence_low
        Confidence.MEDIUM -> R.string.settings_confidence_medium
        Confidence.HIGH -> R.string.settings_confidence_high
    }

/** The short fix-it action on the Health Connect row, or null when there's nothing to fix there. */
private val HealthConnectAccess.fixLabelRes: Int?
    get() = when (this) {
        HealthConnectAccess.PERMISSION_NEEDED -> R.string.settings_hc_fix_allow
        HealthConnectAccess.NOT_INSTALLED -> R.string.settings_hc_fix_install
        HealthConnectAccess.UPDATE_REQUIRED -> R.string.settings_hc_fix_update
        HealthConnectAccess.CHECKING, HealthConnectAccess.CONNECTED, HealthConnectAccess.NOT_SUPPORTED -> null
    }

private val HealthConnectAccess.textRes: Int
    get() = when (this) {
        HealthConnectAccess.CHECKING -> R.string.settings_hc_checking
        HealthConnectAccess.CONNECTED -> R.string.settings_hc_connected
        HealthConnectAccess.PERMISSION_NEEDED -> R.string.settings_hc_permission
        HealthConnectAccess.NOT_INSTALLED -> R.string.settings_hc_not_installed
        HealthConnectAccess.UPDATE_REQUIRED -> R.string.settings_hc_update
        HealthConnectAccess.NOT_SUPPORTED -> R.string.settings_hc_not_supported
    }

private val SyncError?.textRes: Int
    get() = when (this) {
        SyncError.HEALTH_CONNECT_NOT_INSTALLED -> R.string.settings_sync_error_not_installed
        SyncError.HEALTH_CONNECT_UPDATE_REQUIRED -> R.string.settings_sync_error_update
        SyncError.PERMISSION_DENIED -> R.string.settings_sync_error_permission
        SyncError.RATE_LIMITED -> R.string.settings_sync_error_rate_limited
        SyncError.BACKGROUND_NOT_ALLOWED -> R.string.settings_sync_error_background
        SyncError.UNKNOWN, null -> R.string.settings_sync_error_unknown
    }

private val ClockFormat.textRes: Int
    get() = when (this) {
        ClockFormat.SYSTEM -> R.string.settings_time_format_system
        ClockFormat.H12 -> R.string.settings_time_format_12
        ClockFormat.H24 -> R.string.settings_time_format_24
    }
