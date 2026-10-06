package com.sleepsaver.core.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.R
import com.sleepsaver.core.ui.theme.SleepSaverTheme

/**
 * A slowly breathing tide ring for syncing / recalculating, with an optional [label] underneath
 * ("Syncing with Health Connect…"). Use it instead of a spinner.
 */
@Composable
fun LoadingOrb(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    label: String? = null,
) {
    val tide = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "orb")
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val ripple by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
        label = "ripple",
    )
    val description = label ?: stringResource(R.string.ss_loading)
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2
            val stroke = r * 0.07f
            // An outer ring drifting outwards and fading.
            drawCircle(
                color = tide.copy(alpha = 0.35f * (1f - ripple)),
                radius = r * (0.62f + 0.36f * ripple),
                style = Stroke(width = stroke * (1f - 0.5f * ripple)),
            )
            // Soft core.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tide.copy(alpha = 0.30f + 0.15f * breath), Color.Transparent),
                    center = center,
                    radius = r * 0.62f,
                ),
                radius = r * 0.62f,
            )
            // The main ring breathes in size and brightness.
            drawCircle(
                color = tide.copy(alpha = 0.65f + 0.35f * breath),
                radius = r * (0.48f + 0.06f * breath),
                style = Stroke(width = stroke),
            )
        }
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = SleepSaverTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun LoadingOrbPreview() = SleepSaverTheme {
    LoadingOrb(label = "Syncing with Health Connect…", modifier = Modifier.padding(24.dp))
}
