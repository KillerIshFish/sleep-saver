package com.sleepsaver.feature.progress.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.patterns.EffectMetric
import com.sleepsaver.core.domain.patterns.MarkEffect
import com.sleepsaver.core.domain.patterns.PatternLabel
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.label
import com.sleepsaver.core.ui.format.formatAboutMinutes
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.progress.MarkPatterns
import com.sleepsaver.feature.progress.R
import java.time.Duration
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * "What went with your nights" on the Quality tab: each clear or possible pattern as "Nights after alcohol: about
 * 25 min less sleep (8 vs 31 nights)" with its label, then a collapsed "Not enough nights yet" list and the caveat
 * "This shows what went together, not what caused it." Without a pattern it explains when they appear.
 */
@Composable
internal fun MarksCard(marks: MarkPatterns, modifier: Modifier = Modifier, config: SleepConfig = SleepConfig.DEFAULT) {
    val colors = SleepSaverTheme.colors
    SleepSaverCard(modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.progress_marks_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        if (marks.shown.isEmpty()) {
            Text(
                text = if (marks.anyMeasured) {
                    stringResource(R.string.progress_marks_none_stand_out)
                } else {
                    pluralStringResource(R.plurals.progress_marks_empty, config.journal.marksMinPerSide, config.journal.marksMinPerSide)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = Spacing.s),
            )
        } else {
            Column(Modifier.padding(top = Spacing.s)) {
                marks.shown.forEachIndexed { index, effect ->
                    if (index > 0) HorizontalDivider(color = colors.gridLine)
                    EffectRow(effect)
                }
            }
        }
        if (marks.waiting.isNotEmpty()) WaitingList(marks)
        if (marks.shown.isNotEmpty()) {
            Text(
                text = stringResource(R.string.progress_marks_caveat),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = Spacing.m),
            )
        }
    }
}

@Composable
private fun EffectRow(effect: MarkEffect) {
    val nightsRes = effect.mark.nightsRes() ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.m)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(
            text = pluralStringResource(
                R.plurals.progress_marks_row,
                effect.withoutNights,
                stringResource(nightsRes),
                effectText(effect),
                effect.withNights,
                effect.withoutNights,
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        PatternChip(effect.label)
    }
}

/** "about 25 min less sleep", "about 10 min more awake time", "mornings felt about 0.5 steps brighter". */
@Composable
private fun effectText(effect: MarkEffect): String {
    val difference = effect.difference ?: 0.0
    return when (effect.metric) {
        EffectMetric.TOTAL_SLEEP -> stringResource(
            if (difference >= 0) R.string.progress_marks_more_sleep else R.string.progress_marks_less_sleep,
            aboutMinutes(difference),
        )
        EffectMetric.AWAKE -> stringResource(
            if (difference >= 0) R.string.progress_marks_more_awake else R.string.progress_marks_less_awake,
            aboutMinutes(difference),
        )
        EffectMetric.MORNING_FEEL -> {
            val steps = feelSteps(difference)
            pluralStringResource(
                if (difference >= 0) R.plurals.progress_marks_brighter else R.plurals.progress_marks_foggier,
                if (steps == "1") 1 else 2,
                steps,
            )
        }
    }
}

private fun aboutMinutes(minutes: Double): String = formatAboutMinutes(Duration.ofMinutes(abs(minutes).roundToLong()))

/** Feel levels with one decimal and no trailing ".0": 0.5 → "0.5", 1.0 → "1". */
private fun feelSteps(levels: Double): String {
    val text = String.format(Locale.US, "%.1f", abs(levels))
    return text.removeSuffix(".0")
}

/** "Clear pattern" in tide, "Possible pattern" in grey: an outline, never a warning colour. */
@Composable
private fun PatternChip(label: PatternLabel) {
    val clear = label == PatternLabel.CLEAR
    val color = if (clear) MaterialTheme.colorScheme.primary else SleepSaverTheme.colors.textSecondary
    val outline = if (clear) MaterialTheme.colorScheme.primary else SleepSaverTheme.colors.textTertiary
    Row(
        modifier = Modifier
            .heightIn(min = 26.dp)
            .border(1.dp, outline, CircleShape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(if (clear) R.string.progress_pattern_clear else R.string.progress_pattern_possible),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/** "Not enough nights yet", collapsed until tapped: "Stress: 4 of 6 nights". */
@Composable
private fun WaitingList(marks: MarkPatterns) {
    val colors = SleepSaverTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    val header = stringResource(R.string.progress_marks_not_enough)
    val state = stringResource(if (expanded) R.string.progress_marks_expanded else R.string.progress_marks_collapsed)
    Column(Modifier.padding(top = Spacing.s)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics(mergeDescendants = true) { stateDescription = state },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = header,
                style = MaterialTheme.typography.titleSmall,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.textSecondary,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                marks.waiting.forEach { progress ->
                    Text(
                        text = pluralStringResource(
                            if (progress.shortOnNightsWith) R.plurals.progress_marks_waiting else R.plurals.progress_marks_waiting_without,
                            progress.needed,
                            progress.mark.label(),
                            progress.nights,
                            progress.needed,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}

/** "Nights after alcohol", "Nights away from home"; null for Night steps done, which Progress never lists. */
@StringRes
private fun Mark.nightsRes(): Int? = when (this) {
    Mark.LATE_CAFFEINE -> R.string.progress_mark_nights_late_caffeine
    Mark.ALCOHOL -> R.string.progress_mark_nights_alcohol
    Mark.LATE_MEAL -> R.string.progress_mark_nights_late_meal
    Mark.LATE_WORKOUT -> R.string.progress_mark_nights_late_workout
    Mark.STRESS -> R.string.progress_mark_nights_stress
    Mark.SCREENS_IN_BED -> R.string.progress_mark_nights_screens_in_bed
    Mark.AWAY_FROM_HOME -> R.string.progress_mark_nights_away_from_home
    Mark.UNWELL -> R.string.progress_mark_nights_unwell
    Mark.HUSH_USED -> R.string.progress_mark_nights_hush_used
    Mark.ANCHOR_DROPPED -> R.string.progress_mark_nights_anchor_dropped
    Mark.NIGHT_STEPS_DONE -> null
}
