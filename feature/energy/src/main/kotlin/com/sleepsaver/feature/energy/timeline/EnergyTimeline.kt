package com.sleepsaver.feature.energy.timeline

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.LastNightState
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.ui.components.icon
import com.sleepsaver.core.ui.components.tint
import com.sleepsaver.core.ui.components.title
import com.sleepsaver.core.ui.format.LocalUse24HourClock
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.formatClockAxis
import com.sleepsaver.core.ui.format.formatClockTime
import com.sleepsaver.core.ui.format.formatDuration
import com.sleepsaver.core.ui.format.formatNightLabel
import com.sleepsaver.core.ui.format.formatShift
import com.sleepsaver.core.ui.format.formatTimeRange
import com.sleepsaver.core.ui.format.timeRange
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.feature.energy.EnergyUiState
import com.sleepsaver.feature.energy.R
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle as DayTextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Column positions and label sizes, in dp/sp. */
internal object TimelineDimens {
    val HourHeight = 76.dp
    val TopInset = 36.dp
    val BottomInset = 28.dp
    val AxisStart = 8.dp
    val MinAxisRight = 42.dp
    val EndPadding = 16.dp
    val BarWidth = 5.dp
    val BarGap = 4.dp
    val LabelInset = 12.dp
    val CurveStartFraction = 0.55f
    val BlockCorner = 20.dp
    val NapCorner = 12.dp
    val PillHeight = 28.dp
    val NowHeight = 26.dp
    val HabitHeight = 38.dp
    val LabelGap = 6.dp

    /** A habit's place in its label row: start padding, icon, the gap after it, and end padding. */
    val HabitStartPadding = 4.dp
    val HabitIcon = 28.dp
    val HabitIconGap = 8.dp
    val HabitEndPadding = 10.dp

    /** A shared habit row ends at least this far left of the energy curve. */
    val CurveClearance = 8.dp
}

/**
 * Pixel columns for a timeline [width] wide. The hour axis is as wide as its widest label at the current font
 * size and clock format, and label heights follow the font scale, so large text never spills into the next label.
 * Habit titles and the widest clock time are measured too, so habits at the same minute share a label row only
 * while they fit side by side, left of the curve.
 */
@Composable
internal fun rememberTimelineSpec(width: Dp): TimelineSpec {
    val density = LocalDensity.current
    val use24h = LocalUse24HourClock.current
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelMedium
    val titleStyle = MaterialTheme.typography.labelMedium
    val timeStyle = MaterialTheme.typography.labelSmall
    val titles = HabitType.entries.map { stringResource(it.shortTitleRes) }
    return remember(width, density, use24h, axisStyle, titleStyle, timeStyle, titles) {
        val widestAxisLabel = (0..23).maxOf { hour ->
            measurer.measure(formatClockAxis(LocalTime.of(hour, 0), use24h), axisStyle).size.width
        }
        val titleWidths = HabitType.entries.zip(titles).associate { (type, title) ->
            type to measurer.measure(title, titleStyle, maxLines = 1).size.width.toFloat()
        }
        val widestTime = (0..23).maxOf { hour ->
            measurer.measure(formatClockTime(LocalTime.of(hour, 59), use24h), timeStyle, maxLines = 1).size.width
        }
        with(density) {
            val widthPx = width.toPx()
            val axisRight = maxOf(TimelineDimens.MinAxisRight.toPx(), TimelineDimens.AxisStart.toPx() + widestAxisLabel)
            val barsLeft = axisRight + 16.dp.toPx()
            val contentRight = widthPx - TimelineDimens.EndPadding.toPx()
            TimelineSpec(
                width = widthPx,
                pxPerHour = TimelineDimens.HourHeight.toPx(),
                topInset = TimelineDimens.TopInset.toPx(),
                bottomInset = TimelineDimens.BottomInset.toPx(),
                barsLeft = barsLeft,
                barWidth = TimelineDimens.BarWidth.toPx(),
                barGap = TimelineDimens.BarGap.toPx(),
                labelInset = TimelineDimens.LabelInset.toPx(),
                blockLeft = barsLeft,
                blockRight = contentRight,
                curveLeft = maxOf(widthPx * TimelineDimens.CurveStartFraction, barsLeft + 120.dp.toPx()),
                curveRight = contentRight - 6.dp.toPx(),
                pillHeight = maxOf(TimelineDimens.PillHeight.toPx(), 18.sp.toPx() + 10.dp.toPx()),
                captionHeight = 14.sp.toPx() + 4.dp.toPx(),
                habitHeight = maxOf(TimelineDimens.HabitHeight.toPx(), 18.sp.toPx() + 14.sp.toPx() + 6.dp.toPx()),
                nowHeight = maxOf(TimelineDimens.NowHeight.toPx(), 18.sp.toPx() + 8.dp.toPx()),
                labelGap = TimelineDimens.LabelGap.toPx(),
                axisRight = axisRight,
                tickRight = axisRight + 8.dp.toPx(),
                gridLeft = axisRight + 10.dp.toPx(),
                contentRight = contentRight,
                habitTitleWidths = titleWidths,
                habitTimeWidth = widestTime.toFloat(),
                habitChromeWidth = (
                    TimelineDimens.HabitStartPadding + TimelineDimens.HabitIcon + TimelineDimens.HabitIconGap +
                        TimelineDimens.HabitEndPadding
                    ).toPx(),
                curveClearance = TimelineDimens.CurveClearance.toPx(),
            )
        }
    }
}

