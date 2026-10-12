package com.sleepsaver.feature.winddown.steps

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Anchor
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.nightsteps.EveningResult
import com.sleepsaver.core.domain.nightsteps.EveningState
import com.sleepsaver.core.domain.nightsteps.GuidedTool
import com.sleepsaver.core.domain.nightsteps.NightStep
import com.sleepsaver.core.domain.nightsteps.StepAdherence
import com.sleepsaver.core.domain.nightsteps.StepSuggestion
import com.sleepsaver.core.domain.nightsteps.SuggestionReason
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.domain.patterns.EffectMetric
import com.sleepsaver.core.domain.patterns.MarkEffect
import com.sleepsaver.core.domain.patterns.PatternLabel
import com.sleepsaver.core.ui.components.CardPadding
import com.sleepsaver.core.ui.components.EvidenceBadge
import com.sleepsaver.core.ui.components.HushControls
import com.sleepsaver.core.ui.components.HushControlsActions
import com.sleepsaver.core.ui.components.HushControlsState
import com.sleepsaver.core.ui.components.InfoChip
import com.sleepsaver.core.ui.components.MarkChips
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.SecondaryButton
import com.sleepsaver.core.ui.components.SectionCard
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.ToggleRow
import com.sleepsaver.core.ui.components.title
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.formatAboutMinutes
import com.sleepsaver.core.ui.format.formatDayLong
import com.sleepsaver.core.ui.format.formatHours1
import com.sleepsaver.core.ui.format.keepTimesTogether
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.winddown.R
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToLong

/** From this system font scale, side-by-side bits stack. */
private const val StackFontScale = 1.5f

@Composable
private fun largeFont(): Boolean = LocalDensity.current.fontScale >= StackFontScale

// ---------------------------------------------------------------- Steps

/** "2 of 4 done tonight" and tonight's steps by start time, each with its check box; "Change tonight's steps". */
@Composable
internal fun StepsCard(
    state: NightStepsUiState.Ready,
    onToggle: (activityId: Long, done: Boolean) -> Unit,
    onStart: (GuidedTool) -> Unit,
    onChangeSteps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = state.session
    SleepSaverCard(modifier.fillMaxWidth(), contentPadding = EndsWithLinkPadding) {
        ProgressLine(done = session.doneCount, total = session.steps.size)
        Column(Modifier.padding(top = Spacing.s)) {
            session.steps.forEachIndexed { index, step ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StepRow(
                    step = step,
                    zone = state.zone,
                    night = state.night,
                    onToggle = { done -> onToggle(step.activity.id, done) },
                    onStart = onStart,
                )
            }
        }
        TextLinkButton(
            stringResource(R.string.winddown_change_steps),
            onClick = onChangeSteps,
            modifier = Modifier
                .padding(top = Spacing.xs)
                .offset(x = (-12).dp),
        )
    }
}

/** "2 of 4" big, with "done tonight" on its baseline (under it at large font sizes). */
@Composable
private fun ProgressLine(done: Int, total: Int) {
    val description = pluralStringResource(R.plurals.winddown_progress_description, total, done, total)
    val number = stringResource(R.string.winddown_progress, done, total)
    val label = stringResource(R.string.winddown_progress_label)
    val numberStyle = MaterialTheme.typography.displayMedium
    val labelStyle = MaterialTheme.typography.bodyLarge
    if (largeFont()) {
        Column(Modifier.clearAndSetSemantics { contentDescription = description }) {
            Text(number, style = numberStyle, color = MaterialTheme.colorScheme.onSurface)
            Text(label, style = labelStyle, color = SleepSaverTheme.colors.textSecondary)
        }
    } else {
        Row(Modifier.clearAndSetSemantics { contentDescription = description }) {
            Text(number, style = numberStyle, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.alignByBaseline())
            Text(
                label,
                style = labelStyle,
                color = SleepSaverTheme.colors.textSecondary,
                modifier = Modifier
                    .alignByBaseline()
                    .padding(start = Spacing.m),
            )
        }
    }
}

