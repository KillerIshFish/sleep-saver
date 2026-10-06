package com.sleepsaver.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.R
import com.sleepsaver.core.ui.format.formatDebtChange
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import java.time.Duration

private val ChipHeight = 36.dp

/**
 * A small pill with an optional icon: "7h 41m last night", "Garmin", "+ Nap".
 * Tappable (with a ripple) when [onClick] is set.
 */
@Composable
fun InfoChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = SleepSaverTheme.colors.textSecondary,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .heightIn(min = ChipHeight)
            .clip(CircleShape)
            .background(SleepSaverTheme.colors.cardRaised)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = if (icon != null) 12.dp else 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/**
 * Debt change since yesterday: "↓ −0.2 hrs since yesterday". A decrease is tinted tide, an increase coral,
 * and a change that rounds to 0.0 stays grey. Pass `suffix = null` to drop "since yesterday".
 */
@Composable
fun ChangeChip(
    change: Duration,
    modifier: Modifier = Modifier,
    suffix: String? = stringResource(R.string.ss_change_since_yesterday),
) {
    val tenths = change.toMillis() / 360_000.0
    val (tint, arrow) = when {
        tenths <= -0.5 -> SleepSaverTheme.colors.debtLow to Icons.Rounded.ArrowDownward
        tenths >= 0.5 -> SleepSaverTheme.colors.debtHigh to Icons.Rounded.ArrowUpward
        else -> SleepSaverTheme.colors.textSecondary to Icons.Rounded.Remove
    }
    Row(
        modifier = modifier
            .heightIn(min = ChipHeight)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f))
            .padding(start = 10.dp, end = 14.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(arrow, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(
            text = formatDebtChange(change),
            style = MaterialTheme.typography.labelLarge,
            color = tint,
            modifier = Modifier.padding(start = 4.dp),
        )
        if (suffix != null) {
            Text(
                text = suffix,
                style = MaterialTheme.typography.labelMedium,
                color = SleepSaverTheme.colors.textSecondary,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** "12 days steady streak": consecutive days with debt under the streak threshold. */
@Composable
fun StreakBadge(days: Int, modifier: Modifier = Modifier) {
    val tide = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .heightIn(min = ChipHeight)
            .clip(CircleShape)
            .background(SleepSaverTheme.colors.cardRaised)
            .padding(start = 6.dp, end = 14.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Waves,
            contentDescription = null,
            tint = tide,
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(tide.copy(alpha = 0.14f))
                .padding(5.dp),
        )
        Text(
            text = days.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp),
        )
        Text(
            text = pluralStringResource(R.plurals.ss_steady_streak, days),
            style = MaterialTheme.typography.labelMedium,
            color = SleepSaverTheme.colors.textSecondary,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun ChipsPreview() = SleepSaverTheme {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StreakBadge(days = 12)
        ChangeChip(change = Duration.ofMinutes(-12))
        ChangeChip(change = Duration.ofMinutes(66))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoChip("7h 41m last night", icon = Icons.Rounded.Bedtime)
            InfoChip("Garmin", icon = Icons.Rounded.Watch)
            InfoChip("Nap", icon = Icons.Rounded.Add, onClick = {})
        }
    }
}
