package com.sleepsaver.feature.tonight.day

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.schedule.DayPart
import com.sleepsaver.core.ui.components.DayChipRow
import com.sleepsaver.core.ui.format.formatDayLong
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.tonight.R
import java.time.DayOfWeek

/**
 * "Copy Tuesday to…": pick days, and whether the morning (wake time and alarm), the evening (bedtime and Night steps)
 * or both go along (both by default). [day] itself can't be picked.
 */
@Composable
internal fun CopyDialog(day: DayOfWeek, onCopy: (Set<DayOfWeek>, Set<DayPart>) -> Unit, onDismiss: () -> Unit) {
    var days by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    var morning by rememberSaveable { mutableStateOf(true) }
    var evening by rememberSaveable { mutableStateOf(true) }
    val targets = days.mapTo(LinkedHashSet()) { DayOfWeek.of(it) } - day
    val parts = buildSet {
        if (morning) add(DayPart.MORNING)
        if (evening) add(DayPart.EVENING)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tonight_copy_title, formatDayLong(day))) },
        text = {
            Column {
                DayChipRow(
                    selected = targets,
                    onToggle = { toggled ->
                        if (toggled != day) {
                            days = if (toggled.value in days) days - toggled.value else days + toggled.value
                        }
                    },
                    // The day being copied from can't be a target.
                    disabledDays = setOf(day),
                )
                Spacer(Modifier.height(Spacing.l))
                CheckRow(stringResource(R.string.tonight_copy_morning), morning) { morning = it }
                CheckRow(stringResource(R.string.tonight_copy_evening), evening) { evening = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCopy(targets, parts) }, enabled = targets.isNotEmpty() && parts.isNotEmpty()) {
                Text(stringResource(R.string.tonight_copy_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.tonight_cancel), color = SleepSaverTheme.colors.textSecondary)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    )
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = Spacing.m),
        )
    }
}
