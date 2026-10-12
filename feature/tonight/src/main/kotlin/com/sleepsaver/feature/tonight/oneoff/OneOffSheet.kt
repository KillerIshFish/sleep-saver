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
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.formatShortDate
import com.sleepsaver.core.ui.format.keepTimesTogether
import com.sleepsaver.core.ui.platform.AlarmBlocker
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.tonight.AlarmGate
import com.sleepsaver.feature.tonight.ArmBlockerNotice
import com.sleepsaver.feature.tonight.ArmSource
import com.sleepsaver.feature.tonight.R
import com.sleepsaver.feature.tonight.nearDay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * The one-off sheet as drawn. [date] is the day being changed; [dates] the days it can switch to (from the week plan,
 * [oneOffDates]; empty when opened from the alarm card, which is about one date). [alarmOn] = that date has an alarm, so "Day off"
 * is offered. [time] and [mode] are "Just this once"'s choice; [onceEnded] = with an alarm, that choice would be the
 * alarm that already ended at that time that morning, which never rings again, so it can't be set. [pauseUntil] is the
 * last paused day of [pauseDates]. [armBlocker] is why Just this once didn't apply (notifications or the alarm
 * channel), [pauseBlocker] why a shorter pause didn't.
 */
@Immutable
internal data class OneOffSheetState(
    val date: LocalDate,
    val dates: List<LocalDate>,
    val today: LocalDate,
    val alarmOn: Boolean,
    val time: LocalTime,
    val mode: AlarmMode,
    val onceEnded: Boolean = false,
    val pauseDates: List<LocalDate>,
    val pauseUntil: LocalDate,
    val armBlocker: AlarmBlocker? = null,
    val pauseBlocker: AlarmBlocker? = null,
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
 * Just this once with an alarm, and a pause that ends an earlier one sooner (alarms come back after it), go through
 * [gate]. It opens on [date]; with [chooseDate] (the week plan) [date] is the next morning a one-off can change (the
 * Tonight card's date), and the sheet offers it and the `week.alarmLookaheadDays − 1` after it: at 8:41 PM, tomorrow
 * on, never this morning, whose wake time has passed.
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
    val dates = if (chooseDate) oneOffDates(date, config) else emptyList()
    // A pick the list has moved past (this morning's alarm rang while the sheet was open) moves on with it.
    val day = LocalDate.parse(picked).let { if (chooseDate) maxOf(it, date) else it }
    var time by rememberSaveable(day) { mutableStateOf(context.startTime(day, config).toString()) }
    var mode by rememberSaveable(day) { mutableStateOf(context.startMode(day, config)) }
    val pauseDates = (1L..config.week.pauseMaxDays).map { context.today.plusDays(it) }
    var pauseUntil by rememberSaveable { mutableStateOf((context.oneOffs.pause?.until ?: pauseDates.first()).toString()) }
    // Monday's 6:50 already rang (the clock went back): Monday at 6:50 is that alarm and can't ring again.
    val onceEnded = mode != AlarmMode.OFF && context.alreadyEnded(day, LocalTime.parse(time))

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
                dates = dates,
                today = context.today,
                alarmOn = context.resolve(day).alarmOn,
                time = LocalTime.parse(time),
                mode = mode,
                onceEnded = onceEnded,
                pauseDates = pauseDates,
                pauseUntil = LocalDate.parse(pauseUntil),
                armBlocker = gate.blocker(ArmSource.ONE_OFF),
                pauseBlocker = gate.blocker(ArmSource.PAUSE),
            ),
            actions = OneOffSheetActions(
                onDate = { picked = it.toString() },
                onDayOff = { close { onDayOff(day) } },
                onTime = { time = it.toString() },
                onMode = { mode = it },
                onJustOnce = {
                    val chosenTime = LocalTime.parse(time)
                    val chosenMode = mode
                    if (!onceEnded) {
                        gate.armIf(chosenMode != AlarmMode.OFF, ArmSource.ONE_OFF) { close { onJustOnce(day, chosenTime, chosenMode) } }
                    }
                },
                onPauseUntil = { pauseUntil = it.toString() },
                onPause = {
                    val until = LocalDate.parse(pauseUntil)
                    gate.armIf(context.pausingArms(until), ArmSource.PAUSE) { close { onPause(until) } }
                },
                onFixBlocker = gate::fix,
            ),
        )
    }
}

/** The sheet's body, separate so it can be screenshot-tested. */
@Composable
internal fun OneOffSheetContent(state: OneOffSheetState, actions: OneOffSheetActions, modifier: Modifier = Modifier) {
    // "Set for Mon", or "Set for Mon, Oct 19" when the dates offered hold two Mondays.
    val dayName = nearDay(state.date, state.today)
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
        if (state.onceEnded) {
            Spacer(Modifier.height(Spacing.s))
            Body(stringResource(R.string.tonight_oneoff_once_ended, clockTime(state.time), dayName).keepTimesTogether())
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
            enabled = !state.onceEnded,
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
        state.pauseBlocker?.let { blocker ->
            Spacer(Modifier.height(Spacing.m))
            ArmBlockerNotice(blocker, actions.onFixBlocker)
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
