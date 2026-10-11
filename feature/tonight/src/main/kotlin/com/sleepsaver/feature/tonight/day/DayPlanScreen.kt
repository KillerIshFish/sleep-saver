package com.sleepsaver.feature.tonight.day

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.TimingSource
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.AlarmTone
import com.sleepsaver.core.domain.schedule.DayPart
import com.sleepsaver.core.domain.schedule.SnoozeStyle
import com.sleepsaver.core.domain.schedule.WakeStyle
import com.sleepsaver.core.ui.components.DayTabs
import com.sleepsaver.core.ui.components.EmptyState
import com.sleepsaver.core.ui.components.LoadingOrb
import com.sleepsaver.core.ui.components.LocalTimeScrubber
import com.sleepsaver.core.ui.components.MinuteStepper
import com.sleepsaver.core.ui.components.SecondaryButton
import com.sleepsaver.core.ui.components.SegmentedControl
import com.sleepsaver.core.ui.components.SelectableChip
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.ToggleRow
import com.sleepsaver.core.ui.components.description
import com.sleepsaver.core.ui.components.title
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.formatDayLong
import com.sleepsaver.core.ui.format.keepTimesTogether
import com.sleepsaver.core.ui.platform.AlarmBlocker
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.tonight.ArmBlockerNotice
import com.sleepsaver.feature.tonight.BackTopBar
import com.sleepsaver.feature.tonight.DebtPreviewChip
import com.sleepsaver.feature.tonight.GroupLabel
import com.sleepsaver.feature.tonight.R
import com.sleepsaver.feature.tonight.formatHoursSpaced
import com.sleepsaver.feature.tonight.navigation.DayPlanNavigation
import com.sleepsaver.feature.tonight.rememberAlarmGate
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import kotlin.math.roundToInt

/** Every callback of the day plan; all default to doing nothing. */
@Stable
internal class DayPlanActions(
    val onBack: () -> Unit = {},
    val onSelectDay: (DayOfWeek) -> Unit = {},
    val onWakeUsual: () -> Unit = {},
    val onWakeSet: () -> Unit = {},
    val onWakeTime: (LocalTime) -> Unit = {},
    val onAlarmMode: (AlarmMode) -> Unit = {},
    val onWindow: (Duration) -> Unit = {},
    val onStyle: (WakeStyle) -> Unit = {},
    val onTone: (AlarmTone) -> Unit = {},
    val onPreviewTone: (AlarmTone) -> Unit = {},
    val onVolume: (Float) -> Unit = {},
    val onVolumeDone: () -> Unit = {},
    val onSnoozeStyle: (SnoozeStyle) -> Unit = {},
    val onFixedLength: (Duration) -> Unit = {},
    val onFixedCount: (Int) -> Unit = {},
    val onOverrun: (Duration) -> Unit = {},
    val onBedtimeRule: () -> Unit = {},
    val onBedtimeSet: () -> Unit = {},
    val onBedtime: (LocalTime) -> Unit = {},
    val onNightSteps: (Boolean) -> Unit = {},
    /** Minutes before bedtime the Night steps start. */
    val onStepsStart: (Int) -> Unit = {},
    val onEditSteps: (DayOfWeek) -> Unit = {},
    val onCopy: (Set<DayOfWeek>, Set<DayPart>) -> Unit = { _, _ -> },
    val onFixBlocker: (AlarmBlocker) -> Unit = {},
)

