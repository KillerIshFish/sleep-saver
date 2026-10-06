package com.sleepsaver.feature.habits

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.domain.model.HabitAnchor
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.ui.components.EmptyState
import com.sleepsaver.core.ui.components.InfoChip
import com.sleepsaver.core.ui.components.LoadingOrb
import com.sleepsaver.core.ui.components.MinuteStepper
import com.sleepsaver.core.ui.components.SecondaryButton
import com.sleepsaver.core.ui.components.SectionCard
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.WellnessDisclaimer
import com.sleepsaver.core.ui.components.title
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.formatDuration
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import java.time.Duration
import kotlin.math.abs

@Composable
internal fun HabitDetailScreen(
    onBack: () -> Unit,
    onChooseActivities: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HabitDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val notifications = rememberNotificationAccess()
    HabitDetailContent(
        state = state,
        onBack = onBack,
        onToggleHabit = { on ->
            viewModel.setEnabled(on)
            // Switching the habit on brings its stored reminder back with it.
            if (on && (state as? HabitDetailUiState.Ready)?.reminderEnabled == true) notifications.onReminderTurnedOn()
        },
        onToggleReminder = { on ->
            viewModel.setReminder(on)
            if (on) notifications.onReminderTurnedOn()
        },
        onTimingChange = viewModel::setTimingValue,
        onResetTiming = viewModel::resetTiming,
        onChooseActivities = onChooseActivities,
        notificationsBlocked = !notifications.allowed,
        onOpenNotificationSettings = notifications::openSettings,
        modifier = modifier,
    )
}

@Composable
internal fun HabitDetailContent(
    state: HabitDetailUiState,
    onBack: () -> Unit,
    onToggleHabit: (Boolean) -> Unit,
    onToggleReminder: (Boolean) -> Unit,
    onTimingChange: (Int) -> Unit,
    onResetTiming: () -> Unit,
    onChooseActivities: () -> Unit,
    modifier: Modifier = Modifier,
    notificationsBlocked: Boolean = false,
    onOpenNotificationSettings: () -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
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
                HabitDetailUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingOrb(label = stringResource(R.string.habits_loading))
                }
                HabitDetailUiState.NotFound -> EmptyState(
                    icon = Icons.Rounded.SearchOff,
                    title = stringResource(R.string.habits_detail_not_found_title),
                    body = stringResource(R.string.habits_detail_not_found_body),
                    primaryActionLabel = stringResource(R.string.habits_go_back),
                    onPrimaryAction = onBack,
                    modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.xxxl),
                )
                HabitDetailUiState.Unavailable -> EmptyState(
                    icon = Icons.Rounded.ErrorOutline,
                    title = stringResource(R.string.habits_unavailable_title),
                    body = stringResource(R.string.habits_unavailable_body),
                    primaryActionLabel = stringResource(R.string.habits_go_back),
                    onPrimaryAction = onBack,
                    modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.xxxl),
                )
                is HabitDetailUiState.Ready -> HabitDetailBody(
                    state = state,
                    scrollState = scrollState,
                    notificationsBlocked = notificationsBlocked,
                    onToggleHabit = onToggleHabit,
                    onToggleReminder = onToggleReminder,
                    onTimingChange = onTimingChange,
                    onResetTiming = onResetTiming,
                    onChooseActivities = onChooseActivities,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                )
            }
        }
    }
}

