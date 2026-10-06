package com.sleepsaver.core.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.ui.R
import com.sleepsaver.core.ui.format.formatHours1
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import java.time.Duration
import kotlin.math.cos
import kotlin.math.sin

/** The ring is open at the bottom; this is the angle the open gap spans, centred at 6 o'clock. */
private const val GAP_DEGREES = 76f
private const val TRACK_DEGREES = 360f - GAP_DEGREES
private const val START_DEGREES = 90f + GAP_DEGREES / 2

/** The largest system font scale the gauge's labels follow; beyond it they would run over the ring. */
private const val MAX_LABEL_FONT_SCALE = 1.3f

/**
 * The home screen's debt ring: an open ring whose sweep is proportional to [debt] (full at [fullScale]),
 * coloured by [status] (tide, amber, coral) with a soft gradient and glow. The centre shows the debt in hours
 * ("3.7") over [label]; the status name ("Moderate") sits in the ring's opening when [showStatus].
 * The sweep and colour animate when the values change.
 */
@Composable
fun DebtGauge(
    debt: Duration,
    status: DebtStatus,
    modifier: Modifier = Modifier,
    size: Dp = 264.dp,
    fullScale: Duration = Duration.ofHours(12),
    label: String = stringResource(R.string.ss_debt_gauge_label),
    showStatus: Boolean = true,
) {
    val fraction = (debt.toMillis().toFloat() / fullScale.toMillis()).coerceIn(0f, 1f)
    val sweep by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "debtSweep",
    )
    val color by animateColorAsState(status.color, tween(600), label = "debtColor")
    val track = SleepSaverTheme.colors.cardRaised
    val hours = formatHours1(debt)
    val statusLabel = status.label()
    val description = stringResource(R.string.ss_debt_gauge_description, hours, statusLabel)
    // The ring has a fixed size, so its text can't follow large system font sizes all the way without spilling
    // over it: the number (already huge) stays put and the small labels grow up to MAX_LABEL_FONT_SCALE.
    val density = LocalDensity.current
    val labelScale = (MAX_LABEL_FONT_SCALE / density.fontScale).coerceAtMost(1f)

    Box(
        modifier = modifier
            .size(size)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) { drawGauge(sweep, color, track) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = hours,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = with(density) { (size * 0.32f).toSp() },
                    lineHeight = with(density) { (size * 0.34f).toSp() },
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.scaled(labelScale),
                color = SleepSaverTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (showStatus) {
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelLarge.scaled(labelScale),
                color = color,
                // Centred in the ring's opening, on the circle's bottom edge.
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = size * 0.39f),
            )
        }
    }
}

private fun TextStyle.scaled(factor: Float): TextStyle =
    if (factor >= 1f) {
        this
    } else {
        copy(
            fontSize = fontSize * factor,
            lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight,
        )
    }

private fun DrawScope.drawGauge(fraction: Float, color: Color, track: Color) {
    val stroke = size.minDimension * 0.055f
    // Room for the glow around the ring.
    val inset = stroke * 1.6f
    val diameter = size.minDimension - inset * 2
    val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
    val arcSize = Size(diameter, diameter)
    val center = Offset(size.width / 2, size.height / 2)

    // A faint wash of the status colour inside the ring.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.10f), Color.Transparent),
            center = center,
            radius = diameter / 2,
        ),
        radius = diameter / 2,
        center = center,
    )

    drawArc(
        color = track,
        startAngle = START_DEGREES,
        sweepAngle = TRACK_DEGREES,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )

    val sweep = TRACK_DEGREES * fraction
    if (sweep > 0.5f) {
        drawGlowArc(topLeft, arcSize, sweep, stroke, color)
        // The gradient runs from a dim tint at the start to the full colour at the tip. The sweep gradient starts
        // at 3 o'clock, so draw rotated to the arc's start; the last stop repeats the start colour so the start
        // cap (which reaches just below 0°) stays dim.
        val dim = lerp(track, color, 0.35f)
        rotate(START_DEGREES, pivot = center) {
            drawArc(
                brush = Brush.sweepGradient(
                    0f to dim,
                    (sweep / 360f) to color,
                    1f to dim,
                    center = center,
                ),
                startAngle = 0f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }

    // A bright dot marks the tip (or the start, at zero debt).
    val tipAngle = Math.toRadians((START_DEGREES + sweep).toDouble())
    val radius = diameter / 2
    val tip = Offset(center.x + radius * cos(tipAngle).toFloat(), center.y + radius * sin(tipAngle).toFloat())
    drawCircle(color = color.copy(alpha = 0.35f), radius = stroke * 0.9f, center = tip)
    drawCircle(color = Color.White, radius = stroke * 0.3f, center = tip)
}

private fun DrawScope.drawGlowArc(topLeft: Offset, arcSize: Size, sweep: Float, stroke: Float, color: Color) {
    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            style = PaintingStyle.Stroke
            strokeWidth = stroke * 1.4f
            strokeCap = StrokeCap.Round
        }
        paint.asFrameworkPaint().apply {
            isAntiAlias = true
            this.color = color.copy(alpha = 0.55f).toArgb()
            maskFilter = BlurMaskFilter(stroke * 1.2f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawArc(
            left = topLeft.x,
            top = topLeft.y,
            right = topLeft.x + arcSize.width,
            bottom = topLeft.y + arcSize.height,
            startAngle = START_DEGREES,
            sweepAngle = sweep,
            useCenter = false,
            paint = paint,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun DebtGaugePreview() = SleepSaverTheme {
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        DebtGauge(Duration.ofMinutes(222), DebtStatus.MODERATE)
    }
}
