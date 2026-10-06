package com.sleepsaver.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.theme.SleepSaverTheme

/**
 * A calm, centred explanation with up to two actions, for "Health Connect is missing", "permission denied",
 * "no sleep data yet" and similar. Actions show only when both their label and callback are set.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val tide = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .background(Brush.radialGradient(listOf(tide.copy(alpha = 0.16f), Color.Transparent)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tide, modifier = Modifier.size(34.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(max = 340.dp)
                .semantics { heading() },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = SleepSaverTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 340.dp),
        )
        val hasPrimary = primaryActionLabel != null && onPrimaryAction != null
        val hasSecondary = secondaryActionLabel != null && onSecondaryAction != null
        if (hasPrimary || hasSecondary) Spacer(Modifier.height(28.dp))
        if (hasPrimary) {
            PrimaryButton(primaryActionLabel!!, onClick = onPrimaryAction!!, modifier = Modifier.fillMaxWidth())
        }
        if (hasSecondary) {
            if (hasPrimary) Spacer(Modifier.height(10.dp))
            SecondaryButton(secondaryActionLabel!!, onClick = onSecondaryAction!!, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun EmptyStatePreview() = SleepSaverTheme {
    EmptyState(
        icon = Icons.Rounded.HealthAndSafety,
        title = "Health Connect is needed",
        body = "Sleep Saver reads your Garmin sleep through Health Connect. Install it to continue.",
        primaryActionLabel = "Get Health Connect",
        onPrimaryAction = {},
        secondaryActionLabel = "Try demo data",
        onSecondaryAction = {},
        modifier = Modifier.padding(24.dp),
    )
}