@Composable
private fun HabitDetailBody(
    state: HabitDetailUiState.Ready,
    scrollState: ScrollState,
    notificationsBlocked: Boolean,
    onToggleHabit: (Boolean) -> Unit,
    onToggleReminder: (Boolean) -> Unit,
    onTimingChange: (Int) -> Unit,
    onResetTiming: () -> Unit,
    onChooseActivities: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = Spacing.screenHorizontal),
    ) {
        HabitBadge(state.type, active = true, size = 64.dp, modifier = Modifier.padding(top = Spacing.xs))
        Text(
            text = state.type.title(),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .padding(top = Spacing.l)
                .semantics { heading() },
        )
        Text(
            text = stringResource(state.type.whyRes),
            style = MaterialTheme.typography.bodyLarge,
            color = SleepSaverTheme.colors.textSecondary,
            modifier = Modifier.padding(top = Spacing.s),
        )
        TodayCard(state, Modifier.padding(top = Spacing.xl))
        SettingsCard(state, onToggleHabit, onToggleReminder, Modifier.padding(top = Spacing.cardGap))
        if (notificationsBlocked && state.enabled && state.reminderEnabled) {
            NotificationsOffNotice(onOpenNotificationSettings, Modifier.padding(top = Spacing.cardGap))
        }
        if (state.type == HabitType.EVENING_ROUTINE) {
            ActivitiesCard(state.activities, onChooseActivities, Modifier.padding(top = Spacing.cardGap))
        }
        TimingCard(state, onTimingChange, onResetTiming, Modifier.padding(top = Spacing.cardGap))
        WellnessDisclaimer(Modifier.padding(top = Spacing.xxl))
        Spacer(Modifier.height(Spacing.xl))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
    }
}

/** Today's time, big, with what it's measured from. */
@Composable
private fun TodayCard(state: HabitDetailUiState.Ready, modifier: Modifier = Modifier) {
    SleepSaverCard(modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.habits_detail_today),
            style = MaterialTheme.typography.labelLarge,
            color = SleepSaverTheme.colors.textTertiary,
        )
        // Windows ("10:15 – 11:15 PM") are long: shrink to one line rather than wrap.
        BasicText(
            text = habitTimeText(state.time, state.windowEnd, state.zone),
            style = MaterialTheme.typography.displaySmall.copy(color = MaterialTheme.colorScheme.onSurface),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 40.sp, stepSize = 2.sp),
            modifier = Modifier.padding(top = Spacing.xs),
        )
        val explanation = when (state.times) {
            TimesStatus.READY -> anchorText(state)
            TimesStatus.LOADING -> stringResource(R.string.habits_detail_times_loading)
            TimesStatus.NO_NIGHTS -> stringResource(R.string.habits_detail_times_pending)
        }
        if (explanation != null) {
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = SleepSaverTheme.colors.textSecondary,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

@Composable
private fun SettingsCard(
    state: HabitDetailUiState.Ready,
    onToggleHabit: (Boolean) -> Unit,
    onToggleReminder: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SleepSaverCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.xs)) {
        ToggleRow(
            title = stringResource(R.string.habits_detail_show),
            body = stringResource(R.string.habits_detail_show_body),
            checked = state.enabled,
            onCheckedChange = onToggleHabit,
        )
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        ToggleRow(
            title = stringResource(R.string.habits_detail_remind),
            body = stringResource(
                if (state.enabled) R.string.habits_detail_remind_body else R.string.habits_detail_remind_needs_habit,
            ),
            checked = state.reminderEnabled && state.enabled,
            onCheckedChange = onToggleReminder,
            enabled = state.enabled,
        )
    }
}

/** A whole-row switch: tapping the text toggles it too. */
@Composable
private fun ToggleRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(end = Spacing.m),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else SleepSaverTheme.colors.textTertiary,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = SleepSaverTheme.colors.textSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // The row is the toggle; the switch only shows its state.
        HabitSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun ActivitiesCard(activities: List<RoutineActivity>, onChooseActivities: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard(title = stringResource(R.string.habits_detail_activities), modifier = modifier.fillMaxWidth()) {
        if (activities.isEmpty()) {
            Text(
                stringResource(R.string.habits_detail_activities_none),
                style = MaterialTheme.typography.bodyMedium,
                color = SleepSaverTheme.colors.textSecondary,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                activities.forEach { InfoChip(text = "${it.emoji}  ${it.label}") }
            }
        }
        SecondaryButton(
            text = stringResource(R.string.habits_detail_choose_activities),
            onClick = onChooseActivities,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs),
        )
    }
}