/**
 * One step: emoji, label, "9:45 PM · 10 min" (at night only "10 min"), the evidence badge and "Start" for a guided
 * tool. The whole row checks the step off.
 */
@Composable
private fun StepRow(step: NightStep, zone: ZoneId, night: Boolean, onToggle: (Boolean) -> Unit, onStart: (GuidedTool) -> Unit) {
    val activity = step.activity
    val length = stepLength(activity.duration)
    val whenLine = if (night) length else stringResource(R.string.winddown_step_when, clockTime(step.start, zone), length).keepTimesTogether()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = step.done, role = Role.Checkbox, onValueChange = onToggle)
            .padding(vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmojiCircle(activity.emoji)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = Spacing.m),
        ) {
            Text(activity.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                whenLine,
                style = MaterialTheme.typography.bodyMedium,
                color = SleepSaverTheme.colors.textSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
            val technique = activity.technique
            if (technique != null) {
                val tool = technique.tool.takeIf { it != GuidedTool.NONE }
                val large = largeFont()
                // The Start chip's 48 dp touch target already leaves room above the row.
                val top = if (tool != null && !large) 0.dp else Spacing.s
                Stack(large = large, modifier = Modifier.padding(top = top), spacing = Spacing.s) {
                    EvidenceBadge(technique.evidence)
                    if (tool != null) StartChip(technique, onClick = { onStart(tool) })
                }
            }
        }
        Checkbox(
            checked = step.done,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary,
                uncheckedColor = SleepSaverTheme.colors.textSecondary,
                checkmarkColor = MaterialTheme.colorScheme.onPrimary,
            ),
        )
    }
}

/** "10 min", or "Checklist item" for a step without a length. */
@Composable
internal fun stepLength(duration: Duration): String =
    if (duration.isZero || duration.isNegative) {
        stringResource(R.string.winddown_checklist_item)
    } else {
        stringResource(R.string.winddown_minutes, duration.toMinutes().toInt())
    }

/** A small tide "▶ Start" pill (48 dp touch target) that opens a step's guided tool. */
@Composable
private fun StartChip(technique: Technique, onClick: () -> Unit) {
    val description = stringResource(R.string.winddown_step_start_description, technique.title())
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.winddown_step_start), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** The emoji on a 40 dp raised circle. */
@Composable
internal fun EmojiCircle(emoji: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(SleepSaverTheme.colors.cardRaised),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, style = MaterialTheme.typography.titleMedium)
    }
}

/** Side by side, or stacked from a large system font. */
@Composable
internal fun Stack(large: Boolean, modifier: Modifier = Modifier, spacing: Dp = 0.dp, content: @Composable () -> Unit) {
    if (large) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) { content() }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

/** "Night steps are off this evening" with the way to the week plan. */
@Composable
internal fun StepsOffCard(day: DayOfWeek, onOpenWeekPlan: () -> Unit, modifier: Modifier = Modifier) {
    SleepSaverCard(modifier.fillMaxWidth(), contentPadding = EndsWithLinkPadding) {
        CardTitle(stringResource(R.string.winddown_off_title))
        Text(
            stringResource(R.string.winddown_off_body, formatDayLong(day)),
            style = MaterialTheme.typography.bodyMedium,
            color = SleepSaverTheme.colors.textSecondary,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        TextLinkButton(
            stringResource(R.string.winddown_week_plan),
            onClick = onOpenWeekPlan,
            modifier = Modifier
                .padding(top = Spacing.xs)
                .offset(x = (-12).dp),
        )
    }
}

/** "No steps yet. Pick a few that suit you." + "Pick steps". */
@Composable
internal fun NoStepsCard(onPickSteps: () -> Unit, modifier: Modifier = Modifier) {
    SleepSaverCard(modifier.fillMaxWidth()) {
        CardTitle(stringResource(R.string.winddown_empty_title))
        // Drop anchor stays the screen's one main action.
        SecondaryButton(
            stringResource(R.string.winddown_pick_steps),
            onClick = onPickSteps,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.l),
        )
    }
}

