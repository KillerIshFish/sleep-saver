package com.sleepsaver.feature.review.morning

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.model.AwakePeriod
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The night as a gentle arc: it rises from a crescent moon (fell asleep) and settles at a small sun (woke up),
 * dipping wherever you were awake. Decorative, so it gets one plain [description] for screen readers.
 */
@Composable
internal fun NightArc(
    fellAsleep: Instant,
    wokeUp: Instant,
    awake: List<AwakePeriod>,
    description: String,
    modifier: Modifier = Modifier,
) {
    val tide = MaterialTheme.colorScheme.primary
    val dusk = SleepSaverTheme.colors.melatonin
    val moon = MaterialTheme.colorScheme.onSurface
    val baseline = SleepSaverTheme.colors.cardRaised
    val star = SleepSaverTheme.colors.textTertiary
    val spanMs = Duration.between(fellAsleep, wokeUp).toMillis().coerceAtLeast(1L).toFloat()
    // Each awake stretch as (centre, half width, depth) on the 0..1 night axis.
    val dips = awake.mapNotNull { period ->
        val start = Duration.between(fellAsleep, period.start).toMillis() / spanMs
        val end = Duration.between(fellAsleep, period.end).toMillis() / spanMs
        if (end <= 0f || start >= 1f) return@mapNotNull null
        val minutes = period.duration.toMinutes().toFloat()
        Dip(
            center = ((start + end) / 2f).coerceIn(0f, 1f),
            halfWidth = max((end - start) / 2f, MIN_DIP_HALF_WIDTH),
            depth = 0.35f + 0.4f * min(minutes / 60f, 1f),
        )
    }

    Canvas(modifier.semantics { contentDescription = description }) {
        val pad = 24.dp.toPx()
        val x0 = pad
        val x1 = size.width - pad
        val base = size.height - 18.dp.toPx()
        val amplitude = base - 22.dp.toPx()

        val points = (0..SAMPLES).map { i ->
            val t = i / SAMPLES.toFloat()
            val dip = dips.maxOfOrNull { it.at(t) } ?: 0f
            val lift = sin(PI * t).toFloat().pow(0.8f) * (1f - dip)
            Offset(x0 + (x1 - x0) * t, base - amplitude * lift)
        }
        val curve = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }

        drawStars(points, star)

        // Soft wash under the arc.
        val fill = Path().apply {
            addPath(curve)
            lineTo(x1, base)
            lineTo(x0, base)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(tide.copy(alpha = 0.20f), Color.Transparent), startY = base - amplitude, endY = base))
        drawLine(baseline, Offset(x0, base), Offset(x1, base), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        drawPath(
            curve,
            Brush.horizontalGradient(listOf(dusk, tide), startX = x0, endX = x1),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        drawMoon(Offset(x0, base), dusk, moon)
        drawSun(Offset(x1, base), tide)
    }
}

private class Dip(val center: Float, val halfWidth: Float, val depth: Float) {
    /** 1 inside the stretch, easing to 0 just outside it, times [depth]. */
    fun at(t: Float): Float {
        val d = abs(t - center)
        if (d <= halfWidth) return depth
        val x = ((d - halfWidth) / DIP_FALLOFF).coerceIn(0f, 1f)
        val eased = 0.5f + 0.5f * cos(PI * x).toFloat()
        return depth * eased
    }
}

/** A few faint stars above the first half of the arc. Fixed positions, so the picture is stable. */
private fun DrawScope.drawStars(curve: List<Offset>, color: Color) {
    STARS.forEach { (fx, fy, r) ->
        val x = size.width * fx
        val y = size.height * fy
        val below = curve.minByOrNull { abs(it.x - x) }?.y ?: size.height
        if (y < below - 12.dp.toPx()) drawCircle(color.copy(alpha = 0.55f), radius = r.dp.toPx(), center = Offset(x, y))
    }
}

private fun DrawScope.drawMoon(center: Offset, glow: Color, body: Color) {
    val r = 9.dp.toPx()
    drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.35f), Color.Transparent), center, r * 2.4f), r * 2.4f, center)
    val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, r)) }
    val bite = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(Offset(center.x + r * 0.55f, center.y - r * 0.45f), r * 0.9f))
    }
    drawPath(Path.combine(PathOperation.Difference, disc, bite), body)
}

private fun DrawScope.drawSun(center: Offset, color: Color) {
    val r = 6.dp.toPx()
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent), center, r * 3.4f), r * 3.4f, center)
    drawCircle(color, r, center)
    val inner = r + 3.dp.toPx()
    val outer = r + 6.5.dp.toPx()
    for (i in 0 until 8) {
        val a = i * PI / 4
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        drawLine(
            color,
            Offset(center.x + dx * inner, center.y + dy * inner),
            Offset(center.x + dx * outer, center.y + dy * outer),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private const val SAMPLES = 180
private const val MIN_DIP_HALF_WIDTH = 0.018f
private const val DIP_FALLOFF = 0.05f

/** (x fraction, y fraction, radius dp). */
private val STARS = listOf(
    Triple(0.08f, 0.16f, 1.4f),
    Triple(0.16f, 0.40f, 1.0f),
    Triple(0.24f, 0.08f, 1.2f),
    Triple(0.31f, 0.27f, 1.6f),
    Triple(0.40f, 0.05f, 1.0f),
    Triple(0.86f, 0.12f, 1.1f),
    Triple(0.93f, 0.34f, 1.3f),
)
