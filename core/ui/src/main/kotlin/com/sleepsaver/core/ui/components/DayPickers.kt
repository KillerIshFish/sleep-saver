package com.sleepsaver.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.format.formatDayLong
import com.sleepsaver.core.ui.format.formatDayNarrow
import com.sleepsaver.core.ui.format.formatDayShort
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import java.time.DayOfWeek

private val MinDay = 40.dp
private val MaxDay = 48.dp
private val DayGap = 6.dp

/** Day letters and names sit in fixed shapes: they follow large fonts up to this scale. */
private const val MAX_DAY_FONT_SCALE = 1.3f

/**
 * Pick any days of the week, Monday first: seven equal circles ("M T W T F S S"), 40–48 dp each to fit the width
 * (needs 7 × 40 dp plus gaps, about 316 dp). Each is a checkbox named after the whole day ("Monday", checked).
 * Example: the "Copy to…" dialog's target days.
 */
@Composable
fun DayChipRow(
    selected: Set<DayOfWeek>,
    onToggle: (DayOfWeek) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Days shown but not pickable (e.g. the day being copied from). */
    disabledDays: Set<DayOfWeek> = emptySet(),
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val size = ((maxWidth - DayGap * (DayOfWeek.entries.size - 1)) / DayOfWeek.entries.size).coerceIn(MinDay, MaxDay)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DayOfWeek.entries.forEach { day ->
                DayCircle(day, day in selected, onToggle = { onToggle(day) }, enabled = enabled && day !in disabledDays, modifier = Modifier.size(size))
            }
        }
    }
}

@Composable
private fun DayCircle(day: DayOfWeek, selected: Boolean, onToggle: () -> Unit, enabled: Boolean, modifier: Modifier) {
    val tide = MaterialTheme.colorScheme.primary
    val name = formatDayLong(day)
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else DisabledAlpha)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else SleepSaverTheme.colors.cardRaised)
            .border(1.5.dp, if (selected) tide else Color.Transparent, CircleShape)
            .toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            formatDayNarrow(day),
            style = MaterialTheme.typography.labelLarge.maxFontScale(MAX_DAY_FONT_SCALE),
            color = if (selected) tide else SleepSaverTheme.colors.textSecondary,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * One day of the week at a time, Monday first: seven tabs "Mon … Sun" in a pill, the [selected] one raised. A small
 * tide dot sits under each [marked] day (the days with an alarm). Each tab is named after the whole day ("Tuesday").
 */
@Composable
fun DayTabs(
    selected: DayOfWeek,
    onSelect: (DayOfWeek) -> Unit,
    modifier: Modifier = Modifier,
    marked: Set<DayOfWeek> = emptySet(),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(SleepSaverTheme.colors.card)
            .padding(4.dp)
            .selectableGroup(),
    ) {
        DayOfWeek.entries.forEach { day ->
            DayTab(day, day == selected, day in marked, onClick = { onSelect(day) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun DayTab(day: DayOfWeek, selected: Boolean, marked: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val name = formatDayLong(day)
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .then(
                if (selected) {
                    Modifier
                        .background(SleepSaverTheme.colors.cardRaised)
                        .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                } else {
                    Modifier
                },
            )
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = name }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            formatDayShort(day),
            style = MaterialTheme.typography.labelLarge.maxFontScale(MAX_DAY_FONT_SCALE),
            color = if (selected) MaterialTheme.colorScheme.onSurface else SleepSaverTheme.colors.textSecondary,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(if (marked) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun DayPickersPreview() = SleepSaverTheme {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        DayChipRow(
            selected = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            onToggle = {},
        )
        DayTabs(
            selected = DayOfWeek.TUESDAY,
            onSelect = {},
            marked = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        )
    }
}
