package com.sleepsaver.core.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.nightsteps.EvidenceLevel
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing

/**
 * A small outlined label for how well studied a technique is: "Good evidence" in tide, "Some evidence", "Early
 * evidence" and "Little evidence" in neutral grey. No traffic-light colours: weaker evidence isn't a warning.
 */
@Composable
fun EvidenceBadge(level: EvidenceLevel, modifier: Modifier = Modifier) {
    val good = level == EvidenceLevel.GOOD
    val color = if (good) MaterialTheme.colorScheme.primary else SleepSaverTheme.colors.textSecondary
    val outline = if (good) MaterialTheme.colorScheme.primary else SleepSaverTheme.colors.textTertiary
    Row(
        modifier = modifier
            .heightIn(min = 26.dp)
            .border(1.dp, outline, CircleShape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(level.label(), style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun EvidenceBadgePreview() = SleepSaverTheme {
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        EvidenceLevel.entries.forEach { EvidenceBadge(it) }
    }
}