@Composable
internal fun DayPlanScreen(
    navigation: DayPlanNavigation,
    modifier: Modifier = Modifier,
    viewModel: DayPlanViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nav by rememberUpdatedState(navigation)
    val gate = rememberAlarmGate()
    DisposableEffect(viewModel) { onDispose { viewModel.stopPreview() } }
    val actions = remember(viewModel, gate) {
        DayPlanActions(
            onBack = { nav.onBack() },
            onSelectDay = viewModel::onSelectDay,
            onWakeUsual = viewModel::onWakeUsual,
            onWakeSet = viewModel::onWakeSet,
            onWakeTime = viewModel::onWakeTime,
            onAlarmMode = { mode -> gate.armIf(mode != AlarmMode.OFF) { viewModel.onAlarmMode(mode) } },
            onWindow = viewModel::onWindow,
            onStyle = viewModel::onStyle,
            onTone = viewModel::onTone,
            onPreviewTone = viewModel::onPreviewTone,
            onVolume = viewModel::onVolume,
            onVolumeDone = viewModel::onVolumeDone,
            onSnoozeStyle = viewModel::onSnoozeStyle,
            onFixedLength = viewModel::onFixedLength,
            onFixedCount = viewModel::onFixedCount,
            onOverrun = viewModel::onOverrun,
            onBedtimeRule = viewModel::onBedtimeRule,
            onBedtimeSet = viewModel::onBedtimeSet,
            onBedtime = viewModel::onBedtime,
            onNightSteps = viewModel::onNightSteps,
            onStepsStart = viewModel::onStepsStart,
            onEditSteps = { nav.onOpenRoutineEditor(it) },
            onCopy = viewModel::onCopy,
            onFixBlocker = gate::fix,
        )
    }
    DayPlanContent(state, actions, modifier, armBlocker = gate.blocker)
}

/**
 * A day's plan for a given [state]: the Morning (wake time, alarm, window, wake-up style, sound, volume, snooze) and
 * the Evening ("Tuesday evening (before Wednesday)": bedtime, Night steps). Opens scrolled to the evening when the
 * route asks for it. [armBlocker] is why the last alarm switch-on didn't apply.
 */
@Composable
internal fun DayPlanContent(
    state: DayPlanUiState,
    actions: DayPlanActions,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    armBlocker: AlarmBlocker? = null,
    initialCopyOpen: Boolean = false,
) {
    var copyOpen by rememberSaveable { mutableStateOf(initialCopyOpen) }
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        BackTopBar(stringResource(R.string.tonight_day_plan_title), actions.onBack)
        when (state) {
            DayPlanUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingOrb() }
            DayPlanUiState.Unavailable -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Rounded.CloudOff,
                    title = stringResource(R.string.tonight_unavailable_title),
                    body = stringResource(R.string.tonight_unavailable_body),
                )
            }
            is DayPlanUiState.Ready -> ReadyDayPlan(state, actions, scrollState, armBlocker, onCopy = { copyOpen = true })
        }
    }
    if (copyOpen && state is DayPlanUiState.Ready) {
        CopyDialog(
            day = state.day,
            onCopy = { days, parts ->
                copyOpen = false
                actions.onCopy(days, parts)
            },
            onDismiss = { copyOpen = false },
        )
    }
}

private val ScreenPadding = Modifier.padding(horizontal = Spacing.screenHorizontal)

@Composable
private fun ReadyDayPlan(
    state: DayPlanUiState.Ready,
    actions: DayPlanActions,
    scrollState: ScrollState,
    armBlocker: AlarmBlocker?,
    onCopy: () -> Unit,
) {
    // Scroll to the evening once, when the route asked for it (rotation doesn't scroll again).
    var scrolledToPart by rememberSaveable { mutableStateOf(state.initialPart != DayPart.EVENING) }
    var eveningTop by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(eveningTop) {
        val top = eveningTop
        if (!scrolledToPart && top != null) {
            scrollState.scrollTo(top)
            scrolledToPart = true
        }
    }
    val card = ScreenPadding.fillMaxWidth()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(bottom = Spacing.xl),
    ) {
        DayTabs(
            selected = state.day,
            onSelect = actions.onSelectDay,
            marked = state.alarmDays,
            modifier = ScreenPadding.padding(top = Spacing.s),
        )

        // ---------------------------------------------------------------- Morning
        PartTitle(stringResource(R.string.tonight_morning_title, formatDayLong(state.day)), ScreenPadding.padding(top = Spacing.xl))
        Spacer(Modifier.height(Spacing.m))
        WakeCard(state, actions, card)
        state.morningPreview?.let {
            Spacer(Modifier.height(Spacing.m))
            DebtPreviewChip(it, state.today, state.zone, card)
        }
        Spacer(Modifier.height(Spacing.xl))
        AlarmControls(state, actions, armBlocker)

        // ---------------------------------------------------------------- Evening
        val next = formatDayLong(state.day.plus(1))
        PartTitle(
            stringResource(R.string.tonight_evening_title, formatDayLong(state.day), next),
            ScreenPadding
                .padding(top = Spacing.xxl)
                .onGloballyPositioned { eveningTop = it.positionInParent().y.roundToInt() },
        )
        Spacer(Modifier.height(Spacing.m))
        BedtimeCard(state, actions, card)
        state.eveningPreview?.let {
            Spacer(Modifier.height(Spacing.m))
            DebtPreviewChip(it, state.today, state.zone, card)
        }
        Spacer(Modifier.height(Spacing.cardGap))
        NightStepsCard(state, actions, card)

        Spacer(Modifier.height(Spacing.xl))
        SecondaryButton(
            stringResource(R.string.tonight_copy),
            onClick = onCopy,
            modifier = card,
            icon = Icons.Rounded.ContentCopy,
        )
    }
}

