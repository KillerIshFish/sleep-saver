package com.sleepsaver.feature.tonight.week

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlarmOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.patterns.KeelResult
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.OverrideKind
import com.sleepsaver.core.domain.schedule.WeekPreset
import com.sleepsaver.core.ui.components.EmptyState
import com.sleepsaver.core.ui.components.LoadingOrb
import com.sleepsaver.core.ui.components.LocalTimeScrubber
import com.sleepsaver.core.ui.components.NoticeCard
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.ScreenHeader
import com.sleepsaver.core.ui.components.SecondaryButton
import com.sleepsaver.core.ui.components.SectionCard
import com.sleepsaver.core.ui.components.SelectableChip
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.SleepSaverSwitch
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.WellnessDisclaimer
import com.sleepsaver.core.ui.components.label
import com.sleepsaver.core.ui.components.title
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.formatDayLong
import com.sleepsaver.core.ui.format.formatDayShort
import com.sleepsaver.core.ui.format.formatShortDate
import com.sleepsaver.core.ui.format.keepTimesTogether
import com.sleepsaver.core.ui.platform.AlarmBlocker
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.tonight.ArmBlockerNotice
import com.sleepsaver.feature.tonight.ArmSource
import com.sleepsaver.feature.tonight.BackTopBar
import com.sleepsaver.feature.tonight.R
import com.sleepsaver.feature.tonight.TestRingMessages
import com.sleepsaver.feature.tonight.TonightSnackbarHost
import com.sleepsaver.feature.tonight.dayAndTime
import com.sleepsaver.feature.tonight.formatHoursSpaced
import com.sleepsaver.feature.tonight.navigation.WeekPlanNavigation
import com.sleepsaver.feature.tonight.nearDay
import com.sleepsaver.feature.tonight.oneoff.OneOffSheet
import com.sleepsaver.feature.tonight.rememberAlarmGate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** Every callback of the week plan; all default to doing nothing. */
@Stable
internal class WeekPlanActions(
    val onBack: () -> Unit = {},
    val onOpenDay: (DayOfWeek) -> Unit = {},
    val onAlarmSwitch: (DayOfWeek, Boolean) -> Unit = { _, _ -> },
    val onChangeOneDay: () -> Unit = {},
    val onUndoOverride: (LocalDate) -> Unit = {},
    val onResumeAlarms: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    val onApplyPreset: (WeekPreset, LocalTime) -> Unit = { _, _ -> },
    val onOpenAlarmCheck: () -> Unit = {},
    val onTestRing: () -> Unit = {},
    val onFixBlocker: (AlarmBlocker) -> Unit = {},
)

@Composable
internal fun WeekPlanScreen(
    navigation: WeekPlanNavigation,
    modifier: Modifier = Modifier,
    viewModel: WeekPlanViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val oneOffContext by viewModel.oneOffContext.collectAsStateWithLifecycle()
    val gate = rememberAlarmGate()
    val nav by rememberUpdatedState(navigation)
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    TestRingMessages(viewModel.events, snackbar)

    val actions = remember(viewModel, gate) {
        WeekPlanActions(
            onBack = { nav.onBack() },
            onOpenDay = { nav.onOpenDayPlan(it) },
            onAlarmSwitch = { day, on -> gate.armIf(on, ArmSource.ALARM) { viewModel.onAlarmSwitch(day, on) } },
            onChangeOneDay = { sheetOpen = true },
            // An Undo that brings an alarm back (a Day off) is arming; one that removes a one-off alarm isn't.
            onUndoOverride = { date -> gate.armIf(viewModel.undoArms(date), ArmSource.ALARM) { viewModel.onClearOverride(date) } },
            onResumeAlarms = { gate.arm(ArmSource.ALARM, viewModel::onResumeAlarms) },
            onDismissNotice = viewModel::onDismissNotice,
            onApplyPreset = { preset, wake -> gate.arm(ArmSource.ALARM) { viewModel.onApplyPreset(preset, wake) } },
            onOpenAlarmCheck = { nav.onOpenAlarmCheck() },
            onTestRing = { gate.arm(ArmSource.TEST_RING, viewModel::onTestRing) },
            onFixBlocker = gate::fix,
        )
    }
    WeekPlanContent(
        state,
        actions,
        modifier,
        armBlocker = gate.blocker(ArmSource.ALARM),
        testRingBlocker = gate.blocker(ArmSource.TEST_RING),
        snackbarHostState = snackbar,
    )

    val context = oneOffContext
    if (sheetOpen && context != null) {
        OneOffSheet(
            context = context,
            date = context.today.plusDays(1),
            chooseDate = true,
            gate = gate,
            onDayOff = viewModel::onDayOff,
            onJustOnce = viewModel::onJustOnce,
            onPause = viewModel::onPause,
            onDismiss = { sheetOpen = false },
        )
    }
}

