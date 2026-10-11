package com.sleepsaver.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing

/**
 * The night's marks as chips that wrap onto more lines: "Late caffeine", "Stress", "Away from home" …, each with its
 * icon and ticked when in [selected]. [marks] defaults to the eight the user sets (`Mark.stored`); pass a list with
 * derived ones to show those too. [enabled] = false shows them read-only (older than the edit window).
 */
@Composable
fun MarkChips(
    selected: Set<Mark>,
    onToggle: (Mark) -> Unit,
    modifier: Modifier = Modifier,
    marks: List<Mark> = Mark.stored,
    enabled: Boolean = true,
) {
    // Each chip's 48 dp touch target already leaves 12 dp between rows.
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        marks.forEach { mark ->
            SelectableChip(
                text = mark.label(),
                selected = mark in selected,
                onClick = { onToggle(mark) },
                icon = mark.icon,
                enabled = enabled,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun MarkChipsPreview() = SleepSaverTheme {
    MarkChips(selected = setOf(Mark.LATE_CAFFEINE, Mark.STRESS), onToggle = {}, modifier = Modifier.padding(16.dp))
}
