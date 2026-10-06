package com.sleepsaver.feature.habits

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.ui.components.EmptyState
import com.sleepsaver.core.ui.components.LoadingOrb
import com.sleepsaver.core.ui.components.ScreenHeader
import com.sleepsaver.core.ui.components.SectionHeader
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.WellnessDisclaimer
import com.sleepsaver.core.ui.components.title
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import java.time.ZoneId

@Composable
internal fun HabitsScreen(
    onBack: () -> Unit,
    onOpenHabit: (HabitType) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HabitsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val notifications = rememberNotificationAccess()
    HabitsContent(
        state = state,
        onBack = onBack,
        onOpenHabit = onOpenHabit,
        onToggleHabit = { type, on ->
            viewModel.setEnabled(type, on)
            // Switching on a habit whose reminder is set turns that reminder on too.
            val reminderSet = (state as? HabitsUiState.Ready)?.available?.any { it.type == type && it.reminderEnabled } == true
            if (on && reminderSet) notifications.onReminderTurnedOn()
        },
        onToggleReminder = { type, on ->
            viewModel.setReminder(type, on)
            if (on) notifications.onReminderTurnedOn()
        },
        notificationsBlocked = !notifications.allowed,
        onOpenNotificationSettings = notifications::openSettings,
        modifier = modifier,
    )
}

@Composable
internal fun HabitsContent(
    state: HabitsUiState,
    onBack: () -> Unit,
    onOpenHabit: (HabitType) -> Unit,
    onToggleHabit: (HabitType, Boolean) -> Unit,
    onToggleReminder: (HabitType, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    notificationsBlocked: Boolean = false,
    onOpenNotificationSettings: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            TopIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                description = stringResource(R.string.habits_back),
                onClick = onBack,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
            when (state) {
                HabitsUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingOrb(label = stringResource(R.string.habits_loading))
                }
                HabitsUiState.Unavailable -> EmptyState(
                    icon = Icons.Rounded.ErrorOutline,
                    title = stringResource(R.string.habits_unavailable_title),
                    body = stringResource(R.string.habits_unavailable_body),
                    primaryActionLabel = stringResource(R.string.habits_go_back),
                    onPrimaryAction = onBack,
                    modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.xxxl),
                )
                is HabitsUiState.Ready -> HabitsList(
                    state = state,
                    listState = listState,
                    notificationsBlocked = notificationsBlocked,
                    onOpenHabit = onOpenHabit,
                    onToggleHabit = onToggleHabit,
                    onToggleReminder = onToggleReminder,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                )
            }
        }
    }
}

@Composable
private fun HabitsList(
    state: HabitsUiState.Ready,
    listState: LazyListState,
    notificationsBlocked: Boolean,
    onOpenHabit: (HabitType) -> Unit,
    onToggleHabit: (HabitType, Boolean) -> Unit,
    onToggleReminder: (HabitType, Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.screenHorizontal, end = Spacing.screenHorizontal, top = Spacing.xs),
    ) {
        item(key = "header") {
            ScreenHeader(
                title = stringResource(R.string.habits_title),
                subtitle = stringResource(R.string.habits_subtitle),
            )
        }
        if (notificationsBlocked && state.anyReminderOn) {
            item(key = "notifications") {
                NotificationsOffNotice(onOpenNotificationSettings, Modifier.padding(top = Spacing.xl))
            }
        }
        if (state.times == TimesStatus.NO_NIGHTS) {
            item(key = "pending") { TimesPendingNote(Modifier.padding(top = Spacing.xl)) }
        }
        habitGroup(
            key = "scheduled",
            title = R.string.habits_section_scheduled,
            emptyText = R.string.habits_scheduled_empty,
            rows = state.scheduled,
            zone = state.zone,
            onOpenHabit = onOpenHabit,
            onToggleHabit = onToggleHabit,
            onToggleReminder = onToggleReminder,
        )
        habitGroup(
            key = "available",
            title = R.string.habits_section_available,
            emptyText = R.string.habits_available_empty,
            rows = state.available,
            zone = state.zone,
            onOpenHabit = onOpenHabit,
            onToggleHabit = onToggleHabit,
            onToggleReminder = onToggleReminder,
        )
        item(key = "disclaimer") { WellnessDisclaimer(Modifier.padding(top = Spacing.xxl)) }
        item(key = "insets") {
            Spacer(Modifier.height(Spacing.xl))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
        }
    }
}

