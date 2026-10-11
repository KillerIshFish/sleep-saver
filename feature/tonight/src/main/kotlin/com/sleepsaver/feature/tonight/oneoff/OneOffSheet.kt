package com.sleepsaver.feature.tonight.oneoff

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.ui.components.LocalTimeScrubber
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.SecondaryButton
import com.sleepsaver.core.ui.components.SelectableChip
import com.sleepsaver.core.ui.components.title
import com.sleepsaver.core.ui.format.formatDayShort
import com.sleepsaver.core.ui.format.formatShortDate
import com.sleepsaver.core.ui.platform.AlarmBlocker
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.tonight.AlarmGate
import com.sleepsaver.feature.tonight.ArmBlockerNotice
import com.sleepsaver.feature.tonight.R
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * The one-off sheet as drawn. [date] is the day being changed; [dates] the days it can switch to (from the week plan;
 * empty when opened from the alarm card, which is about one date). [alarmOn] = that date has an alarm, so "Day off"
 * is offered. [time] and [mode] are "Just this once"'s choice; [pauseUntil] the last paused day of [pauseDates].
 * [armBlocker] is why Just this once didn't apply (notifications or the alarm channel).
 */
@Immutable
internal data class OneOffSheetState(
    val date: LocalDate,
    val dates: List<LocalDate>,
    val today: LocalDate,
    val alarmOn: Boolean,
    val time: LocalTime,
    val mode: AlarmMode,
    val pauseDates: List<LocalDate>,
    val pauseUntil: LocalDate,
    val armBlocker: AlarmBlocker? = null,
)

/** Every callback of [OneOffSheetContent]; all default to doing nothing. */
@Stable
internal class OneOffSheetActions(
    val onDate: (LocalDate) -> Unit = {},
    val onDayOff: () -> Unit = {},
    val onTime: (LocalTime) -> Unit = {},
    val onMode: (AlarmMode) -> Unit = {},
    val onJustOnce: () -> Unit = {},
    val onPauseUntil: (LocalDate) -> Unit = {},
    val onPause: () -> Unit = {},
    val onFixBlocker: (AlarmBlocker) -> Unit = {},
)

/**
 * "Change for one day", from the alarm card or the week plan: Day off, Just this once (another time, with or without
 * an alarm) or "Pause alarms until…". Each is a one-off row; the week plan stays as it is. Applying closes the sheet.
 * Just this once with an alarm goes through [gate].
 */
@Composable
internal fun OneOffSheet(
    context: OneOffContext,
    date: LocalDate,
    chooseDate: Boolean,
    gate: AlarmGate,
    onDayOff: (LocalDate) -> Unit,
    onJustOnce: (LocalDate, LocalTime, AlarmMode) -> Unit,
    onPause: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    config: SleepConfig = SleepConfig.DEFAULT,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var picked by rememberSaveable { mutableStateOf(date.toString()) }
    val day = LocalDate.parse(picked)
    var time by rememberSaveable(picked) { mutableStateOf(context.startTime(day, config).toString()) }
    var mode by rememberSaveable(picked) { mutableStateOf(context.startMode(day, config)) }
    val pauseDates = (1L..config.week.pauseMaxDays).map { context.today.plusDays(it) }
    var pauseUntil by rememberSaveable { mutableStateOf((context.oneOffs.pause?.until ?: pauseDates.first()).toString()) }

    fun close(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            then()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        OneOffSheetContent(
            state = OneOffSheetState(
                date = day,
                dates = if (chooseDate) (0L until config.week.alarmLookaheadDays).map { context.today.plusDays(it) } else emptyList(),
                today = context.today,
                alarmOn = context.resolve(day).alarmOn,
                time = LocalTime.parse(time),
                mode = mode,
                pauseDates = pauseDates,
                pauseUntil = LocalDate.parse(pauseUntil),
                armBlocker = gate.blocker,
            ),
            actions = OneOffSheetActions(
                onDate = { picked = it.toString() },
                onDayOff = { close { onDayOff(day) } },
                onTime = { time = it.toString() },
                onMode = { mode = it },
                onJustOnce = {
                    val chosenTime = LocalTime.parse(time)
                    val chosenMode = mode
                    gate.armIf(chosenMode != AlarmMode.OFF) { close { onJustOnce(day, chosenTime, chosenMode) } }
                },
                onPauseUntil = { pauseUntil = it.toString() },
                onPause = { close { onPause(LocalDate.parse(pauseUntil)) } },
                onFixBlocker = gate::fix,
            ),
        )
    }
}