@Composable
private fun PartTitle(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.semantics { heading() },
    )
}

// ---------------------------------------------------------------- Morning

@Composable
private fun WakeCard(state: DayPlanUiState.Ready, actions: DayPlanActions, modifier: Modifier) {
    val wake = state.plan.morning.wakeTime
    SleepSaverCard(modifier) {
        CardTitle(stringResource(R.string.tonight_wake_title))
        Column(Modifier.selectableGroup()) {
            val usual = state.usualWake?.let { time ->
                when (state.usualSource) {
                    TimingSource.LEARNED -> stringResource(R.string.tonight_wake_learned, clockTime(time))
                    null -> clockTime(time)
                    else -> stringResource(R.string.tonight_wake_setting, clockTime(time))
                }.keepTimesTogether()
            }
            RadioRow(
                title = stringResource(R.string.tonight_wake_usual),
                supporting = usual,
                selected = wake == null,
                enabled = !state.alarmOn,
                onClick = actions.onWakeUsual,
            )
            RadioRow(
                title = stringResource(R.string.tonight_wake_set),
                selected = wake != null,
                onClick = actions.onWakeSet,
            )
        }
        if (wake != null) LocalTimeScrubber(value = wake, onValueChange = actions.onWakeTime)
        if (state.alarmOn) Caption(stringResource(R.string.tonight_wake_alarm_caption))
    }
}

