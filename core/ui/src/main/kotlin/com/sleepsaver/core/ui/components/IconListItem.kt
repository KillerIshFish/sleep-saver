package com.sleepsaver.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.ui.theme.SleepSaverTheme

/**
 * A list row: [icon] in a tinted circle, [title], optional [supporting] line and [trailing] content
 * (a time, a switch). Use it for habits, phases and settings. Tappable when [onClick] is set.
 */
@Composable
fun IconListItem(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp, end = if (trailing != null) 12.dp else 0.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepSaverTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        trailing?.invoke()
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun IconListItemPreview() = SleepSaverTheme {
    IconListItem(
        icon = HabitType.CAFFEINE_CUTOFF.icon,
        title = HabitType.CAFFEINE_CUTOFF.title(),
        supporting = HabitType.CAFFEINE_CUTOFF.description(),
        modifier = Modifier.padding(16.dp),
        trailing = {
            Switch(
                checked = true,
                onCheckedChange = {},
                colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
            )
        },
    )
}