/** The sheet's body, separate so it can be screenshot-tested. */
@Composable
internal fun OneOffSheetContent(state: OneOffSheetState, actions: OneOffSheetActions, modifier: Modifier = Modifier) {
    val dayName = formatDayShort(state.date.dayOfWeek)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.xl),
    ) {
        Text(
            stringResource(R.string.tonight_oneoff_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        if (state.dates.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.m))
            Row(
                modifier = Modifier.chipRow(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                state.dates.forEach { date ->
                    SelectableChip(
                        text = formatShortDate(date, state.today),
                        selected = date == state.date,
                        onClick = { actions.onDate(date) },
                    )
                }
            }
        }

        if (state.alarmOn) {
            SectionTitle(stringResource(R.string.tonight_oneoff_day_off_title))
            Body(stringResource(R.string.tonight_oneoff_day_off_body, dayName))
            Spacer(Modifier.height(Spacing.m))
            SecondaryButton(
                stringResource(R.string.tonight_oneoff_day_off_apply, dayName),
                onClick = actions.onDayOff,
                modifier = Modifier.fillMaxWidth(),
            )
            Divider()
        }

        SectionTitle(stringResource(R.string.tonight_oneoff_once_title))
        Spacer(Modifier.height(Spacing.s))
        LocalTimeScrubber(value = state.time, onValueChange = actions.onTime)
        Spacer(Modifier.height(Spacing.m))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            OnceModes.forEach { mode ->
                val label = if (mode == AlarmMode.OFF) stringResource(R.string.tonight_oneoff_mode_none) else mode.title()
                SelectableChip(label, selected = state.mode == mode, onClick = { actions.onMode(mode) })
            }
        }
        state.armBlocker?.let { blocker ->
            Spacer(Modifier.height(Spacing.m))
            ArmBlockerNotice(blocker, actions.onFixBlocker)
        }
        Spacer(Modifier.height(Spacing.m))
        PrimaryButton(
            stringResource(R.string.tonight_oneoff_once_apply, dayName),
            onClick = actions.onJustOnce,
            modifier = Modifier.fillMaxWidth(),
        )
        Divider()

        SectionTitle(stringResource(R.string.tonight_oneoff_pause_title))
        Spacer(Modifier.height(Spacing.s))
        Row(
            modifier = Modifier.chipRow(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            state.pauseDates.forEach { date ->
                SelectableChip(
                    text = formatShortDate(date, state.today),
                    selected = date == state.pauseUntil,
                    onClick = { actions.onPauseUntil(date) },
                )
            }
        }
        Spacer(Modifier.height(Spacing.m))
        SecondaryButton(
            stringResource(R.string.tonight_oneoff_pause_apply, formatShortDate(state.pauseUntil, state.today)),
            onClick = actions.onPause,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A row of date chips that scrolls sideways across the whole sheet, edge to edge, while its first chip lines up with
 * the text (the sheet's content is inset by [Spacing.xl]).
 */
@Composable
private fun Modifier.chipRow(): Modifier {
    val inset = Spacing.xl
    return layout { measurable, constraints ->
        val bleed = if (constraints.hasBoundedWidth) inset.roundToPx() else 0
        val wide = constraints.copy(minWidth = constraints.minWidth + 2 * bleed, maxWidth = constraints.maxWidth + 2 * bleed)
        val placeable = measurable.measure(wide)
        layout((placeable.width - 2 * bleed).coerceAtLeast(0), placeable.height) { placeable.place(-bleed, 0) }
    }
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = inset)
}

/** Just this once's modes, in the order shown: At wake time, Surfacing, "No alarm, just the time". */
private val OnceModes = listOf(AlarmMode.FIXED, AlarmMode.SURFACING, AlarmMode.OFF)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .padding(top = Spacing.l)
            .semantics { heading() },
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = SleepSaverTheme.colors.textSecondary,
        modifier = Modifier.padding(top = Spacing.xs),
    )
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(top = Spacing.l), color = MaterialTheme.colorScheme.outlineVariant)
}
