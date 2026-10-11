package com.sleepsaver.feature.review.detail

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.model.AwakeKind
import com.sleepsaver.core.domain.model.SleepBlock
import com.sleepsaver.core.domain.model.SleepStageType
import com.sleepsaver.core.ui.format.LocalUse24HourClock
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.formatClockAxis
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.feature.review.R
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Stage rows, top to bottom: awake sits on top, deep sleep at the bottom. */
internal enum class StageRow { AWAKE, REM, LIGHT, DEEP }

internal data class StageSegment(val start: Instant, val end: Instant, val row: StageRow, val unclassified: Boolean = false)

/** The block's stages as drawable segments, plus gaps and added awake time on the awake row. Sorted by start. */
internal fun stageSegments(block: SleepBlock): List<StageSegment> {
    val stages = block.stages.map { stage ->
        when (stage.type) {
            SleepStageType.AWAKE, SleepStageType.OUT_OF_BED, SleepStageType.AWAKE_IN_BED ->
                StageSegment(stage.start, stage.end, StageRow.AWAKE)
            SleepStageType.REM -> StageSegment(stage.start, stage.end, StageRow.REM)
            SleepStageType.LIGHT -> StageSegment(stage.start, stage.end, StageRow.LIGHT)
            SleepStageType.DEEP -> StageSegment(stage.start, stage.end, StageRow.DEEP)
            SleepStageType.SLEEPING, SleepStageType.UNKNOWN -> StageSegment(stage.start, stage.end, StageRow.LIGHT, unclassified = true)
        }
    }
    val awake = block.awakePeriods
        .filter { it.kind != AwakeKind.STAGE }
        .map { StageSegment(it.start, it.end, StageRow.AWAKE) }
    return (stages + awake).filter { it.end.isAfter(it.start) }.sortedWith(compareBy({ it.start }, { it.end }))
}

/**
 * A stage chart: one row per stage (awake on top), a bar for each stretch, thin steps between them and an hour
 * axis underneath, in [zone].
 */
@Composable
internal fun Hypnogram(block: SleepBlock, zone: ZoneId, modifier: Modifier = Modifier) {
    val segments = remember(block) { stageSegments(block) }
    val description = stringResource(
        R.string.review_hypnogram_description,
        clockTime(block.start, zone),
        clockTime(block.end, zone),
    )
    StageChart(
        segments = segments,
        start = block.start,
        end = block.end,
        zone = zone,
        modifier = modifier.semantics { contentDescription = description },
    )
}

/**
 * Where a [StageChart] draws, so overlays and the movement band share its x-axis: the plot runs from [plotLeft] to
 * [plotRight] (after the row-label gutter) and the stage rows from [rowsTop] to [rowsBottom].
 */
internal class StageChartFrame(
    val plotLeft: Float,
    val plotRight: Float,
    val rowsTop: Float,
    val rowsBottom: Float,
    private val start: Instant,
    private val spanMs: Float,
) {
    /** [t]'s x, clamped to the plot. */
    fun x(t: Instant): Float =
        plotLeft + (plotRight - plotLeft) * (Duration.between(start, t).toMillis() / spanMs).coerceIn(0f, 1f)
}

/**
 * An extra band under a [StageChart]'s rows, before its axis, on the same x-axis (the Wake landing movement band).
 * [draw] paints between `top` and `bottom`.
 */
internal class StageChartBand(
    val label: String,
    val height: Dp,
    val draw: DrawScope.(frame: StageChartFrame, top: Float, bottom: Float) -> Unit,
)

/**
 * The stage chart behind [Hypnogram] and the Wake landing card: rows with their labels, [segments] between [start]
 * and [end], an optional [band] and a time axis in [zone] whose ticks step by the first of [tickMinutes] that leaves
 * 44 dp between labels (hours, then every other hour by default). [underlay] draws behind the bars (a shaded
 * window), [overlay] on top of them (ring ticks). Callers set the semantics.
 */
