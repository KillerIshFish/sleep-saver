package com.sleepsaver.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing

/**
 * The app's switch: a tide track when on, a grey one when off. Pass `onCheckedChange = null` when the whole row
 * around it toggles (see [ToggleRow]), so the switch only shows the state.
 */
@Composable
fun SleepSaverSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tide = MaterialTheme.colorScheme.primary
    val colors = SleepSaverTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = tide,
            checkedBorderColor = tide,
            uncheckedThumbColor = colors.textSecondary,
            uncheckedTrackColor = colors.cardRaised,
            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            disabledCheckedThumbColor = MaterialTheme.colorScheme.onPrimary,
            disabledCheckedTrackColor = tide.copy(alpha = DisabledAlpha),
            disabledCheckedBorderColor = tide.copy(alpha = 0f),
            disabledUncheckedThumbColor = colors.textTertiary,
            disabledUncheckedTrackColor = colors.card,
            disabledUncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

/**
 * A row with a switch where the whole row toggles it: "Night steps" with an optional [supporting] line ("Log
 * movement all night · Uses more battery.") and [icon]. A switch to screen readers. It adds no side padding: give it
 * the padding of the card or screen it sits in.
 */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val tint = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .padding(end = 14.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.14f))
                    .alpha(if (enabled) 1f else DisabledAlpha),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(end = Spacing.m),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else SleepSaverTheme.colors.textTertiary,
            )
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepSaverTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // The row is the toggle; the switch only shows its state.
        SleepSaverSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun TogglesPreview() = SleepSaverTheme {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        ToggleRow("Night steps", checked = true, onCheckedChange = {})
        ToggleRow(
            "Hush",
            checked = false,
            onCheckedChange = {},
            supporting = "Low, steady noise with a timer.",
            icon = Icons.Rounded.GraphicEq,
        )
        SleepSaverSwitch(checked = true, onCheckedChange = {})
    }
}