// ---------------------------------------------------------------- Suggestions

/** "Worth a try": up to two suggestions with their reason and an Add button; "See the library". */
@Composable
internal fun SuggestionsCard(
    suggestions: List<StepSuggestion>,
    onAdd: (Technique, String) -> Unit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = stringResource(R.string.winddown_worth_a_try),
        actionLabel = stringResource(R.string.winddown_see_library),
        onAction = onOpenLibrary,
        modifier = modifier.fillMaxWidth(),
    ) {
        suggestions.forEach { suggestion ->
            // Resolved here, in composition: the click lambda below must not call a @Composable title().
            val title = suggestion.technique.title()
            val addDescription = stringResource(R.string.winddown_add_description, title)
            Row(verticalAlignment = Alignment.Top) {
                EmojiCircle(suggestion.technique.emoji)
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = Spacing.m),
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        stringResource(suggestion.reason.textRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SleepSaverTheme.colors.textSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    EvidenceBadge(suggestion.technique.evidence, Modifier.padding(top = Spacing.s))
                }
                TextLinkButton(
                    stringResource(R.string.winddown_add),
                    onClick = { onAdd(suggestion.technique, title) },
                    modifier = Modifier
                        .offset(x = 12.dp, y = (-8).dp)
                        .semantics { contentDescription = addDescription },
                )
            }
        }
    }
}

/** Why a technique is suggested, in plain words. */
@get:StringRes
internal val SuggestionReason.textRes: Int
    get() = when (this) {
        SuggestionReason.SLOW_TO_SETTLE -> R.string.winddown_reason_slow_to_settle
        SuggestionReason.RESTLESS_NIGHTS -> R.string.winddown_reason_restless_nights
        SuggestionReason.STRESSFUL_DAYS -> R.string.winddown_reason_stressful_days
        SuggestionReason.DRIFTING_BEDTIMES -> R.string.winddown_reason_drifting_bedtimes
        SuggestionReason.SCREENS_IN_ROUTINE -> R.string.winddown_reason_screens_in_routine
        SuggestionReason.SHORT_ON_SLEEP -> R.string.winddown_reason_short_on_sleep
        SuggestionReason.STARTER -> R.string.winddown_reason_starter
    }

// ---------------------------------------------------------------- Tracking

/**
 * "4 of the last 6 nights", the 14-evening strip and the quiet streak ("5 evenings in a row", from
 * [streakShowFrom]). Hidden until some evening counts. Never shows a streak as lost.
 */
