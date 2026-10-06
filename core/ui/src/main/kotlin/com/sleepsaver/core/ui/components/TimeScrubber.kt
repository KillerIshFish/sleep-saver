package com.sleepsaver.core.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.sleepsaver.core.ui.R
import com.sleepsaver.core.ui.format.LocalUse24HourClock
import com.sleepsaver.core.ui.format.formatClockAxis
import com.sleepsaver.core.ui.format.formatClockTime
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

private val MinuteWidth = 6.dp
private val RulerHeight = 76.dp

/**
 * A horizontal ruler you drag to pick a time, e.g. when you fell asleep or woke up. Shows [value] large above
 * a ruler with minute ticks; dragging (and flinging) moves the ruler under a fixed centre line, snapping to
 * [stepMinutes] on the local clock of [zone] and staying within [min]..[max]. Every snapped change is reported
 * through [onValueChange] (with a light haptic tick when [hapticTicks]); ticks outside the bounds are dimmed.
 * The whole control is draggable, not only the ruler. Screen readers get "earlier"/"later" actions.
 */
@Composable
fun TimeScrubber(
    value: Instant,
    onValueChange: (Instant) -> Unit,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    min: Instant? = null,
    max: Instant? = null,
    stepMinutes: Int = 5,
    label: String? = null,
    hapticTicks: Boolean = true,
) {
    val use24h = LocalUse24HourClock.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val minutePx = with(LocalDensity.current) { MinuteWidth.toPx() }
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    val lower = min?.let { ceil(it.epochSecond / 60.0) } ?: Double.NEGATIVE_INFINITY
    val upper = max?.let { floor(it.epochSecond / 60.0) } ?: Double.POSITIVE_INFINITY

    // While the user drags or the ruler settles, it follows its own position; otherwise it shows [value].
    var dragging by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf(false) }
    val interacting = dragging || settling
    var position by remember { mutableDoubleStateOf(value.epochMinutes()) }
    var lastReported by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var settleJob by remember { mutableStateOf<Job?>(null) }

    fun snap(minutes: Double): Long {
        val offset = zone.offsetAt(minutes)
        val local = (minutes + offset) / stepMinutes
        val snapped = local.roundToLong() * stepMinutes - offset
        return snapped.toDouble().coerceIn(lower, upper).toLong()
    }

    fun report(minutes: Long) {
        if (minutes == lastReported) return
        lastReported = minutes
        if (hapticTicks) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        currentOnValueChange(Instant.ofEpochSecond(minutes * 60))
    }

    fun nudge(steps: Int) {
        val target = snap(currentValue.epochMinutes() + steps * stepMinutes)
        currentOnValueChange(Instant.ofEpochSecond(target * 60))
    }

    val shown: Instant = if (interacting) Instant.ofEpochSecond(snap(position) * 60) else value
    val timeText = formatClockTime(shown, zone, use24h)
    val earlier = stringResource(R.string.ss_time_earlier, stepMinutes)
    val later = stringResource(R.string.ss_time_later, stepMinutes)

    Column(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                if (label != null) contentDescription = label
                stateDescription = timeText
                customActions = listOf(
                    CustomAccessibilityAction(earlier) { nudge(-1); true },
                    CustomAccessibilityAction(later) { nudge(1); true },
                )
            }
            // The whole control scrubs, not just the ruler: the big time is the easiest thing to grab.
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    position = (position - delta / minutePx).coerceIn(lower, upper)
                    report(snap(position))
                },
                onDragStarted = {
                    settleJob?.cancel()
                    if (!interacting) {
                        position = currentValue.epochMinutes()
                        lastReported = position.toLong()
                    }
                    dragging = true
                },
                onDragStopped = { velocity ->
                    dragging = false
                    settling = true
                    settleJob = scope.launch {
                        val fling = decay.calculateTargetValue(0f, -velocity / minutePx)
                        val start = position
                        val target = snap(start + fling)
                        try {
                            animate(
                                initialValue = 0f,
                                targetValue = (target - start).toFloat(),
                                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 200f),
                            ) { v, _ ->
                                position = start + v
                                report(snap(position))
                            }
                            report(target)
                        } finally {
                            // A newer drag may have replaced this settle; only the latest one ends it.
                            if (settleJob === coroutineContext[Job]) settling = false
                        }
                    }
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = SleepSaverTheme.colors.textSecondary)
        }
        Text(
            text = bigTime(timeText),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Ruler(
            center = if (interacting) position else value.epochMinutes(),
            zone = zone,
            lower = lower,
            upper = upper,
            use24h = use24h,
            modifier = Modifier
                .fillMaxWidth()
                .height(RulerHeight),
        )
    }
}