@Composable
internal fun StageChart(
    segments: List<StageSegment>,
    start: Instant,
    end: Instant,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    tickMinutes: List<Long> = HOUR_TICKS,
    band: StageChartBand? = null,
    underlay: DrawScope.(StageChartFrame) -> Unit = {},
    overlay: DrawScope.(StageChartFrame) -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val use24h = LocalUse24HourClock.current
    val colors = SleepSaverTheme.colors
    val rowColors = mapOf(
        StageRow.AWAKE to colors.stageAwake,
        StageRow.REM to colors.stageRem,
        StageRow.LIGHT to colors.stageLight,
        StageRow.DEEP to colors.stageDeep,
    )
    val unclassified = colors.textTertiary
    val labels = listOf(
        stringResource(R.string.review_stage_awake),
        stringResource(R.string.review_stage_rem),
        stringResource(R.string.review_stage_light),
        stringResource(R.string.review_stage_deep),
    )
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.textSecondary)
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = colors.textTertiary)
    val grid = colors.gridLine
    val connector = colors.textTertiary.copy(alpha = 0.45f)

    Canvas(modifier) {
        val labelLayouts = labels.map { measurer.measure(it, labelStyle) }
        val bandLayout = band?.let { measurer.measure(it.label, labelStyle) }
        val gutter = maxOf(labelLayouts.maxOf { it.size.width }, bandLayout?.size?.width ?: 0) + 12.dp.toPx()
        val axisHeight = 24.dp.toPx()
        val top = 4.dp.toPx()
        val gap = 6.dp.toPx()
        val bandSpace = band?.let { gap + it.height.toPx() } ?: 0f
        val plotLeft = gutter
        val plotRight = size.width - 4.dp.toPx()
        val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
        val rowHeight = (size.height - axisHeight - top - bandSpace) / StageRow.entries.size
        val barHeight = (rowHeight * 0.56f).coerceAtMost(18.dp.toPx())
        val spanMs = Duration.between(start, end).toMillis().coerceAtLeast(1L).toFloat()
        val rowsBottom = top + rowHeight * StageRow.entries.size
        val frame = StageChartFrame(plotLeft, plotRight, top, rowsBottom, start, spanMs)
        fun rowCenter(row: StageRow): Float = top + rowHeight * (row.ordinal + 0.5f)

        // Row labels and dotted guides.
        StageRow.entries.forEachIndexed { i, row ->
            val layout = labelLayouts[i]
            val cy = rowCenter(row)
            drawText(layout, topLeft = Offset(0f, cy - layout.size.height / 2f))
            drawLine(
                grid,
                Offset(plotLeft, cy),
                Offset(plotRight, cy),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())),
            )
        }

        underlay(frame)

        // Steps between consecutive stretches.
        segments.zipWithNext().forEach { (a, b) ->
            if (Duration.between(a.end, b.start).abs() <= Duration.ofMinutes(1) && a.row != b.row) {
                val cx = frame.x(b.start)
                drawLine(connector, Offset(cx, rowCenter(a.row)), Offset(cx, rowCenter(b.row)), strokeWidth = 1.dp.toPx())
            }
        }

        // Bars.
        val minWidth = 2.dp.toPx()
        val radius = CornerRadius(3.dp.toPx())
        segments.forEach { segment ->
            val left = frame.x(segment.start)
            val width = (frame.x(segment.end) - left).coerceAtLeast(minWidth)
            val color = if (segment.unclassified) unclassified else rowColors.getValue(segment.row)
            drawRoundRect(
                color = color,
                topLeft = Offset(left, rowCenter(segment.row) - barHeight / 2f),
                size = Size(width, barHeight),
                cornerRadius = radius,
            )
        }

        // The extra band, with its label in the gutter.
        var axisTop = rowsBottom + gap
        if (band != null && bandLayout != null) {
            val bandTop = rowsBottom + gap
            val bandBottom = bandTop + band.height.toPx()
            drawText(bandLayout, topLeft = Offset(0f, (bandTop + bandBottom) / 2f - bandLayout.size.height / 2f))
            band.draw(this, frame, bandTop, bandBottom)
            axisTop = bandBottom + gap
        }

        overlay(frame)

        // Time axis: whole steps of local time, strictly inside the span.
        val stepMinutes = tickMinutes.firstOrNull { plotWidth * it / (spanMs / 60_000f) >= 44.dp.toPx() } ?: tickMinutes.last()
        var tick = start.atZone(zone).truncatedTo(ChronoUnit.HOURS)
        while (tick.toInstant().isBefore(end)) {
            val minuteOfDay = tick.hour * 60L + tick.minute
            if (tick.toInstant().isAfter(start) && minuteOfDay % stepMinutes == 0L) {
                val cx = frame.x(tick.toInstant())
                drawLine(grid, Offset(cx, top), Offset(cx, axisTop - 4.dp.toPx()), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
                val layout = measurer.measure(formatClockAxis(tick.toInstant(), zone, use24h), axisStyle)
                val lx = (cx - layout.size.width / 2f).coerceIn(plotLeft, plotRight - layout.size.width)
                drawText(layout, topLeft = Offset(lx, axisTop))
            }
            tick = tick.plusMinutes(TICK_GRANULARITY_MINUTES)
        }
    }
}

/** Hour ticks, or every other hour when hours are too close (a whole night). */
internal val HOUR_TICKS: List<Long> = listOf(60L, 120L)

/** Half-hour ticks first, for short spans (the 90-minute Wake landing chart). */
internal val HALF_HOUR_TICKS: List<Long> = listOf(30L, 60L, 120L)

/** Ticks are looked for on this grid of local time; every step in the lists above is a multiple of it. */
private const val TICK_GRANULARITY_MINUTES = 30L

/** A night without stage data: one rounded block with its awake stretches cut out. */
@Composable
internal fun SleepTimelineBar(block: SleepBlock, modifier: Modifier = Modifier) {
    val colors = SleepSaverTheme.colors
    Canvas(modifier) {
        val spanMs = Duration.between(block.start, block.end).toMillis().coerceAtLeast(1L).toFloat()
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(colors.sleepBlock, cornerRadius = radius)
        drawRoundRect(colors.sleepBlockStroke, cornerRadius = radius, style = Stroke(width = 1.dp.toPx()))
        block.awakePeriods.forEach { period ->
            val start = (Duration.between(block.start, period.start).toMillis() / spanMs).coerceIn(0f, 1f) * size.width
            val end = (Duration.between(block.start, period.end).toMillis() / spanMs).coerceIn(0f, 1f) * size.width
            drawRect(
                color = colors.stageAwake.copy(alpha = 0.6f),
                topLeft = Offset(start, size.height * 0.2f),
                size = Size((end - start).coerceAtLeast(2.dp.toPx()), size.height * 0.6f),
            )
        }
    }
}
