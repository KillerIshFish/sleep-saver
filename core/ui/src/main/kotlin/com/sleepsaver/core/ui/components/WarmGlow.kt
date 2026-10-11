package com.sleepsaver.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.sleepsaver.core.ui.theme.SleepSaverTheme

/** The glow's colour strength at its centre. */
private const val GLOW_ALPHA = 0.35f

/**
 * The ringing screen's background: Ink with a subtle warm radial glow ([SleepSaverTheme.colors] `ringGlow` at 35 %)
 * centred a little above the middle, behind the time, fading to plain Ink at the edges. It stays dark: the only
 * non-grey surface in the app. Fill the screen with it behind the content:
 * `Box { WarmGlow(Modifier.matchParentSize()); RingingContent(...) }`.
 */
@Composable
fun WarmGlow(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.background
    val glow = SleepSaverTheme.colors.ringGlow.copy(alpha = GLOW_ALPHA)
    Canvas(modifier) {
        drawRect(ink)
        val center = Offset(size.width / 2, size.height * 0.38f)
        val radius = maxOf(size.width, size.height) * 0.6f
        if (radius > 0f) {
            drawRect(Brush.radialGradient(listOf(glow, Color.Transparent), center = center, radius = radius))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10, widthDp = 411, heightDp = 914)
@Composable
private fun WarmGlowPreview() = SleepSaverTheme {
    WarmGlow(Modifier.fillMaxSize())
}