@Composable
internal fun TrackingCard(adherence: StepAdherence, streakShowFrom: Int, modifier: Modifier = Modifier) {
    if (adherence.evenings.none { it.state == EveningState.DONE || it.state == EveningState.MISSED }) return
    WrapCard(stringResource(R.string.winddown_tracking_title), modifier) {
        if (adherence.countedRecent > 0) {
            Text(
                pluralStringResource(
                    R.plurals.winddown_tracking_recent,
                    adherence.countedRecent,
                    adherence.doneRecent,
                    adherence.countedRecent,
                ),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        DotStrip(adherence.evenings)
        if (adherence.quietStreak >= streakShowFrom) {
            Text(
                pluralStringResource(R.plurals.winddown_quiet_streak, adherence.quietStreak, adherence.quietStreak),
                style = MaterialTheme.typography.bodyMedium,
                color = SleepSaverTheme.colors.textSecondary,
            )
        }
    }
}

/** One dot per evening, oldest first: done filled tide, missed hollow grey, not counted a tiny dot, tonight a tide ring. */
@Composable
private fun DotStrip(evenings: List<EveningResult>) {
    val description = pluralStringResource(
        R.plurals.winddown_strip_description,
        evenings.size,
        evenings.size,
        evenings.count { it.state == EveningState.DONE },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tide = MaterialTheme.colorScheme.primary
        val grey = SleepSaverTheme.colors.textTertiary
        evenings.forEach { evening ->
            Box(Modifier.size(DotSize), contentAlignment = Alignment.Center) {
                when (evening.state) {
                    EveningState.DONE -> Box(
                        Modifier
                            .size(DotSize)
                            .clip(CircleShape)
                            .background(tide),
                    )
                    EveningState.MISSED -> Box(
                        Modifier
                            .size(DotSize)
                            .border(1.5.dp, grey, CircleShape),
                    )
                    EveningState.OPEN -> Box(
                        Modifier
                            .size(DotSize)
                            .border(1.5.dp, tide, CircleShape),
                    )
                    EveningState.NEUTRAL -> Box(
                        Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(grey),
                    )
                }
            }
        }
    }
}

private val DotSize = 14.dp

// ---------------------------------------------------------------- Patterns

/** "How your Night steps evenings compare": Clear / Possible patterns of NIGHT_STEPS_DONE, always with the caveat. */
@Composable
internal fun CompareCard(rows: List<MarkEffect>, status: CompareStatus, modifier: Modifier = Modifier) {
    WrapCard(stringResource(R.string.winddown_compare_title), modifier) {
        when (status) {
            CompareStatus.ROWS -> {
                rows.forEach { effect ->
                    Column {
                        Text(effectSentence(effect), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        InfoChip(
                            stringResource(
                                if (effect.label == PatternLabel.CLEAR) R.string.winddown_pattern_clear else R.string.winddown_pattern_possible,
                            ),
                            modifier = Modifier.padding(top = Spacing.s),
                        )
                    }
                }
                Caveat()
            }
            CompareStatus.NOT_ENOUGH -> Body(stringResource(R.string.winddown_compare_not_enough))
            CompareStatus.NO_PATTERN -> {
                Body(stringResource(R.string.winddown_compare_no_pattern))
                Caveat()
            }
        }
    }
}

/**
 * "Nights after Night steps: about 25 min more sleep (8 vs 31 nights)", "…about 10 min less awake time …",
 * "…mornings felt about 0.5 steps brighter …".
 */
@Composable
private fun effectSentence(effect: MarkEffect): String {
    val difference = effect.difference ?: 0.0
    val more = difference >= 0
    val minutes = formatAboutMinutes(Duration.ofMinutes(abs(difference).roundToLong()))
    return when (effect.metric) {
        EffectMetric.TOTAL_SLEEP -> pluralStringResource(
            if (more) R.plurals.winddown_compare_sleep_more else R.plurals.winddown_compare_sleep_less,
            effect.withoutNights,
            minutes,
            effect.withNights,
            effect.withoutNights,
        )
        EffectMetric.AWAKE -> pluralStringResource(
            if (more) R.plurals.winddown_compare_awake_more else R.plurals.winddown_compare_awake_less,
            effect.withoutNights,
            minutes,
            effect.withNights,
            effect.withoutNights,
        )
        EffectMetric.MORNING_FEEL -> pluralStringResource(
            if (more) R.plurals.winddown_compare_feel_brighter else R.plurals.winddown_compare_feel_foggier,
            effect.withoutNights,
            formatHours1(abs(difference)),
            effect.withNights,
            effect.withoutNights,
        )
    }
}

@Composable
private fun Caveat() {
    Text(
        stringResource(R.string.winddown_compare_caveat),
        style = MaterialTheme.typography.bodySmall,
        color = SleepSaverTheme.colors.textSecondary,
    )
}

// ---------------------------------------------------------------- Marks and Drop anchor

/** "Anything to mark for tonight?" with the eight marks. */
@Composable
internal fun MarksCard(selected: Set<Mark>, onToggle: (Mark) -> Unit, modifier: Modifier = Modifier) {
    WrapCard(stringResource(R.string.winddown_marks_title), modifier) {
        MarkChips(selected = selected, onToggle = onToggle)
    }
}

/** The evening's last step: "Ready to settle in?" + Drop anchor, or "Anchor dropped" once it is. */
@Composable
internal fun AnchorCard(
    anchored: Boolean,
    night: Boolean,
    onDropAnchor: () -> Unit,
    onOpenTonight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SleepSaverCard(modifier.fillMaxWidth(), contentPadding = if (anchored) EndsWithLinkPadding else CardPadding) {
        if (anchored) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Icon(Icons.Rounded.Anchor, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                CardTitle(stringResource(R.string.winddown_anchor_dropped))
            }
            TextLinkButton(
                stringResource(R.string.winddown_open_tonight),
                onClick = onOpenTonight,
                modifier = Modifier
                    .padding(top = Spacing.xs)
                    .offset(x = (-12).dp),
            )
        } else {
            CardTitle(stringResource(R.string.winddown_anchor_title))
            MainButton(
                stringResource(R.string.winddown_drop_anchor),
                onClick = onDropAnchor,
                night = night,
                modifier = Modifier.padding(top = Spacing.l),
                icon = Icons.Rounded.Anchor,
            )
        }
    }
}

/** "Settling in": the optional whole-night movement log (off by default), then Drop anchor. */
@Composable
internal fun AnchorSheetContent(night: Boolean, onDrop: (wholeNightLog: Boolean) -> Unit, modifier: Modifier = Modifier) {
    var log by rememberSaveable { mutableStateOf(false) }
    Column(modifier.padding(start = Spacing.screenHorizontal, end = Spacing.screenHorizontal, bottom = Spacing.xl)) {
        Text(
            stringResource(R.string.winddown_sheet_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        ToggleRow(
            title = stringResource(R.string.winddown_sheet_log),
            checked = log,
            onCheckedChange = { log = it },
            supporting = stringResource(R.string.winddown_sheet_log_body, stringResource(com.sleepsaver.core.ui.R.string.ss_placement)),
            modifier = Modifier.padding(top = Spacing.m),
        )
        MainButton(
            stringResource(R.string.winddown_drop_anchor),
            onClick = { onDrop(log) },
            night = night,
            modifier = Modifier.padding(top = Spacing.l),
            icon = Icons.Rounded.Anchor,
        )
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
    }
}

/** Hush inline, from a Hush step's "Start". */
@Composable
internal fun HushSheetContent(state: HushControlsState, actions: HushControlsActions, modifier: Modifier = Modifier) {
    Column(modifier.padding(start = Spacing.screenHorizontal, end = Spacing.screenHorizontal, bottom = Spacing.xl)) {
        Text(
            stringResource(com.sleepsaver.core.ui.R.string.ss_hush_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(bottom = Spacing.m)
                .semantics { heading() },
        )
        HushControls(state, actions, Modifier.fillMaxWidth())
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
    }
}

// ---------------------------------------------------------------- Shared pieces

/** A card with a heading that wraps (long titles stay whole at large font sizes) and children 12 dp apart. */
@Composable
internal fun WrapCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    SleepSaverCard(modifier.fillMaxWidth()) {
        CardTitle(title)
        Column(Modifier.padding(top = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.m), content = content)
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = SleepSaverTheme.colors.textSecondary)
}

/** A card that ends with a text button: its 48 dp touch target stands in for most of the bottom padding. */
private val EndsWithLinkPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = Spacing.s)

/** The card's main action: a tide [PrimaryButton] in the evening, a quiet [SecondaryButton] at night (no glow). */
@Composable
private fun MainButton(text: String, onClick: () -> Unit, night: Boolean, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    if (night) {
        SecondaryButton(text, onClick = onClick, modifier = modifier.fillMaxWidth(), icon = icon)
    } else {
        PrimaryButton(text, onClick = onClick, modifier = modifier.fillMaxWidth(), icon = icon)
    }
}
