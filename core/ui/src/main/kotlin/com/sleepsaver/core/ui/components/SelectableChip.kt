package com.sleepsaver.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing

private val ChipHeight = 36.dp
private val SelectedBorder = 1.5.dp

/** How faded a disabled choice is. */
internal const val DisabledAlpha = 0.4f

/**
 * A pill that switches on and off: a mark, a timer, a noise colour ("Late caffeine", "45 min", "Pink"). Selected,
 * it turns tide-tinted with a 1.5 dp tide border and a check (in place of [icon]). 36 dp tall inside a 48 dp touch
 * target; a checkbox to screen readers. A disabled chip is faded and ignores taps ("Until my window" with no alarm).
 */
@Composable
fun SelectableChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val tide = MaterialTheme.colorScheme.primary
    val leading = if (selected) Icons.Rounded.Check else icon
    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .alpha(if (enabled) 1f else DisabledAlpha)
            .heightIn(min = ChipHeight)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else SleepSaverTheme.colors.cardRaised)
            .border(SelectedBorder, if (selected) tide else Color.Transparent, CircleShape)
            .toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
            // The vertical padding only matters at large font sizes, where the label outgrows 36 dp.
            .padding(start = if (leading != null) 10.dp else 14.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) {
            Icon(
                leading,
                contentDescription = null,
                tint = if (selected) tide else SleepSaverTheme.colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun SelectableChipPreview() = SleepSaverTheme {
    FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SelectableChip("Late caffeine", selected = true, onClick = {}, icon = Icons.Rounded.LocalCafe)
        SelectableChip("45 min", selected = false, onClick = {})
        SelectableChip("Until my window", selected = false, onClick = {}, enabled = false)
    }
}
