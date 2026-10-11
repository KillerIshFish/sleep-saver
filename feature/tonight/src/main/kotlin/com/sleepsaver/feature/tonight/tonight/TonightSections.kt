package com.sleepsaver.feature.tonight.tonight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Anchor
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.alarm.CatnapTiming
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.DayPart
import com.sleepsaver.core.ui.components.HushControls
import com.sleepsaver.core.ui.components.MarkChips
import com.sleepsaver.core.ui.components.NoticeCard
import com.sleepsaver.core.ui.components.NoticeTone
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.SecondaryButton
import com.sleepsaver.core.ui.components.SectionCard
import com.sleepsaver.core.ui.components.SelectableChip
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.WellnessDisclaimer
import com.sleepsaver.core.ui.components.fix
import com.sleepsaver.core.ui.components.icon
import com.sleepsaver.core.ui.components.title
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.formatDayLong
import com.sleepsaver.core.ui.format.formatDayNarrow
import com.sleepsaver.core.ui.format.formatDayShort
import com.sleepsaver.core.ui.format.formatShortDate
import com.sleepsaver.core.ui.format.keepTimesTogether
import com.sleepsaver.core.ui.platform.AlarmBlocker
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.tonight.ArmBlockerNotice
import com.sleepsaver.feature.tonight.DebtPreviewChip
import com.sleepsaver.feature.tonight.R
import com.sleepsaver.feature.tonight.TonightPhase
import com.sleepsaver.feature.tonight.dayAndTime
import com.sleepsaver.core.ui.R as UiR

private val ScreenPadding = Modifier.padding(horizontal = Spacing.screenHorizontal)

/** What stopped the last attempt to bring the alarm back ([alarm]) or to start a catnap ([catnap]). */
internal data class ArmBlockers(val alarm: AlarmBlocker? = null, val catnap: AlarmBlocker? = null)

/** The tab's sections for the day phase (SPEC_V2 §5.2 table): what matters now comes first. */
@Composable
internal fun ColumnScope.TonightSections(
    state: TonightUiState.Ready,
    actions: TonightActions,
    alarmBlockers: List<AlarmBlocker>,
    armBlockers: ArmBlockers,
) {
    val card = ScreenPadding.fillMaxWidth()
    if (state.demo) {
        DemoLine(ScreenPadding.padding(top = Spacing.s))
    }
    when (state.phase) {
        TonightPhase.MORNING -> {
            TextLinkButton(
                stringResource(R.string.tonight_morning_review),
                onClick = { actions.onOpenMorningReview(state.today) },
                modifier = Modifier.padding(start = Spacing.s),
            )
            state.tomorrowList?.let { list ->
                Gap()
                SectionCard(stringResource(R.string.tonight_list_title), card) {
                    list.items.forEach { item -> Bullet(item) }
                }
            }
            Gap()
            AlarmSection(state, actions, alarmBlockers, armBlockers.alarm, card)
            Gap()
            NightStepsSummary(state, actions, card)
            Gap()
            WeekStrip(state, actions, card)
            Gap()
            HushSection(state, actions, card)
        }
        TonightPhase.DAY -> {
            Spacer(Modifier.height(Spacing.l))
            AlarmSection(state, actions, alarmBlockers, armBlockers.alarm, card)
            state.catnap?.let { catnap ->
                Gap()
                CatnapCard(catnap, state, actions, armBlockers.catnap, card)
            }
            Gap()
            NightStepsPreview(state, actions, card)
            Gap()
            WeekStrip(state, actions, card)
            Gap()
            HushSection(state, actions, card)
        }
        TonightPhase.EVENING, TonightPhase.NIGHT -> {
            Spacer(Modifier.height(Spacing.l))
            NightStepsCard(state, actions, card)
            Gap()
            DropAnchorCard(actions, card)
            Gap()
            AlarmSection(state, actions, alarmBlockers, armBlockers.alarm, card, showPreview = true)
            Gap()
            SectionCard(stringResource(R.string.tonight_marks_title), card) {
                MarkChips(selected = state.tonightMarks, onToggle = { mark -> actions.onMark(mark, mark !in state.tonightMarks) })
            }
            Gap()
            HushSection(state, actions, card)
            Gap()
            WeekStrip(state, actions, card)
        }
    }
    Spacer(Modifier.weight(1f))
    Spacer(Modifier.height(Spacing.xxl))
    WellnessDisclaimer(ScreenPadding)
}

// ---------------------------------------------------------------- Alarm card