@Composable
private fun AlarmControls(state: DayPlanUiState.Ready, actions: DayPlanActions, armBlocker: AlarmBlocker?) {
    val config = SleepConfig.DEFAULT
    val alarm = state.plan.morning.alarm
    val modes = listOf(AlarmMode.OFF, AlarmMode.FIXED, AlarmMode.SURFACING)
    val card = ScreenPadding.fillMaxWidth()
    GroupLabel(stringResource(R.string.tonight_alarm_label), ScreenPadding)
    Spacer(Modifier.height(Spacing.s))
    SegmentedControl(
        options = modes.map { it.title() },
        selectedIndex = modes.indexOf(alarm.mode),
        onSelectedChange = { actions.onAlarmMode(modes[it]) },
        modifier = card,
    )
    alarm.mode.description()?.let { Caption(it, ScreenPadding.padding(top = Spacing.s)) }
    armBlocker?.let {
        Spacer(Modifier.height(Spacing.m))
        ArmBlockerNotice(it, actions.onFixBlocker, card)
    }
    if (alarm.mode == AlarmMode.OFF) return
    val wake = state.shownWake

    if (alarm.mode == AlarmMode.SURFACING) {
        Spacer(Modifier.height(Spacing.cardGap))
        SleepSaverCard(card) {
            CardTitle(stringResource(R.string.tonight_window_title))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                generateSequence(config.alarm.windowMin) { it + config.alarm.windowStep }
                    .takeWhile { it <= config.alarm.windowMax }
                    .forEach { window ->
                        SelectableChip(
                            text = stringResource(R.string.tonight_minutes, window.toMinutes().toInt()),
                            selected = window == alarm.window,
                            onClick = { actions.onWindow(window) },
                        )
                    }
            }
            if (wake != null) {
                Caption(stringResource(R.string.tonight_window_caption, alarm.window.toMinutes().toInt(), clockTime(wake)).keepTimesTogether())
            }
        }
    }

    Spacer(Modifier.height(Spacing.cardGap))
    SleepSaverCard(card) {
        CardTitle(stringResource(R.string.tonight_style_title))
        Column(Modifier.selectableGroup()) {
            WakeStyle.entries.forEach { style ->
                RadioRow(style.title(), supporting = style.description(), selected = style == alarm.style, onClick = { actions.onStyle(style) })
            }
        }
    }

    Spacer(Modifier.height(Spacing.cardGap))
    SleepSaverCard(card) {
        CardTitle(stringResource(R.string.tonight_sound_title))
        Column(Modifier.selectableGroup()) {
            AlarmTone.entries.forEach { tone ->
                val title = tone.title()
                val previewLabel = stringResource(R.string.tonight_sound_preview, title)
                RadioRow(title, selected = tone == alarm.tone, onClick = { actions.onTone(tone) }) {
                    IconButton(
                        onClick = { actions.onPreviewTone(tone) },
                        modifier = Modifier.semantics { contentDescription = previewLabel },
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        Row(Modifier.padding(top = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            CardTitle(stringResource(R.string.tonight_volume_title), Modifier.weight(1f))
            Text(
                stringResource(R.string.tonight_percent, (alarm.volume * 100).roundToInt()),
                style = MaterialTheme.typography.labelLarge,
                color = SleepSaverTheme.colors.textSecondary,
            )
        }
        val volumeLabel = stringResource(R.string.tonight_volume_title)
        Slider(
            value = alarm.volume.coerceIn(config.alarm.minVolume, 1f),
            onValueChange = actions.onVolume,
            onValueChangeFinished = actions.onVolumeDone,
            valueRange = config.alarm.minVolume..1f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = SleepSaverTheme.colors.cardRaised,
            ),
            modifier = Modifier.semantics { contentDescription = volumeLabel },
        )
        Caption(stringResource(R.string.tonight_volume_caption))
    }

    Spacer(Modifier.height(Spacing.xl))
    val snoozeStyles = listOf(SnoozeStyle.TAPERING, SnoozeStyle.FIXED, SnoozeStyle.NONE)
    GroupLabel(stringResource(R.string.tonight_snooze_title), ScreenPadding)
    Spacer(Modifier.height(Spacing.s))
    SegmentedControl(
        options = snoozeStyles.map { it.title() },
        selectedIndex = snoozeStyles.indexOf(alarm.snooze.style),
        onSelectedChange = { actions.onSnoozeStyle(snoozeStyles[it]) },
        modifier = card,
    )
    val snooze = alarm.snooze
    if (snooze.style == SnoozeStyle.NONE) return
    Spacer(Modifier.height(Spacing.cardGap))
    val minuteLabel = stringResource(R.string.tonight_minutes_format)
    SleepSaverCard(card) {
        snooze.style.description()?.let { Caption(it) }
        if (snooze.style == SnoozeStyle.FIXED) {
            CardTitle(stringResource(R.string.tonight_snooze_length))
            MinuteStepper(
                minutes = snooze.fixedLength.toMinutes().toInt(),
                onMinutesChange = { actions.onFixedLength(Duration.ofMinutes(it.toLong())) },
                step = config.alarm.fixedSnoozeMin.toMinutes().toInt(),
                min = config.alarm.fixedSnoozeMin.toMinutes().toInt(),
                max = config.alarm.fixedSnoozeMax.toMinutes().toInt(),
                format = { minuteLabel.format(it) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            CardTitle(stringResource(R.string.tonight_snooze_count))
            val countLabels = (config.alarm.fixedSnoozeCountMin..config.alarm.fixedSnoozeCountMax)
                .associateWith { pluralStringResource(R.plurals.tonight_snoozes, it, it) }
            MinuteStepper(
                minutes = snooze.fixedCount,
                onMinutesChange = actions.onFixedCount,
                step = 1,
                min = config.alarm.fixedSnoozeCountMin,
                max = config.alarm.fixedSnoozeCountMax,
                format = { countLabels[it] ?: it.toString() },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        CardTitle(stringResource(R.string.tonight_overrun_title))
        MinuteStepper(
            minutes = snooze.overrun.toMinutes().toInt(),
            onMinutesChange = { actions.onOverrun(Duration.ofMinutes(it.toLong())) },
            step = config.alarm.overrunStep.toMinutes().toInt(),
            min = 0,
            max = config.alarm.overrunMax.toMinutes().toInt(),
            format = { minuteLabel.format(it) },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Caption(
            if (snooze.overrun.isZero) {
                stringResource(R.string.tonight_overrun_zero)
            } else {
                stringResource(R.string.tonight_overrun_caption, minuteLabel.format(snooze.overrun.toMinutes().toInt()))
            },
        )
    }
}

// ---------------------------------------------------------------- Evening

@Composable
private fun BedtimeCard(state: DayPlanUiState.Ready, actions: DayPlanActions, modifier: Modifier) {
    val bedtime = state.plan.evening.bedtime
    SleepSaverCard(modifier) {
        CardTitle(stringResource(R.string.tonight_bedtime_title))
        Column(Modifier.selectableGroup()) {
            RadioRow(
                title = stringResource(R.string.tonight_bedtime_rule),
                supporting = state.ruleBedtime?.let { clockTime(it) },
                selected = bedtime == null,
                onClick = actions.onBedtimeRule,
            )
            RadioRow(title = stringResource(R.string.tonight_bedtime_set), selected = bedtime != null, onClick = actions.onBedtimeSet)
        }
        if (bedtime != null) LocalTimeScrubber(value = bedtime, onValueChange = actions.onBedtime, overnight = true)
    }
}

@Composable
private fun NightStepsCard(state: DayPlanUiState.Ready, actions: DayPlanActions, modifier: Modifier) {
    val config = SleepConfig.DEFAULT
    val evening = state.plan.evening
    SleepSaverCard(modifier) {
        ToggleRow(
            title = stringResource(R.string.tonight_steps_toggle),
            checked = evening.nightStepsOn,
            onCheckedChange = actions.onNightSteps,
        )
        if (!evening.nightStepsOn) return@SleepSaverCard
        val before = (evening.windDownLength + config.melatoninHalfWidth).toMinutes().toInt()
        CardTitle(stringResource(R.string.tonight_steps_start))
        MinuteStepper(
            minutes = before,
            onMinutesChange = actions.onStepsStart,
            step = config.week.windDownStep.toMinutes().toInt(),
            min = (config.week.windDownMinLength + config.melatoninHalfWidth).toMinutes().toInt(),
            max = (config.week.windDownMaxLength + config.melatoninHalfWidth).toMinutes().toInt(),
            format = { formatHoursSpaced(Duration.ofMinutes(it.toLong())) },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        val from = state.shownBedtime?.minusMinutes(before.toLong())
        Caption(
            listOfNotNull(
                stringResource(R.string.tonight_steps_before),
                from?.let { stringResource(R.string.tonight_steps_from, clockTime(it)) },
            ).joinToString(" · ").keepTimesTogether(),
            Modifier.align(Alignment.CenterHorizontally),
        )
        Row(Modifier.fillMaxWidth().padding(top = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(
                    if (evening.routine == null) R.plurals.tonight_steps_every_evening else R.plurals.tonight_steps_own_list,
                    state.stepsCount,
                    state.stepsCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextLinkButton(
                stringResource(R.string.tonight_steps_edit),
                onClick = { actions.onEditSteps(state.day) },
                modifier = Modifier.offset(x = 12.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- Pieces

@Composable
private fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = SleepSaverTheme.colors.textSecondary, modifier = modifier)
}

/** A radio choice: the whole row selects it; an optional [trailing] control (the ▶ preview). */
@Composable
private fun RadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    supporting: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val secondary = SleepSaverTheme.colors.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary, unselectedColor = secondary),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = Spacing.m),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else SleepSaverTheme.colors.textTertiary,
            )
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = secondary)
        }
        trailing?.invoke()
    }
}
