package com.sleepsaver.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.R
import com.sleepsaver.core.ui.format.formatDuration
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import java.time.Duration

/**
 * "− 45m +": edits a number of minutes in [step]s within [min]..[max] (awake time, nap length, need).
 * When [max] is below [min], [min] wins. [format] renders the value; the default is [formatDuration].
 */
@Composable
fun MinuteStepper(
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 5,
    min: Int = 0,
    max: Int = 24 * 60,
    format: (Int) -> String = { formatDuration(Duration.ofMinutes(it.toLong())) },
) {
    // coerceIn throws on an empty range (e.g. a max computed from a window that shrank to nothing).
    val upper = max.coerceAtLeast(min)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        StepButton(
            icon = Icons.Rounded.Remove,
            description = stringResource(R.string.ss_decrease),
            enabled = minutes > min,
            onClick = { onMinutesChange((minutes - step).coerceIn(min, upper)) },
        )
        Text(
            text = format(minutes),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Light),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(min = 128.dp)
                .padding(horizontal = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        StepButton(
            icon = Icons.Rounded.Add,
            description = stringResource(R.string.ss_increase),
            enabled = minutes < upper,
            onClick = { onMinutesChange((minutes + step).coerceIn(min, upper)) },
        )
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .background(SleepSaverTheme.colors.cardRaised, CircleShape),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = SleepSaverTheme.colors.textTertiary.copy(alpha = 0.5f),
        ),
    ) {
        Icon(icon, contentDescription = description)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun MinuteStepperPreview() = SleepSaverTheme {
    Box(Modifier.padding(16.dp)) {
        MinuteStepper(minutes = 45, onMinutesChange = {})
    }
}