private fun LazyListScope.habitGroup(
    key: String,
    title: Int,
    emptyText: Int,
    rows: List<HabitRowState>,
    zone: ZoneId?,
    onOpenHabit: (HabitType) -> Unit,
    onToggleHabit: (HabitType, Boolean) -> Unit,
    onToggleReminder: (HabitType, Boolean) -> Unit,
) {
    item(key = "$key-title") {
        SectionHeader(stringResource(title), Modifier.padding(top = Spacing.xxl, bottom = Spacing.m))
    }
    item(key = "$key-rows") {
        SleepSaverCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.xs)) {
            if (rows.isEmpty()) {
                Text(
                    text = stringResource(emptyText),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepSaverTheme.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = Spacing.l),
                )
            }
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = RowDividerInset, end = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                HabitRow(
                    row = row,
                    zone = zone,
                    onOpen = { onOpenHabit(row.type) },
                    onToggleHabit = { onToggleHabit(row.type, it) },
                    onToggleReminder = { onToggleReminder(row.type, it) },
                )
            }
        }
    }
}

/** Dividers start under the text, past the 40 dp badge and its gap. */
private val RowDividerInset = 16.dp + 40.dp + 14.dp

@Composable
private fun HabitRow(
    row: HabitRowState,
    zone: ZoneId?,
    onOpen: () -> Unit,
    onToggleHabit: (Boolean) -> Unit,
    onToggleReminder: (Boolean) -> Unit,
) {
    val title = row.type.title()
    // With very large text the controls move under the text, so the words keep most of the row's width.
    val stacked = LocalDensity.current.fontScale >= StackedFontScale
    val controls = @Composable {
        ReminderBell(
            checked = row.reminderEnabled && row.enabled,
            enabled = row.enabled,
            onCheckedChange = onToggleReminder,
            description = stringResource(R.string.habits_toggle_reminder, title),
        )
        val switchDescription = stringResource(R.string.habits_toggle_schedule, title)
        HabitSwitch(
            checked = row.enabled,
            onCheckedChange = onToggleHabit,
            modifier = Modifier
                .padding(start = 4.dp, end = 8.dp)
                .semantics { contentDescription = switchDescription },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.habits_open_habit), role = Role.Button, onClick = onOpen)
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = if (stacked) Alignment.Top else Alignment.CenterVertically,
    ) {
        HabitBadge(row.type, active = row.enabled)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp, end = if (stacked) 0.dp else 4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = stringResource(row.type.taglineRes),
                style = MaterialTheme.typography.bodyMedium,
                color = SleepSaverTheme.colors.textSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = habitTimeText(row.time, row.windowEnd, zone),
                style = MaterialTheme.typography.labelLarge,
                color = if (row.enabled && row.time != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    SleepSaverTheme.colors.textTertiary
                },
                modifier = Modifier.padding(top = 6.dp),
            )
            if (stacked) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xs),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) { controls() }
            }
        }
        if (!stacked) controls()
    }
}

/** Font scale from which list rows put their controls under the text. */
private const val StackedFontScale = 1.5f

/** The reminder toggle: a filled bell when on; greyed out while the habit itself is off. */
@Composable
private fun ReminderBell(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit, description: String) {
    IconToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        val tint = when {
            !enabled -> SleepSaverTheme.colors.textTertiary.copy(alpha = 0.45f)
            checked -> MaterialTheme.colorScheme.primary
            else -> SleepSaverTheme.colors.textSecondary
        }
        Icon(
            imageVector = if (checked) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** "Times appear once your first night has synced." */
@Composable
internal fun TimesPendingNote(modifier: Modifier = Modifier, text: String = stringResource(R.string.habits_times_pending)) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Rounded.Schedule,
            contentDescription = null,
            tint = SleepSaverTheme.colors.textTertiary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = SleepSaverTheme.colors.textSecondary)
    }
}