@Composable
private fun AlarmSection(
    state: TonightUiState.Ready,
    actions: TonightActions,
    alarmBlockers: List<AlarmBlocker>,
    armBlocker: AlarmBlocker?,
    modifier: Modifier,
    showPreview: Boolean = false,
) {
    Column(modifier) {
        AlarmCard(state, actions, showPreview)
        // "Turn back on" or "Resume alarms" was stopped by the arming gate: say why, with the fix.
        armBlocker?.let {
            Gap()
            ArmBlockerNotice(it, actions.onFixBlocker)
        }
        val first = alarmBlockers.firstOrNull()
        if (state.armed && first != null) {
            Gap()
            NoticeCard(
                title = stringResource(R.string.tonight_blocked_title),
                // "Alarms & reminders is off. Without it, …": the fix line alone wouldn't say what "it" is.
                body = stringResource(R.string.tonight_blocked_body, first.title(), first.fix()),
                icon = first.icon,
                actionLabel = stringResource(R.string.tonight_action_alarm_check),
                onAction = actions.onOpenAlarmCheck,
                tone = NoticeTone.WARNING,
            )
        }
    }
}

@Composable
private fun AlarmCard(state: TonightUiState.Ready, actions: TonightActions, showPreview: Boolean) {
    val secondary = SleepSaverTheme.colors.textSecondary
    val dayName = formatDayShort(state.wakeDate.dayOfWeek)
    SleepSaverCard(Modifier.fillMaxWidth()) {
        when (val alarm = state.alarmCard) {
            is AlarmCardModel.On -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            clockTime(alarm.time),
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        Text(
                            if (alarm.thisMorning) {
                                stringResource(R.string.tonight_alarm_this_morning)
                            } else {
                                stringResource(R.string.tonight_alarm_tomorrow, dayName)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = secondary,
                        )
                    }
                    Icon(alarm.mode.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(Spacing.m))
                Text(
                    if (alarm.mode == AlarmMode.SURFACING) {
                        stringResource(R.string.tonight_alarm_surfacing, clockTime(alarm.windowStart), clockTime(alarm.time))
                    } else {
                        stringResource(R.string.tonight_alarm_fixed, clockTime(alarm.time))
                    }.keepTimesTogether(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondary,
                )
                if (alarm.mode == AlarmMode.SURFACING && alarm.debtAware) {
                    Text(
                        stringResource(R.string.tonight_alarm_debt_aware),
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondary,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
                if (alarm.justOnce) {
                    Text(
                        stringResource(R.string.tonight_alarm_just_once),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
                if (showPreview) {
                    state.debtPreview?.let { preview ->
                        Spacer(Modifier.height(Spacing.m))
                        DebtPreviewChip(preview, state.today, state.zone)
                    }
                }
                CardActions {
                    TextLinkButton(stringResource(R.string.tonight_action_day_off), onClick = { actions.onDayOff(alarm.wakeDate) })
                    TextLinkButton(stringResource(R.string.tonight_action_just_once), onClick = { actions.onOneOff(alarm.wakeDate) })
                    TextLinkButton(stringResource(R.string.tonight_action_week_plan), onClick = actions.onOpenWeekPlan)
                }
            }
            is AlarmCardModel.DayOff -> {
                BigLine(stringResource(R.string.tonight_alarm_no_alarm_day, dayName))
                Line(stringResource(R.string.tonight_alarm_day_off))
                alarm.next?.let { Line(stringResource(R.string.tonight_alarm_next, dayAndTime(it.date, it.time))) }
                CardActions {
                    TextLinkButton(stringResource(R.string.tonight_action_turn_back_on), onClick = { actions.onTurnBackOn(alarm.wakeDate) })
                }
            }
            is AlarmCardModel.Paused -> {
                BigLine(stringResource(R.string.tonight_alarm_paused))
                Line(stringResource(R.string.tonight_alarm_paused_until, formatShortDate(alarm.until, state.today)))
                alarm.next?.let { Line(stringResource(R.string.tonight_alarm_next, dayAndTime(it.date, it.time))) }
                CardActions {
                    TextLinkButton(stringResource(R.string.tonight_action_resume), onClick = actions.onResumeAlarms)
                }
            }
            is AlarmCardModel.None -> {
                BigLine(
                    stringResource(
                        if (alarm.wakeDate == state.today) R.string.tonight_alarm_none_today else R.string.tonight_alarm_none,
                    ),
                )
                alarm.next?.let { Line(stringResource(R.string.tonight_alarm_next, dayAndTime(it.date, it.time))) }
                CardActions {
                    TextLinkButton(stringResource(R.string.tonight_action_set_one, dayName), onClick = { actions.onOneOff(alarm.wakeDate) })
                    TextLinkButton(stringResource(R.string.tonight_action_week_plan), onClick = actions.onOpenWeekPlan)
                }
            }
        }
    }
}

/** A row of text actions under a card's content, pulled left so the first label lines up with the text. */
@Composable
private fun CardActions(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier
            .padding(top = Spacing.s)
            .offset(x = (-12).dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) { content() }
}

@Composable
private fun BigLine(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun Line(text: String) {
    Text(
        text.keepTimesTogether(),
        style = MaterialTheme.typography.bodyMedium,
        color = SleepSaverTheme.colors.textSecondary,
        modifier = Modifier.padding(top = Spacing.xs),
    )
}

// ---------------------------------------------------------------- Night steps

/** EVENING: tonight's checklist at a glance, with the way in. */
@Composable
private fun NightStepsCard(state: TonightUiState.Ready, actions: TonightActions, modifier: Modifier) {
    val steps = state.steps ?: return
    SectionCard(stringResource(R.string.tonight_steps_title), modifier) {
        when {
            !steps.enabled -> {
                Text(stringResource(R.string.tonight_steps_off), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                TextLinkButton(
                    stringResource(R.string.tonight_steps_change_evening, formatDayLong(state.nightOf.dayOfWeek)),
                    onClick = { actions.onOpenDayPlan(state.nightOf.dayOfWeek, DayPart.EVENING) },
                    modifier = Modifier.offset(x = (-12).dp),
                )
            }
            steps.steps.isEmpty() -> {
                Text(stringResource(R.string.tonight_steps_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                TextLinkButton(stringResource(R.string.tonight_steps_pick), onClick = actions.onOpenRoutineEditor, modifier = Modifier.offset(x = (-12).dp))
            }
            else -> {
                Text(
                    stringResource(R.string.tonight_steps_window, clockTime(steps.windowStart, state.zone), clockTime(steps.bedtime, state.zone))
                        .keepTimesTogether(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepSaverTheme.colors.textSecondary,
                )
                Text(
                    stringResource(R.string.tonight_steps_done, steps.doneCount, steps.steps.size),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val next = steps.currentStep(state.now)
                Text(
                    if (next != null) {
                        stringResource(R.string.tonight_steps_next, next.activity.emoji, next.activity.label)
                    } else {
                        stringResource(R.string.tonight_steps_all_done)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SecondaryButton(stringResource(R.string.tonight_steps_open), onClick = actions.onOpenNightSteps, modifier = Modifier.fillMaxWidth())
            }
        }
        if (steps.fromSettingsOnly) SettingsOnlyCaption()
    }
}

/** DAY: when tonight's Night steps start and how many there are. */
@Composable
private fun NightStepsPreview(state: TonightUiState.Ready, actions: TonightActions, modifier: Modifier) {
    val steps = state.steps ?: return
    SectionCard(
        stringResource(R.string.tonight_steps_title),
        modifier,
        actionLabel = stringResource(R.string.tonight_steps_open_short),
        onAction = actions.onOpenNightSteps,
    ) {
        StepsWindowLines(state)
        if (steps.fromSettingsOnly) SettingsOnlyCaption()
    }
}

/** MORNING: how the evenings went ("4 of the last 5 nights") and tonight's window. */
@Composable
private fun NightStepsSummary(state: TonightUiState.Ready, actions: TonightActions, modifier: Modifier) {
    val adherence = state.adherence
    if (state.steps == null && (adherence == null || adherence.countedRecent == 0)) return
    SectionCard(
        stringResource(R.string.tonight_steps_title),
        modifier,
        actionLabel = stringResource(R.string.tonight_steps_open_short),
        onAction = actions.onOpenNightSteps,
    ) {
        if (adherence != null && adherence.countedRecent > 0) {
            Text(
                pluralStringResource(R.plurals.tonight_adherence, adherence.countedRecent, adherence.doneRecent, adherence.countedRecent),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        StepsWindowLines(state)
    }
}

@Composable
private fun StepsWindowLines(state: TonightUiState.Ready) {
    val steps = state.steps ?: return
    val secondary = SleepSaverTheme.colors.textSecondary
    when {
        !steps.enabled -> Text(stringResource(R.string.tonight_steps_off), style = MaterialTheme.typography.bodyMedium, color = secondary)
        steps.steps.isEmpty() -> Text(stringResource(R.string.tonight_steps_empty), style = MaterialTheme.typography.bodyMedium, color = secondary)
        else -> {
            Text(
                stringResource(R.string.tonight_steps_window, clockTime(steps.windowStart, state.zone), clockTime(steps.bedtime, state.zone))
                    .keepTimesTogether(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                pluralStringResource(R.plurals.tonight_steps_tonight, steps.steps.size, steps.steps.size),
                style = MaterialTheme.typography.bodyMedium,
                color = secondary,
            )
        }
    }
}

@Composable
private fun SettingsOnlyCaption() {
    Text(
        stringResource(R.string.tonight_steps_settings_only),
        style = MaterialTheme.typography.bodySmall,
        color = SleepSaverTheme.colors.textSecondary,
    )
}

// ---------------------------------------------------------------- Drop anchor

@Composable
private fun DropAnchorCard(actions: TonightActions, modifier: Modifier) {
    SleepSaverCard(modifier) {
        Text(
            stringResource(R.string.tonight_drop_anchor_body),
            style = MaterialTheme.typography.bodyMedium,
            color = SleepSaverTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(Spacing.m))
        PrimaryButton(
            text = stringResource(R.string.tonight_drop_anchor),
            onClick = actions.onDropAnchor,
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Rounded.Anchor,
        )
    }
}

// ---------------------------------------------------------------- Catnap

@Composable
private fun CatnapCard(
    catnap: CatnapModel,
    state: TonightUiState.Ready,
    actions: TonightActions,
    armBlocker: AlarmBlocker?,
    modifier: Modifier,
) {
    val config = SleepConfig.DEFAULT
    val secondary = SleepSaverTheme.colors.textSecondary
    SectionCard(stringResource(R.string.tonight_catnap_title), modifier) {
        val running = catnap.running
        if (running != null) {
            Text(
                stringResource(R.string.tonight_catnap_running, clockTime(running.ringsAt, state.zone)).keepTimesTogether(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextLinkButton(stringResource(R.string.tonight_catnap_cancel), onClick = actions.onCancelCatnap, modifier = Modifier.offset(x = (-12).dp))
            return@SectionCard
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            catnap.lengths.forEach { length ->
                SelectableChip(
                    text = stringResource(R.string.tonight_minutes, length.toMinutes().toInt()),
                    selected = length == catnap.length,
                    onClick = { actions.onCatnapLength(length) },
                )
            }
        }
        val plan = catnap.plan
        when (plan.timing) {
            CatnapTiming.IN_AFTERNOON_DIP -> R.string.tonight_catnap_dip
            CatnapTiming.CLOSE_TO_BEDTIME -> R.string.tonight_catnap_bedtime
            CatnapTiming.EARLY -> R.string.tonight_catnap_early
            CatnapTiming.FINE -> null
        }?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
        if (plan.mayFeelGroggy) {
            Text(
                stringResource(R.string.tonight_catnap_groggy, plan.length.toMinutes().toInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = secondary,
            )
        }
        if (plan.coffeeHint) {
            Text(stringResource(R.string.tonight_catnap_coffee), style = MaterialTheme.typography.bodyMedium, color = secondary)
        }
        Text(
            stringResource(R.string.tonight_catnap_fall_asleep, config.catnap.fallAsleep.toMinutes().toInt()),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )
        armBlocker?.let { ArmBlockerNotice(it, actions.onFixBlocker) }
        SecondaryButton(stringResource(R.string.tonight_catnap_start), onClick = actions.onStartCatnap, modifier = Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------- This week, Hush, demo

@Composable
private fun WeekStrip(state: TonightUiState.Ready, actions: TonightActions, modifier: Modifier) {
    val secondary = SleepSaverTheme.colors.textSecondary
    SectionCard(
        stringResource(R.string.tonight_week_title),
        modifier,
        actionLabel = stringResource(R.string.tonight_action_week_plan),
        onAction = actions.onOpenWeekPlan,
        onClick = actions.onOpenWeekPlan,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            state.week.forEach { day ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 28.dp)
                        .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The narrow day ("T"), read out in full ("Tuesday").
                    val dayName = formatDayLong(day.date.dayOfWeek)
                    Text(
                        formatDayNarrow(day.date.dayOfWeek),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (day.alarmOn) MaterialTheme.colorScheme.onSurface else secondary,
                        modifier = Modifier
                            .width(32.dp)
                            .semantics { contentDescription = dayName },
                    )
                    Text(
                        day.time?.let { clockTime(it) } ?: stringResource(R.string.tonight_week_none),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (day.alarmOn) FontWeight.Medium else FontWeight.Normal),
                        color = if (day.alarmOn) MaterialTheme.colorScheme.onSurface else secondary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        day.mode.icon,
                        contentDescription = day.mode.title(),
                        tint = if (day.alarmOn) MaterialTheme.colorScheme.primary else SleepSaverTheme.colors.textTertiary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HushSection(state: TonightUiState.Ready, actions: TonightActions, modifier: Modifier) {
    SectionCard(stringResource(UiR.string.ss_hush_title), modifier) {
        HushControls(state = state.hush, actions = actions.hush)
    }
}

@Composable
private fun DemoLine(modifier: Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Icon(Icons.Rounded.Science, contentDescription = null, tint = SleepSaverTheme.colors.textTertiary, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.tonight_demo), style = MaterialTheme.typography.bodySmall, color = SleepSaverTheme.colors.textSecondary)
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
