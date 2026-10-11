package com.sleepsaver.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing

/**
 * "How do you feel?" in five words, foggy to bright: Foggy / Slow / Okay / Clear / Bright. One tap picks one (radio
 * buttons); [value] null means not answered yet. Side by side normally, stacked full width from a 150 % system font.
 * Words only: no faces, no batteries.
 */
@Composable
fun MorningFeelScale(
    value: MorningFeel?,
    onValueChange: (MorningFeel) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (stackForLargeFont()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            MorningFeel.entries.forEach { feel ->
                FeelPill(feel, feel == value, onClick = { onValueChange(feel) }, modifier = Modifier.fillMaxWidth())
            }
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            MorningFeel.entries.forEach { feel ->
                FeelPill(feel, feel == value, onClick = { onValueChange(feel) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FeelPill(feel: MorningFeel, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val tide = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else SleepSaverTheme.colors.cardRaised)
            .border(1.5.dp, if (selected) tide else Color.Transparent, CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            feel.label(),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) tide else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun MorningFeelScalePreview() = SleepSaverTheme {
    MorningFeelScale(value = MorningFeel.CLEAR, onValueChange = {}, modifier = Modifier.padding(16.dp))
}
