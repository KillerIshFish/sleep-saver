package com.sleepsaver.feature.review.morning

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WbSunny
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.journal.FeelSource
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.journal.MorningFeelEntry
import com.sleepsaver.core.domain.journal.TomorrowList
import com.sleepsaver.core.domain.patterns.OddNight
import com.sleepsaver.core.domain.patterns.OddWay
import com.sleepsaver.core.ui.components.MarkChips
import com.sleepsaver.core.ui.components.MorningFeelScale
import com.sleepsaver.core.ui.components.SectionCard
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.label
import com.sleepsaver.core.ui.format.formatDuration
import com.sleepsaver.core.ui.format.formatNightLabel
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.review.R
import com.sleepsaver.feature.review.common.alignTextStart
import java.time.Duration
import java.time.LocalDate

/**
 * Morning feel at the top of the summary. Unanswered (or answered here before): "How do you feel this morning?" with
 * the five words. Answered on the ringing screen: a small chip "Morning feel: Clear · Change" that opens the scale,
 * and a new pick is saved over the old one. Once [editable] ends the chip is read-only, and an unanswered morning
 * shows nothing.
 */
@Composable
internal fun MorningFeelCard(
    feel: MorningFeelEntry?,
    editable: Boolean,
    onFeel: (MorningFeel) -> Unit,
    modifier: Modifier = Modifier,
) {
    var changing by rememberSaveable { mutableStateOf(false) }
    val showScale = editable && (feel == null || feel.source == FeelSource.REVIEW || changing)
    when {
        showScale -> SleepSaverCard(modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.review_feel_question),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            MorningFeelScale(
                value = feel?.feel,
                onValueChange = onFeel,
                modifier = Modifier.padding(top = Spacing.m),
            )
        }
        feel != null -> FeelChip(
            text = if (editable) {
                stringResource(R.string.review_feel_chip_change, feel.feel.label())
            } else {
                stringResource(R.string.review_feel_chip, feel.feel.label())
            },
            onClick = if (editable) ({ changing = true }) else null,
            modifier = modifier,
        )
    }
}

/** A pill like InfoChip, but its text may wrap at large font sizes. */
@Composable
private fun FeelChip(text: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val changeLabel = stringResource(R.string.review_feel_change)
    Row(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(SleepSaverTheme.colors.cardRaised)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = changeLabel, role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.WbSunny,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * "Anything different last night?": how the night differed from the usual (good or bad), the marks to note why,
 * and "Skip".
 */
@Composable
internal fun OddNightCard(
    oddNight: OddNight,
    marks: Set<Mark>,
    marksEditable: Boolean,
    onMarkToggle: (Mark) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Less padding under "Skip": its 48 dp touch target already leaves room.
    SleepSaverCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
        Text(
            text = stringResource(R.string.review_odd_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = oddNight.ways.sortedBy { it.ordinal }.map { way -> oddLine(way, oddNight) }.joinToString(" "),
            style = MaterialTheme.typography.bodyMedium,
            color = SleepSaverTheme.colors.textSecondary,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.m),
        )
        MarkChips(selected = marks, onToggle = onMarkToggle, enabled = marksEditable)
        TextLinkButton(
            text = stringResource(R.string.review_odd_skip),
            onClick = onSkip,
            color = SleepSaverTheme.colors.textSecondary,
            modifier = Modifier.alignTextStart(),
        )
    }
}

/** One line of the odd-night body; total sleep says by how much ("You slept about 1h 10m less than usual."). */
@Composable
private fun oddLine(way: OddWay, oddNight: OddNight): String {
    val amount = oddNight.totalDelta.abs().takeIf { it >= Duration.ofMinutes(1) }?.let { formatDuration(it).replace(' ', '\u00A0') }
    return when {
        amount != null && way == OddWay.MORE_SLEEP -> stringResource(R.string.review_odd_more_sleep_by, amount)
        amount != null && way == OddWay.LESS_SLEEP -> stringResource(R.string.review_odd_less_sleep_by, amount)
        else -> stringResource(way.lineRes())
    }
}

private fun OddWay.lineRes(): Int = when (this) {
    OddWay.MORE_SLEEP -> R.string.review_odd_more_sleep
    OddWay.LESS_SLEEP -> R.string.review_odd_less_sleep
    OddWay.MORE_AWAKE -> R.string.review_odd_more_awake
    OddWay.EARLIER_ONSET -> R.string.review_odd_earlier
    OddWay.LATER_ONSET -> R.string.review_odd_later
}

/** "Your list from last night", one line per item. */
@Composable
internal fun TomorrowListCard(list: TomorrowList, modifier: Modifier = Modifier) {
    SectionCard(title = stringResource(R.string.review_tomorrow_list_title), modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            list.items.forEach { item ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = Spacing.m),
                    )
                    Text(item, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** "Last night’s marks" (or "Saturday night’s marks"): the eight marks to tick, read-only once the night is old. */
@Composable
internal fun NightMarksCard(
    wakeDate: LocalDate,
    today: LocalDate,
    marks: Set<Mark>,
    editable: Boolean,
    onMarkToggle: (Mark) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = formatNightLabel(wakeDate, today)
    val title = if (label.endsWith(" night")) {
        stringResource(R.string.review_marks_title_named, label)
    } else {
        stringResource(R.string.review_marks_title)
    }
    SectionCard(title = title, modifier = modifier.fillMaxWidth()) {
        MarkChips(selected = marks, onToggle = onMarkToggle, enabled = editable)
    }
}
