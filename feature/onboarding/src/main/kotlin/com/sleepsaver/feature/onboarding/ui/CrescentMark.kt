package com.sleepsaver.feature.onboarding.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.theme.SleepSaverTheme

/**
 * The app's mark: a tide crescent with a small four-point star in its opening, on a soft glow.
 * Drawn, not a bitmap, so it stays crisp at any size. [glow] = false for small inline uses.
 */
@Composable
internal fun CrescentMark(modifier: Modifier = Modifier, size: Dp = 128.dp, glow: Boolean = true) {
    val tide = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(size)) { drawCrescent(tide, glow) }
}

private fun DrawScope.drawCrescent(tide: Color, glow: Boolean) {
    val s = size.minDimension
    val origin = Offset((size.width - s) / 2, (size.height - s) / 2)
    fun at(x: Float, y: Float) = origin + Offset(x * s, y * s)

    if (glow) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(tide.copy(alpha = 0.22f), tide.copy(alpha = 0.06f), Color.Transparent),
                center = at(0.5f, 0.5f),
                radius = s / 2,
            ),
            radius = s / 2,
            center = at(0.5f, 0.5f),
        )
    }

    // The moon: a disc minus a shifted disc, lit from the lower left.
    val r = s * 0.31f
    val moonCenter = at(0.46f, 0.53f)
    val moon = Path().apply { addOval(Rect(moonCenter, r)) }
    val shadow = Path().apply { addOval(Rect(moonCenter + Offset(r * 0.62f, -r * 0.42f), r * 0.86f)) }
    val crescent = Path().apply { op(moon, shadow, PathOperation.Difference) }
    drawPath(
        path = crescent,
        brush = Brush.linearGradient(
            colors = listOf(tide, tide.copy(alpha = 0.72f)),
            start = moonCenter + Offset(-r, r),
            end = moonCenter + Offset(r, -r),
        ),
    )

    // A four-point star in the crescent's opening, and a smaller one further out.
    drawPath(sparkle(at(0.69f, 0.36f), s * 0.085f), tide)
    drawPath(sparkle(at(0.80f, 0.55f), s * 0.04f), tide.copy(alpha = 0.7f))
}

/** A four-point star whose sides curve in towards [center]. */
private fun sparkle(center: Offset, radius: Float): Path = Path().apply {
    val (x, y) = center
    val pinch = radius * 0.18f
    moveTo(x, y - radius)
    quadraticTo(x + pinch, y - pinch, x + radius, y)
    quadraticTo(x + pinch, y + pinch, x, y + radius)
    quadraticTo(x - pinch, y + pinch, x - radius, y)
    quadraticTo(x - pinch, y - pinch, x, y - radius)
    close()
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun CrescentMarkPreview() = SleepSaverTheme { CrescentMark() }