/** Colours read once per composition and handed to the Canvas. */
@Immutable
private data class TimelinePalette(
    val grid: Color,
    val tick: Color,
    val axisText: Color,
    val dayText: Color,
    val sleepBlock: Color,
    val sleepStroke: Color,
    val napBlock: Color,
    val awake: Color,
    val awakeText: Color,
    val curve: Color,
    val melatonin: Color,
    val wakeZone: Color,
    val wakeZoneEdge: Color,
    val wakeZoneText: Color,
    val now: Color,
    val connector: Color,
    val background: Color,
    val phaseTints: Map<PhaseType, Color>,
)

@Composable
@ReadOnlyComposable
private fun timelinePalette(): TimelinePalette {
    val colors = SleepSaverTheme.colors
    return TimelinePalette(
        grid = colors.gridLine,
        tick = colors.textTertiary,
        axisText = colors.textSecondary,
        dayText = colors.textTertiary,
        sleepBlock = colors.sleepBlock,
        sleepStroke = colors.sleepBlockStroke,
        napBlock = colors.napBlock,
        awake = colors.awakeGap,
        awakeText = colors.textSecondary,
        curve = colors.energyCurve,
        melatonin = colors.melatonin,
        wakeZone = colors.wakeZone,
        // The zone's own hue, a little stronger, so its edges read over the night block.
        wakeZoneEdge = colors.wakeZone.copy(alpha = (colors.wakeZone.alpha * 2.5f).coerceAtMost(1f)),
        wakeZoneText = colors.textSecondary,
        now = colors.nowLine,
        connector = colors.textTertiary.copy(alpha = 0.55f),
        background = MaterialTheme.colorScheme.background,
        phaseTints = PhaseType.entries.associateWith { it.tint },
    )
}

/**
 * The day as one vertical strip: last night's sleep, the energy curve, phases, habits and tonight's window.
 * [onBlockTap] gets any tapped sleep block; [onMainLongPress] the time pressed inside last night's block.
 */