@Composable
private fun Ruler(
    center: Double,
    zone: ZoneId,
    lower: Double,
    upper: Double,
    use24h: Boolean,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val tide = MaterialTheme.colorScheme.primary
    val tick = SleepSaverTheme.colors.textTertiary
    val major = SleepSaverTheme.colors.textSecondary
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = major)
    val halfHourStyle = MaterialTheme.typography.labelSmall.copy(color = tick)
    Canvas(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // Fade both edges out.
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.2f to Color.Black,
                        0.8f to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        val minutePx = MinuteWidth.toPx()
        val top = 10.dp.toPx()
        val mid = size.width / 2
        val halfSpan = mid / minutePx
        val first = floor(center - halfSpan).toLong()
        val last = ceil(center + halfSpan).toLong()
        for (m in first..last) {
            val x = mid + ((m - center) * minutePx).toFloat()
            val local = Math.floorMod(m + zone.offsetAt(m.toDouble()), 60L).toInt()
            val (length, color, width) = when {
                local == 0 -> Triple(30.dp, major, 2.dp)
                local == 30 -> Triple(24.dp, tick, 1.5.dp)
                local % 15 == 0 -> Triple(18.dp, tick, 1.5.dp)
                local % 5 == 0 -> Triple(13.dp, tick, 1.5.dp)
                else -> Triple(7.dp, tick.copy(alpha = 0.45f), 1.dp)
            }
            val inRange = m >= lower && m <= upper
            drawLine(
                color = if (inRange) color else color.copy(alpha = color.alpha * 0.3f),
                start = Offset(x, top),
                end = Offset(x, top + length.toPx()),
                strokeWidth = width.toPx(),
                cap = StrokeCap.Round,
            )
            if (local == 0 || local == 30) {
                val text = formatClockAxis(Instant.ofEpochSecond(m * 60), zone, use24h)
                val style = if (local == 0) labelStyle else halfHourStyle
                val layout = measurer.measure(text, style.withAlpha(if (inRange) 1f else 0.3f))
                drawText(layout, topLeft = Offset(x - layout.size.width / 2f, top + 36.dp.toPx()))
            }
        }
        // The fixed centre line, with a soft glow.
        val lineTop = top - 6.dp.toPx()
        val lineBottom = top + 34.dp.toPx()
        drawLine(tide.copy(alpha = 0.18f), Offset(mid, lineTop), Offset(mid, lineBottom), 10.dp.toPx(), StrokeCap.Round)
        drawLine(tide, Offset(mid, lineTop), Offset(mid, lineBottom), 3.dp.toPx(), StrokeCap.Round)
        drawCircle(tide, radius = 4.5.dp.toPx(), center = Offset(mid, lineTop))
    }
}

/** "11:45 PM" with a smaller "PM". */
private fun bigTime(text: String) = buildAnnotatedString {
    val split = text.indexOf(' ')
    if (split < 0) {
        append(text)
    } else {
        append(text.substring(0, split))
        withStyle(SpanStyle(fontSize = 0.4.em)) { append(" " + text.substring(split + 1)) }
    }
}

private fun TextStyle.withAlpha(alpha: Float) = copy(color = color.copy(alpha = color.alpha * alpha))

private fun Instant.epochMinutes(): Double = epochSecond / 60.0

/** The zone's UTC offset in whole minutes at [epochMinutes]. */
private fun ZoneId.offsetAt(epochMinutes: Double): Long {
    val offset = if (this is ZoneOffset) this else rules.getOffset(Instant.ofEpochSecond((epochMinutes * 60).toLong()))
    return offset.totalSeconds / 60L
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun TimeScrubberPreview() = SleepSaverTheme {
    val zone = ZoneOffset.ofHours(2)
    TimeScrubber(
        value = Instant.parse("2026-10-05T21:45:00Z"),
        onValueChange = {},
        zone = zone,
        label = "Fell asleep",
    )
}