/** The week plan for a given [state]; stateless apart from the quick-setup choice. */
@Composable
internal fun WeekPlanContent(
    state: WeekPlanUiState,
    actions: WeekPlanActions,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    armBlocker: AlarmBlocker? = null,
    testRingBlocker: AlarmBlocker? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Box(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        when (state) {
            WeekPlanUiState.Loading -> Column(Modifier.fillMaxSize()) {
                BackTopBar(stringResource(R.string.tonight_week_plan_title), actions.onBack)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingOrb() }
            }
            WeekPlanUiState.Unavailable -> Column(Modifier.fillMaxSize()) {
                BackTopBar(stringResource(R.string.tonight_week_plan_title), actions.onBack)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Rounded.ErrorOutline,
                        title = stringResource(R.string.tonight_unavailable_title),
                        body = stringResource(R.string.tonight_unavailable_body),
                    )
                }
            }
            is WeekPlanUiState.Ready -> ReadyWeekPlan(state, actions, scrollState, armBlocker, testRingBlocker)
        }
        TonightSnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

private val ScreenPadding = Modifier.padding(horizontal = Spacing.screenHorizontal)

@Composable
private fun ReadyWeekPlan(
    state: WeekPlanUiState.Ready,
    actions: WeekPlanActions,
    scrollState: ScrollState,
    armBlocker: AlarmBlocker?,
    testRingBlocker: AlarmBlocker?,
) {
    val card = ScreenPadding.fillMaxWidth()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(bottom = Spacing.xl),
    ) {
        BackTopBar(title = null, onBack = actions.onBack)
        ScreenHeader(
            title = stringResource(R.string.tonight_week_plan_title),
            subtitle = subtitle(state),
            modifier = ScreenPadding,
        )

        state.oneOffs.pause?.let { pause ->
            Spacer(Modifier.height(Spacing.l))
            NoticeCard(
                title = stringResource(R.string.tonight_week_paused, nearDay(pause.until, state.today)),
                body = stringResource(R.string.tonight_week_paused_body),
                icon = Icons.Rounded.AlarmOff,
                actionLabel = stringResource(R.string.tonight_action_resume_short),
                onAction = actions.onResumeAlarms,
                modifier = card,
            )
        }

        armBlocker?.let {
            Spacer(Modifier.height(Spacing.l))
            ArmBlockerNotice(it, actions.onFixBlocker, card)
        }

        Spacer(Modifier.height(Spacing.l))
        SleepSaverCard(card, contentPadding = PaddingValues(vertical = Spacing.s)) {
            state.rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                DayRow(row, state, actions)
            }
        }

        Spacer(Modifier.height(Spacing.cardGap))
        OneOffsCard(state, actions, card)

        state.planCheck?.let { check ->
            check.plannedKeel?.let { planned ->
                Spacer(Modifier.height(Spacing.cardGap))
                SleepSaverCard(card) {
                    val band = check.plannedBand?.label()
                    val keel = (state.keel as? KeelResult.Ready)?.score
                    val line = buildString {
                        append(stringResource(R.string.tonight_planned_keel, planned))
                        if (band != null) append(" · ").append(band)
                        if (keel != null) append(stringResource(R.string.tonight_planned_keel_last, SleepConfig.DEFAULT.keel.windowDays, keel))
                    }
                    Text(line, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            if (check.warn && !state.noticeDismissed) {
                Spacer(Modifier.height(Spacing.cardGap))
                val shift = check.freeDayWakeShift
                val jetlag = check.plannedSocialJetlag
                NoticeCard(
                    title = stringResource(R.string.tonight_plan_notice_title),
                    body = when {
                        check.warnFreeDayShift && shift != null -> stringResource(R.string.tonight_plan_free_shift, formatHoursSpaced(shift))
                        jetlag != null -> stringResource(R.string.tonight_plan_jetlag, formatHoursSpaced(jetlag.abs()))
                        else -> ""
                    },
                    actionLabel = stringResource(R.string.tonight_plan_got_it),
                    onAction = actions.onDismissNotice,
                    modifier = card,
                )
            }
        }

        Spacer(Modifier.height(Spacing.cardGap))
        QuickSetup(state, actions, card)

        Spacer(Modifier.height(Spacing.xl))
        Column(ScreenPadding, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            testRingBlocker?.let { ArmBlockerNotice(it, actions.onFixBlocker, Modifier.fillMaxWidth()) }
            SecondaryButton(stringResource(R.string.tonight_alarm_check), onClick = actions.onOpenAlarmCheck, modifier = Modifier.fillMaxWidth())
            TextLinkButton(stringResource(R.string.tonight_test_ring), onClick = actions.onTestRing, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        Spacer(Modifier.height(Spacing.xxl))
        WellnessDisclaimer(ScreenPadding)
    }
}

/** "Next: Thu 6:40 AM · window 30 min" (fixed: no window); at night without "Next". */
@Composable
private fun subtitle(state: WeekPlanUiState.Ready): String {
    val next = state.next ?: return stringResource(R.string.tonight_week_no_alarms)
    val occurrence = next.occurrence
    val alarm = dayAndTime(occurrence.date, occurrence.localTime, state.today)
    val window = next.window.length.toMinutes().toInt()
    val surfacing = occurrence.settings.mode == AlarmMode.SURFACING
    return when {
        state.night && surfacing -> stringResource(R.string.tonight_week_alarm_window, alarm, window)
        state.night -> alarm
        surfacing -> stringResource(R.string.tonight_week_next_window, alarm, window)
        else -> stringResource(R.string.tonight_week_next, alarm)
    }.keepTimesTogether()
}

@Composable
private fun DayRow(row: WeekDayRow, state: WeekPlanUiState.Ready, actions: WeekPlanActions) {
    val alarm = row.plan.morning.alarm
    val wake = row.plan.morning.wakeTime
    val wakeText = wake?.let { clockTime(it) }
        ?: row.usualWake?.let { stringResource(R.string.tonight_row_usual, clockTime(it)) }
    val alarmText = when (alarm.mode) {
        AlarmMode.SURFACING -> stringResource(R.string.tonight_row_surfacing, alarm.window.toMinutes().toInt())
        AlarmMode.FIXED -> alarm.mode.title()
        AlarmMode.OFF -> stringResource(R.string.tonight_row_no_alarm)
    }
    val morning = listOfNotNull(formatDayShort(row.day), wakeText, alarmText).joinToString(" · ").keepTimesTogether()

    val evening = row.plan.evening
    val parts = mutableListOf(stringResource(R.string.tonight_row_evening))
    when {
        !evening.nightStepsOn -> parts += stringResource(R.string.tonight_row_steps_off)
        row.stepsFrom != null -> {
            parts += stringResource(R.string.tonight_row_steps_from, clockTime(row.stepsFrom, state.zone))
            parts += pluralStringResource(R.plurals.tonight_row_steps, row.steps, row.steps)
        }
        else -> {
            parts += stringResource(R.string.tonight_row_steps_on)
            parts += pluralStringResource(R.plurals.tonight_row_steps, row.steps, row.steps)
        }
    }
    evening.bedtime?.let { parts += stringResource(R.string.tonight_row_bedtime, clockTime(it)) }
    val eveningText = parts.joinToString(" · ").keepTimesTogether()
    val switchLabel = stringResource(R.string.tonight_row_switch, formatDayLong(row.day))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = { actions.onOpenDay(row.day) })
            .heightIn(min = 64.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Spacing.m)) {
            Text(
                morning,
                style = MaterialTheme.typography.titleSmall,
                color = if (row.alarmOn) MaterialTheme.colorScheme.onSurface else SleepSaverTheme.colors.textSecondary,
            )
            Text(
                eveningText,
                style = MaterialTheme.typography.bodySmall,
                color = SleepSaverTheme.colors.textSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        SleepSaverSwitch(
            checked = row.alarmOn,
            onCheckedChange = { actions.onAlarmSwitch(row.day, it) },
            modifier = Modifier.semantics { contentDescription = switchLabel },
        )
    }
}

@Composable
private fun OneOffsCard(state: WeekPlanUiState.Ready, actions: WeekPlanActions, modifier: Modifier) {
    val overrides = state.oneOffs.overrides.filter { !it.date.isBefore(state.today) }.sortedBy { it.date }
    val pause = state.oneOffs.pause
    SectionCard(
        stringResource(R.string.tonight_oneoffs_title),
        modifier,
        actionLabel = stringResource(R.string.tonight_oneoff_change),
        onAction = actions.onChangeOneDay,
    ) {
        if (overrides.isEmpty() && pause == null) {
            Text(
                stringResource(R.string.tonight_oneoffs_none),
                style = MaterialTheme.typography.bodyMedium,
                color = SleepSaverTheme.colors.textSecondary,
            )
        }
        overrides.forEach { override ->
            val date = formatShortDate(override.date, state.today)
            val text = when {
                override.kind == OverrideKind.DAY_OFF -> stringResource(R.string.tonight_oneoff_day_off, date)
                override.mode == AlarmMode.OFF ->
                    stringResource(R.string.tonight_oneoff_just_once_no_alarm, date, override.time?.let { clockTime(it) }.orEmpty())
                else -> stringResource(R.string.tonight_oneoff_just_once, date, override.time?.let { clockTime(it) }.orEmpty())
            }
            OneOffRow(text, onUndo = { actions.onUndoOverride(override.date) })
        }
        pause?.let {
            OneOffRow(
                stringResource(R.string.tonight_oneoff_paused, formatShortDate(it.until, state.today)),
                onUndo = actions.onResumeAlarms,
            )
        }
    }
}

@Composable
private fun OneOffRow(text: String, onUndo: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.keepTimesTogether(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextLinkButton(stringResource(R.string.tonight_oneoff_undo), onClick = onUndo, modifier = Modifier.offset(x = 12.dp))
    }
}

@Composable
private fun QuickSetup(state: WeekPlanUiState.Ready, actions: WeekPlanActions, modifier: Modifier) {
    var preset by rememberSaveable { mutableStateOf(WeekPreset.WORK_WEEK) }
    var wake by rememberSaveable(state.presetWake) { mutableStateOf(state.presetWake.toString()) }
    SectionCard(stringResource(R.string.tonight_quick_title), modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            WeekPreset.entries.forEach { option ->
                SelectableChip(
                    text = stringResource(
                        when (option) {
                            WeekPreset.WORK_WEEK -> R.string.tonight_preset_work
                            WeekPreset.SAME_EVERY_DAY -> R.string.tonight_preset_same
                        },
                    ),
                    selected = option == preset,
                    onClick = { preset = option },
                )
            }
        }
        LocalTimeScrubber(
            value = LocalTime.parse(wake),
            onValueChange = { wake = it.toString() },
            label = stringResource(R.string.tonight_quick_wake),
        )
        PrimaryButton(
            stringResource(R.string.tonight_quick_apply),
            onClick = { actions.onApplyPreset(preset, LocalTime.parse(wake)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