@Composable
internal fun EnergyTimeline(
    layout: TimelineLayout,
    state: EnergyUiState.Ready,
    onBlockTap: (TimelineBlock) -> Unit,
    onMainLongPress: (TimelineBlock, Instant) -> Unit,
    onOpenHabit: (HabitType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val palette = timelinePalette()
    val heightDp = with(density) { layout.height.toDp() }
    Box(modifier.fillMaxWidth().height(heightDp)) {
        TimelineCanvas(layout, state, palette, Modifier.fillMaxSize())
        layout.blocks.forEach { block ->
            key(block.kind, block.start) {
                BlockOverlay(block, layout, state, onBlockTap, onMainLongPress)
            }
        }
        val labelMaxWidth = with(density) { (layout.spec.contentRight - layout.labelLeft).toDp() }
        val pillHeight = with(density) { layout.spec.pillHeight.toDp() }
        layout.phases.forEach { mark ->
            PhaseLabel(
                mark = mark,
                tint = palette.phaseTints.getValue(mark.phase.type),
                isCurrent = mark.phase.contains(state.now),
                zone = state.zone,
                pillHeight = pillHeight,
                modifier = Modifier
                    .offset { IntOffset(layout.labelLeft.roundToInt(), mark.labelTop.roundToInt()) }
                    .widthIn(max = labelMaxWidth),
            )
        }
        layout.habits.forEach { mark ->
            HabitLabel(
                mark = mark,
                zone = state.zone,
                onOpen = onOpenHabit,
                height = with(density) { layout.spec.habitHeight.toDp() },
                modifier = Modifier
                    .offset { IntOffset(layout.labelLeft.roundToInt() - with(density) { 4.dp.roundToPx() }, mark.labelTop.roundToInt()) }
                    .widthIn(max = labelMaxWidth),
            )
        }
        layout.now?.let { now ->
            NowPill(
                now = now,
                zone = state.zone,
                showTime = !state.nightMode,
                height = with(density) { layout.spec.nowHeight.toDp() },
                modifier = Modifier.offset { IntOffset(layout.labelLeft.roundToInt(), now.labelTop.roundToInt()) },
            )
        }
        // A shared row stays left of the curve, but one habit's label at large text can still reach it late in the
        // evening: the dots stay on top.
        Canvas(Modifier.fillMaxSize()) {
            layout.habits.forEach { mark ->
                drawCircle(palette.background, radius = 5.dp.toPx(), center = Offset(mark.anchorX, mark.anchorY))
                drawCircle(palette.curve, radius = 3.5.dp.toPx(), center = Offset(mark.anchorX, mark.anchorY), style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/// ---------------------------------------------------------------- Canvas

@Composable
private fun TimelineCanvas(layout: TimelineLayout, state: EnergyUiState.Ready, palette: TimelinePalette, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val use24h = LocalUse24HourClock.current
    val axisStyle = MaterialTheme.typography.labelMedium.copy(color = palette.axisText)
    val dayStyle = MaterialTheme.typography.labelSmall.copy(color = palette.dayText, fontWeight = FontWeight.SemiBold)
    val bandStyle = MaterialTheme.typography.labelSmall.copy(color = palette.awakeText)
    val wakeStyle = MaterialTheme.typography.labelSmall.copy(color = palette.wakeZoneText)
    val scaleStyle = MaterialTheme.typography.labelSmall.copy(color = palette.dayText)
    val wakeZoneLabel = stringResource(R.string.energy_wake_zone)
    val scaleLow = stringResource(R.string.energy_scale_low)
    val scaleTitle = stringResource(R.string.energy_scale_title)
    val scaleHigh = stringResource(R.string.energy_scale_high)
    val awakeFormat = stringResource(R.string.energy_awake_band)

    Canvas(modifier) {
        val spec = layout.spec
        val gridLeft = spec.gridLeft
        val right = spec.contentRight

        // Tonight's melatonin window and the wake zones: soft bands across the day column, under everything else.
        layout.melatonin?.let { band ->
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to palette.melatonin.copy(alpha = 0.05f),
                    1f to palette.melatonin.copy(alpha = 0.16f),
                    startX = gridLeft,
                    endX = right,
                ),
                topLeft = Offset(gridLeft, band.top),
                size = Size(right - gridLeft, band.height),
            )
        }
        layout.wakeZones.forEach { band ->
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to palette.wakeZone.copy(alpha = palette.wakeZone.alpha * 0.35f),
                    1f to palette.wakeZone,
                    startX = gridLeft,
                    endX = right,
                ),
                topLeft = Offset(gridLeft, band.top),
                size = Size(right - gridLeft, band.height),
            )
        }

        // Axis: faint hour lines, minor half-hour ticks, hour labels and the day at midnight.
        layout.ticks.forEach { tick ->
            if (tick.isHour) {
                drawLine(
                    color = if (tick.isMidnight) palette.tick.copy(alpha = 0.35f) else palette.grid,
                    start = Offset(gridLeft, tick.y),
                    end = Offset(right, tick.y),
                    strokeWidth = 1.dp.toPx(),
                )
                drawLine(palette.tick, Offset(spec.tickRight - 6.dp.toPx(), tick.y), Offset(spec.tickRight, tick.y), 1.5.dp.toPx(), StrokeCap.Round)
                val text = measurer.measure(formatClockAxis(tick.time, state.zone, use24h), axisStyle)
                drawText(text, topLeft = Offset(spec.axisRight - text.size.width, tick.y - text.size.height / 2f))
                if (tick.isMidnight) {
                    val day = tick.time.atZone(state.zone).dayOfWeek.getDisplayName(DayTextStyle.SHORT, Locale.US).uppercase(Locale.US)
                    val dayText = measurer.measure(day, dayStyle)
                    drawText(dayText, topLeft = Offset(spec.axisRight - dayText.size.width, tick.y + text.size.height / 2f))
                }
            } else {
                drawLine(palette.tick.copy(alpha = 0.6f), Offset(spec.tickRight - 3.dp.toPx(), tick.y), Offset(spec.tickRight, tick.y), 1.dp.toPx(), StrokeCap.Round)
            }
        }

        // The energy scale over the curve column, above the first hour.
        drawScaleLegend(layout, measurer, scaleStyle, scaleLow, scaleTitle, scaleHigh, palette)

        // Sleep blocks.
        layout.blocks.forEach { block -> drawBlock(block, palette, measurer, bandStyle, awakeFormat) }

        // Wake zone edges stay visible over the night block; the label sits inside the zone's lower right.
        layout.wakeZones.forEach { band ->
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            listOf(band.top, band.bottom).forEach { y ->
                drawLine(palette.wakeZoneEdge, Offset(gridLeft, y), Offset(right, y), 1.dp.toPx(), pathEffect = dash)
            }
            val label = measurer.measure(wakeZoneLabel, wakeStyle)
            if (band.height > label.size.height + 8.dp.toPx()) {
                drawText(label, topLeft = Offset(right - label.size.width - 10.dp.toPx(), band.bottom - label.size.height - 4.dp.toPx()))
            }
        }

        // Phase bars, with a connector from each phase start to its label.
        layout.phases.forEach { mark ->
            val tint = palette.phaseTints.getValue(mark.phase.type)
            val w = spec.barWidth
            drawRoundRect(
                color = tint.copy(alpha = 0.85f),
                topLeft = Offset(mark.barX, mark.top),
                size = Size(w, (mark.bottom - mark.top).coerceAtLeast(w)),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
            drawConnector(
                color = tint.copy(alpha = 0.45f),
                fromX = mark.barX + w + 2.dp.toPx(),
                anchorY = mark.anchorY,
                railX = layout.lanesRight + 5.dp.toPx(),
                labelY = mark.labelTop + spec.pillHeight / 2,
                toX = layout.labelLeft - 2.dp.toPx(),
            )
        }

        drawCurve(layout, state.now, palette)

        // Habits: a tick at the exact time beside the lanes with a connector to the label, and a dotted guide
        // across the curve column to a dot on the curve (drawn above the labels, in EnergyTimeline).
        layout.habits.forEach { mark ->
            // Only a label its neighbours moved (up or down) needs pointing back to its time.
            val railX = layout.lanesRight + 5.dp.toPx()
            val labelY = mark.labelTop + spec.habitHeight / 2
            if (abs(labelY - mark.anchorY) >= 1.dp.toPx()) {
                drawConnector(palette.connector, railX, mark.anchorY, railX, labelY, layout.labelLeft - 6.dp.toPx())
                drawCircle(palette.connector, radius = 2.dp.toPx(), center = Offset(railX, mark.anchorY))
            }
            drawLine(
                color = palette.curve.copy(alpha = 0.35f),
                start = Offset(spec.curveLeft, mark.anchorY),
                end = Offset(mark.anchorX, mark.anchorY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
            )
        }

        // Now.
        layout.now?.let { now ->
            drawLine(palette.now.copy(alpha = 0.75f), Offset(gridLeft, now.y), Offset(right, now.y), 1.dp.toPx())
            drawCircle(palette.now, radius = 3.dp.toPx(), center = Offset(gridLeft, now.y))
            now.dotX?.let { x ->
                drawCircle(palette.curve.copy(alpha = 0.22f), radius = 11.dp.toPx(), center = Offset(x, now.y))
                drawCircle(palette.now, radius = 5.5.dp.toPx(), center = Offset(x, now.y))
                drawCircle(palette.curve, radius = 3.5.dp.toPx(), center = Offset(x, now.y))
            }
        }
    }
}

/**
 * From a label's exact time to the label: straight across when the label sits at its time, otherwise along a
 * vertical rail beside the phase lanes, so labels moved up or down by their neighbours read as a tidy list.
 */
private fun DrawScope.drawConnector(color: Color, fromX: Float, anchorY: Float, railX: Float, labelY: Float, toX: Float) {
    val path = Path().apply {
        moveTo(fromX, anchorY)
        if (abs(labelY - anchorY) < 1.dp.toPx()) {
            lineTo(toX, labelY)
        } else {
            lineTo(railX, anchorY)
            lineTo(railX, labelY)
            lineTo(toX, labelY)
        }
    }
    drawPath(path, color, style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawScaleLegend(
    layout: TimelineLayout,
    measurer: TextMeasurer,
    style: TextStyle,
    low: String,
    title: String,
    high: String,
    palette: TimelinePalette,
) {
    val y = layout.spec.topInset / 2
    val left = layout.scale.left
    val right = layout.scale.right
    val lowText = measurer.measure(low, style)
    val highText = measurer.measure(high, style)
    val titleText = measurer.measure(title, style.copy(fontWeight = FontWeight.SemiBold))
    drawText(lowText, topLeft = Offset(left, y - lowText.size.height / 2f))
    drawText(highText, topLeft = Offset(right - highText.size.width, y - highText.size.height / 2f))
    drawText(titleText, topLeft = Offset((left + right) / 2 - titleText.size.width / 2f, y - titleText.size.height / 2f))
    val gap = 6.dp.toPx()
    val lineY = y
    val segments = listOf(
        left + lowText.size.width + gap to (left + right) / 2 - titleText.size.width / 2f - gap,
        (left + right) / 2 + titleText.size.width / 2f + gap to right - highText.size.width - gap,
    )
    segments.forEachIndexed { i, (a, b) ->
        if (b > a) {
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(palette.curve.copy(alpha = if (i == 0) 0.08f else 0.3f), palette.curve.copy(alpha = if (i == 0) 0.3f else 0.6f)),
                    startX = a,
                    endX = b,
                ),
                start = Offset(a, lineY),
                end = Offset(b, lineY),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun DrawScope.drawBlock(
    block: TimelineBlock,
    palette: TimelinePalette,
    measurer: TextMeasurer,
    bandStyle: TextStyle,
    awakeFormat: String,
) {
    val corner = when (block.kind) {
        TimelineBlockKind.NAP -> TimelineDimens.NapCorner.toPx()
        else -> TimelineDimens.BlockCorner.toPx()
    }.coerceAtMost(block.height / 2)
    val topLeft = Offset(block.left, block.top)
    val size = Size(block.right - block.left, block.height)
    val radius = CornerRadius(corner, corner)
    when (block.kind) {
        TimelineBlockKind.MAIN -> {
            drawRoundRect(
                brush = Brush.verticalGradient(
                    0f to palette.sleepBlock.copy(alpha = 0.95f),
                    1f to palette.sleepBlock,
                    startY = block.top,
                    endY = block.bottom,
                ),
                topLeft = topLeft,
                size = size,
                cornerRadius = radius,
            )
            drawAwakeBands(block, topLeft, size, radius, palette, measurer, bandStyle, awakeFormat)
            drawRoundRect(palette.sleepStroke.copy(alpha = 0.35f), topLeft, size, radius, style = Stroke(1.dp.toPx()))
        }
        TimelineBlockKind.EXTRA -> {
            drawRoundRect(palette.sleepBlock.copy(alpha = 0.75f), topLeft, size, radius)
            drawAwakeBands(block, topLeft, size, radius, palette, measurer, bandStyle, awakeFormat)
            drawRoundRect(palette.sleepStroke.copy(alpha = 0.25f), topLeft, size, radius, style = Stroke(1.dp.toPx()))
        }
        TimelineBlockKind.NAP -> {
            drawRoundRect(palette.napBlock.copy(alpha = 0.7f), topLeft, size, radius)
            drawRoundRect(palette.sleepStroke.copy(alpha = 0.3f), topLeft, size, radius, style = Stroke(1.dp.toPx()))
        }
        TimelineBlockKind.PENDING -> {
            drawRoundRect(palette.sleepBlock.copy(alpha = 0.3f), topLeft, size, radius)
            drawRoundRect(
                color = palette.sleepStroke.copy(alpha = 0.45f),
                topLeft = topLeft,
                size = size,
                cornerRadius = radius,
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                ),
            )
        }
    }
}

/** Interruptions as darker bands, clipped to the block's rounded corners, labelled when there's room. */
private fun DrawScope.drawAwakeBands(
    block: TimelineBlock,
    topLeft: Offset,
    size: Size,
    radius: CornerRadius,
    palette: TimelinePalette,
    measurer: TextMeasurer,
    style: TextStyle,
    format: String,
) {
    if (block.awake.isEmpty()) return
    val clip = Path().apply { addRoundRect(RoundRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height, radius)) }
    clipPath(clip) {
        block.awake.forEach { band ->
            drawRect(palette.awake, topLeft = Offset(block.left, band.top), size = Size(size.width, band.height.coerceAtLeast(2.dp.toPx())))
        }
    }
    // Labels on the right, centred on their band, clear of the block's centre text and of each other.
    val centre = (block.top + block.bottom) / 2
    val busy = if (block.kind == TimelineBlockKind.MAIN && block.height >= MainBlockDetailsMinHeight.toPx()) {
        (centre - MainBlockDetailsHalfHeight.toPx())..(centre + MainBlockDetailsHalfHeight.toPx())
    } else {
        null
    }
    var lastBottom = Float.NEGATIVE_INFINITY
    block.awake.forEach { band ->
        val text = measurer.measure(format.format(formatDuration(band.duration)), style)
        val top = (band.top + band.bottom) / 2 - text.size.height / 2f
        val bottom = top + text.size.height
        val clear = top >= block.top + 4.dp.toPx() && bottom <= block.bottom - 4.dp.toPx() && top >= lastBottom &&
            (busy == null || bottom < busy.start || top > busy.endInclusive)
        if (clear) {
            drawText(text, topLeft = Offset(block.right - text.size.width - 14.dp.toPx(), top))
            lastBottom = bottom + 2.dp.toPx()
        }
    }
}

/** The main block shows its duration and the hold hint in the middle once it's at least this tall. */
internal val MainBlockDetailsMinHeight = 180.dp
private val MainBlockDetailsHalfHeight = 76.dp

/** The energy curve: soft fill toward higher energy, a faded line for the past and a bright one ahead. */
private fun DrawScope.drawCurve(layout: TimelineLayout, now: Instant, palette: TimelinePalette) {
    val points = layout.curve
    if (points.size < 2) return
    val line = smoothPath(points)
    val baseline = layout.scale.left
    val fill = Path().apply {
        addPath(line)
        lineTo(baseline, points.last().y)
        lineTo(baseline, points.first().y)
        close()
    }
    drawPath(
        path = fill,
        brush = Brush.horizontalGradient(
            0f to palette.curve.copy(alpha = 0f),
            0.35f to palette.curve.copy(alpha = 0.06f),
            1f to palette.curve.copy(alpha = 0.26f),
            startX = baseline,
            endX = layout.scale.right,
        ),
    )
    val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val nowY = layout.geometry.yOf(now)
    clipRect(top = 0f, bottom = nowY) { drawPath(line, palette.curve.copy(alpha = 0.45f), style = stroke) }
    clipRect(top = nowY, bottom = size.height) { drawPath(line, palette.curve, style = stroke) }
}

/** Quadratic smoothing through segment midpoints: the 5-minute samples read as one continuous line. */
private fun smoothPath(points: List<Offset>): Path = Path().apply {
    moveTo(points.first().x, points.first().y)
    for (i in 1 until points.lastIndex) {
        val p = points[i]
        val next = points[i + 1]
        quadraticTo(p.x, p.y, (p.x + next.x) / 2, (p.y + next.y) / 2)
    }
    lineTo(points.last().x, points.last().y)
}

// ---------------------------------------------------------------- Overlays

@Composable
private fun BlockOverlay(
    block: TimelineBlock,
    layout: TimelineLayout,
    state: EnergyUiState.Ready,
    onBlockTap: (TimelineBlock) -> Unit,
    onMainLongPress: (TimelineBlock, Instant) -> Unit,
) {
    val density = LocalDensity.current
    val currentBlock by rememberUpdatedState(block)
    val currentGeometry by rememberUpdatedState(layout.geometry)
    val currentTap by rememberUpdatedState(onBlockTap)
    val currentLongPress by rememberUpdatedState(onMainLongPress)
    val width = with(density) { (block.right - block.left).toDp() }
    val height = with(density) { block.height.toDp() }
    val isMain = block.kind == TimelineBlockKind.MAIN
    // Before the usual wake time the placeholder is only a promise: nothing to edit yet.
    val tappable = block.kind != TimelineBlockKind.PENDING ||
        state.lastNightState == LastNightState.WAITING || state.lastNightState == LastNightState.MISSING
    val zone = state.zone
    val range = blockRange(block, zone)
    val description = when (block.kind) {
        TimelineBlockKind.PENDING -> stringResource(
            if (tappable) R.string.energy_pending_description else R.string.energy_upcoming_description,
        )
        TimelineBlockKind.NAP -> stringResource(R.string.energy_nap_label, formatDuration(block.asleep)) + ", " + range
        TimelineBlockKind.EXTRA -> stringResource(R.string.energy_extra_label, formatDuration(block.asleep)) + ", " + range
        TimelineBlockKind.MAIN -> stringResource(
            R.string.energy_block_description,
            formatNightLabel(block.wakeDate, state.day),
            range,
            formatDuration(block.asleep),
        )
    }
    val tapLabel = stringResource(if (block.kind == TimelineBlockKind.PENDING) R.string.energy_add_night else R.string.energy_edit_night)
    val addAwakeLabel = stringResource(R.string.energy_add_awake_title)
    val shape = RoundedCornerShape(if (block.kind == TimelineBlockKind.NAP) TimelineDimens.NapCorner else TimelineDimens.BlockCorner)
    Box(
        Modifier
            .offset { IntOffset(block.left.roundToInt(), block.top.roundToInt()) }
            .size(width, height)
            .clip(shape)
            .pointerInput(isMain, tappable) {
                detectTapGestures(
                    onTap = if (tappable) {
                        { currentTap(currentBlock) }
                    } else {
                        null
                    },
                    onLongPress = if (isMain) {
                        { offset -> currentLongPress(currentBlock, currentGeometry.timeAt(currentBlock.top + offset.y)) }
                    } else {
                        null
                    },
                )
            }
            .clearAndSetSemantics {
                contentDescription = description
                if (tappable) {
                    role = Role.Button
                    onClick(label = tapLabel) { currentTap(currentBlock); true }
                }
                if (isMain) {
                    customActions = listOf(
                        CustomAccessibilityAction(addAwakeLabel) {
                            val middle = currentBlock.start.plusMillis(Duration.between(currentBlock.start, currentBlock.end).toMillis() / 2)
                            currentLongPress(currentBlock, middle)
                            true
                        },
                    )
                }
            },
    ) {
        when (block.kind) {
            TimelineBlockKind.MAIN -> MainBlockContent(block, state, height)
            TimelineBlockKind.PENDING -> PendingBlockContent(state.lastNightState, height)
            TimelineBlockKind.EXTRA -> SmallBlockLabel(stringResource(R.string.energy_extra_label, formatDuration(block.asleep)), height)
            TimelineBlockKind.NAP -> SmallBlockLabel(stringResource(R.string.energy_nap_label, formatDuration(block.asleep)), height)
        }
    }
}

@Composable
private fun blockRange(block: TimelineBlock, zone: ZoneId): String {
    val use24h = LocalUse24HourClock.current
    return formatTimeRange(block.start, block.end, zone, use24h)
}

@Composable
private fun MainBlockContent(block: TimelineBlock, state: EnergyUiState.Ready, height: Dp) {
    val secondary = SleepSaverTheme.colors.textSecondary
    val tide = MaterialTheme.colorScheme.primary
    val startZone = block.startOffset ?: state.zone
    val endZone = block.endOffset ?: state.zone
    Box(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        BlockTime(
            label = stringResource(R.string.energy_fell_asleep),
            time = clockTime(block.start, startZone),
            labelFirst = true,
            modifier = Modifier.align(Alignment.TopStart),
        )
        if (height >= MainBlockDetailsMinHeight) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = tide.copy(alpha = 0.8f), modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(6.dp))
                Text(
                    text = formatDuration(block.asleep),
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Light),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(stringResource(R.string.energy_asleep), style = MaterialTheme.typography.bodyMedium, color = secondary)
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.35f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.TouchApp, contentDescription = null, tint = secondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.energy_hold_hint), style = MaterialTheme.typography.labelMedium, color = secondary)
                }
            }
        }
        BlockTime(
            label = stringResource(R.string.energy_woke_up),
            time = clockTime(block.end, endZone),
            labelFirst = false,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

@Composable
private fun BlockTime(label: String, time: String, labelFirst: Boolean, modifier: Modifier = Modifier) {
    val labelText: @Composable () -> Unit = {
        Text(label, style = MaterialTheme.typography.labelMedium, color = SleepSaverTheme.colors.textSecondary)
    }
    Column(modifier) {
        if (labelFirst) labelText()
        Text(time, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (!labelFirst) labelText()
    }
}

@Composable
private fun PendingBlockContent(lastNightState: LastNightState, height: Dp) {
    if (height < 96.dp) return
    val (icon, title, body) = when (lastNightState) {
        LastNightState.MISSING -> Triple(Icons.Rounded.EditCalendar, R.string.energy_missing_title, R.string.energy_missing_body)
        LastNightState.WAITING -> Triple(Icons.Rounded.CloudSync, R.string.energy_pending_title, R.string.energy_pending_body)
        // Before the usual wake time the night may not even have started: no promise, no action.
        LastNightState.NOT_EXPECTED_YET, LastNightState.AVAILABLE ->
            Triple(Icons.Rounded.Bedtime, R.string.energy_upcoming_title, R.string.energy_upcoming_body)
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            color = SleepSaverTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SmallBlockLabel(text: String, height: Dp) {
    if (height < 22.dp) return
    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PhaseLabel(
    mark: PhaseMark,
    tint: Color,
    isCurrent: Boolean,
    zone: ZoneId,
    pillHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val title = mark.phase.type.title()
    val description = stringResource(R.string.energy_phase_description, title, timeRange(mark.phase.start, mark.phase.end, zone))
    Column(modifier.clearAndSetSemantics { contentDescription = description }) {
        Row(
            Modifier
                .height(pillHeight)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.background)
                .background(tint.copy(alpha = if (isCurrent) 0.30f else 0.14f))
                .padding(start = 9.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(mark.phase.type.icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (mark.showsShift) {
            Text(
                text = formatShift(mark.phase.shiftVsYesterday).orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = SleepSaverTheme.colors.textTertiary,
                maxLines = 1,
                modifier = Modifier.padding(start = 10.dp, top = 2.dp),
            )
        }
    }
}

/**
 * One label row: each habit at that time with its own icon, short title, time and button, side by side when several
 * share the minute ("Dim the lights 9:32 PM · Night steps 9:32 PM").
 */
@Composable
private fun HabitLabel(mark: HabitMark, zone: ZoneId, onOpen: (HabitType) -> Unit, height: Dp, modifier: Modifier = Modifier) {
    val time = clockTime(mark.habit.time, zone)
    Row(
        modifier
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.88f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        mark.habits.forEach { habit ->
            val type = habit.type
            val title = stringResource(type.shortTitleRes)
            val description = stringResource(R.string.energy_habit_marker_description, type.title(), time)
            Row(
                Modifier
                    .fillMaxHeight()
                    .clickable(role = Role.Button) { onOpen(type) }
                    .clearAndSetSemantics {
                        contentDescription = description
                        role = Role.Button
                        onClick { onOpen(type); true }
                    }
                    .padding(start = TimelineDimens.HabitStartPadding, end = TimelineDimens.HabitEndPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(TimelineDimens.HabitIcon)
                        .clip(CircleShape)
                        .background(SleepSaverTheme.colors.cardRaised),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(type.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(TimelineDimens.HabitIconGap))
                Column {
                    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(time, style = MaterialTheme.typography.labelSmall, color = SleepSaverTheme.colors.textSecondary, maxLines = 1)
                }
            }
        }
    }
}

/** "Now 2:47 PM"; just "Now" at night, when no screen shows the time (§5.0). */
@Composable
private fun NowPill(now: NowMark, zone: ZoneId, showTime: Boolean, height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(height)
            .clip(CircleShape)
            .background(SleepSaverTheme.colors.nowLine)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (showTime) {
                stringResource(R.string.energy_now, clockTime(now.time, zone))
            } else {
                stringResource(R.string.energy_now_night)
            },
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.background,
            maxLines = 1,
        )
    }
}

/** Short names that fit the timeline's label column. */
@get:StringRes
internal val HabitType.shortTitleRes: Int
    get() = when (this) {
        HabitType.MORNING_LIGHT -> R.string.energy_habit_short_morning_light
        HabitType.CAFFEINE_CUTOFF -> R.string.energy_habit_short_caffeine
        HabitType.LARGE_MEAL_CUTOFF -> R.string.energy_habit_short_meal
        HabitType.ALCOHOL_CUTOFF -> R.string.energy_habit_short_alcohol
        HabitType.EXERCISE_CUTOFF -> R.string.energy_habit_short_exercise
        HabitType.DIM_LIGHTS -> R.string.energy_habit_short_dim_lights
        HabitType.EVENING_ROUTINE -> R.string.energy_habit_short_routine
        HabitType.BEDTIME -> R.string.energy_habit_short_bedtime
        HabitType.NAP_WINDOW -> R.string.energy_habit_short_nap
    }

/** Icon for the footer line about tomorrow's wake zone. */
internal val TomorrowWakeIcon = Icons.Rounded.WbSunny