@Composable
private fun TimingCard(
    state: HabitDetailUiState.Ready,
    onTimingChange: (Int) -> Unit,
    onResetTiming: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val format = timingFormatter(state.timing.kind)
    SectionCard(title = stringResource(R.string.habits_detail_timing), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            MinuteStepper(
                minutes = state.value,
                onMinutesChange = onTimingChange,
                step = state.timing.step,
                min = state.timing.min,
                max = state.timing.max,
                format = format,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            Text(
                text = timingCaption(state.timing.kind, state.anchor),
                style = MaterialTheme.typography.bodyMedium,
                color = SleepSaverTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.s),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = Spacing.xs))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.habits_detail_default, format(state.defaultValue)),
                style = MaterialTheme.typography.bodyMedium,
                color = SleepSaverTheme.colors.textTertiary,
                modifier = Modifier.weight(1f),
            )
            TextLinkButton(
                text = stringResource(R.string.habits_detail_reset),
                onClick = onResetTiming,
                enabled = !state.isDefaultTiming,
                modifier = Modifier.padding(start = Spacing.s),
            )
        }
    }
}

/** Formats a stepper value: "10h", "15m", or a shift such as "30m earlier" / "On time". */
@Composable
internal fun timingFormatter(kind: TimingKind): (Int) -> String {
    val resources = LocalResources.current
    return when (kind) {
        TimingKind.BEFORE_BEDTIME, TimingKind.AFTER_WAKE -> { value -> formatDuration(Duration.ofMinutes(value.toLong())) }
        TimingKind.SHIFT -> { value ->
            val amount = formatDuration(Duration.ofMinutes(abs(value).toLong()))
            when {
                value == 0 -> resources.getString(R.string.habits_shift_none)
                value < 0 -> resources.getString(R.string.habits_shift_earlier, amount)
                else -> resources.getString(R.string.habits_shift_later, amount)
            }
        }
    }
}

@Composable
private fun timingCaption(kind: TimingKind, anchor: HabitAnchor): String = stringResource(
    when (kind) {
        TimingKind.BEFORE_BEDTIME -> R.string.habits_timing_before_bedtime
        TimingKind.AFTER_WAKE -> R.string.habits_timing_after_wake
        TimingKind.SHIFT -> when (anchor) {
            HabitAnchor.MELATONIN_START -> R.string.habits_timing_shift_sleep_window
            HabitAnchor.AFTERNOON_DIP_START -> R.string.habits_timing_shift_dip
            else -> R.string.habits_timing_shift_wind_down
        }
    },
)

/** "10h before your 10:45 PM target bedtime"; null when the anchor isn't in today's schedule. */
@Composable
private fun anchorText(state: HabitDetailUiState.Ready): String? {
    val anchorTime = state.anchorTime ?: return null
    val zone = state.zone ?: return null
    val at = clockTime(anchorTime, zone)
    val minutes = state.offset.toMinutes()
    val amount = formatDuration(state.offset.abs())
    val (before, after, exactly) = when (state.anchor) {
        HabitAnchor.TARGET_BEDTIME -> Triple(
            R.string.habits_anchor_bedtime_before, R.string.habits_anchor_bedtime_after, R.string.habits_anchor_bedtime_at,
        )
        HabitAnchor.TYPICAL_WAKE -> Triple(
            R.string.habits_anchor_wake_before, R.string.habits_anchor_wake_after, R.string.habits_anchor_wake_at,
        )
        HabitAnchor.WIND_DOWN_START -> Triple(
            R.string.habits_anchor_wind_down_before, R.string.habits_anchor_wind_down_after, R.string.habits_anchor_wind_down_at,
        )
        HabitAnchor.MELATONIN_START -> Triple(
            R.string.habits_anchor_sleep_window_before, R.string.habits_anchor_sleep_window_after, R.string.habits_anchor_sleep_window_at,
        )
        HabitAnchor.AFTERNOON_DIP_START -> Triple(
            R.string.habits_anchor_dip_before, R.string.habits_anchor_dip_after, R.string.habits_anchor_dip_at,
        )
    }
    return when {
        minutes < 0 -> stringResource(before, amount, at)
        minutes > 0 -> stringResource(after, amount, at)
        else -> stringResource(exactly, at)
    }
}
