package com.sleepsaver.feature.progress.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.components.SectionHeader
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.format.LocalUse24HourClock
import com.sleepsaver.core.ui.format.formatClockAxis
import com.sleepsaver.core.ui.format.formatDuration
import com.sleepsaver.core.ui.format.formatNightLabel
import com.sleepsaver.core.ui.format.formatTimeRange
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.progress.NightRow
import com.sleepsaver.feature.progress.ProgressUiState
import com.sleepsaver.feature.progress.R
import com.sleepsaver.feature.progress.chart.BarSegment
import com.sleepsaver.feature.progress.chart.SegmentKind
import com.sleepsaver.feature.progress.chart.SleepTimesChart
import com.sleepsaver.feature.progress.chart.SleepTimesColumn
import java.time.LocalDate
import kotlin.math.min

private val PlotHeight = 280.dp
private val PillRowHeight = 26.dp
private val FadeLength = 18.dp
private val MinSegment = 2.dp

internal fun LazyListScope.sleepTimesItems(
    state: ProgressUiState.Ready,
    onOpenNight: (LocalDate) -> Unit,
    onAddMissingNight: (LocalDate) -> Unit,
) {
    item(key = "times-chart") {
        SleepSaverCard(modifier = Modifier.fillMaxWidth(), contentPadding = ChartCardPadding) {
            SleepTimesChartView(state.sleepTimes, state.today, onOpenNight, onAddMissingNight)
            Spacer(Modifier.height(Spacing.l))
            SleepTimesLegend(state.sleepTimes, Modifier.padding(horizontal = 12.dp))
        }
    }
    item(key = "times-list-header") {
        SectionHeader(
            title = stringResource(R.string.progress_all_sleep_times),
            modifier = Modifier.padding(top = Spacing.xl, bottom = Spacing.s),
        )
    }
    item(key = "times-list") {
        SleepSaverCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
            state.nightRows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                NightRowItem(row, state.today, onOpenNight, onAddMissingNight)
            }
        }
    }
}

@Composable
internal fun SleepTimesChartView(
    chart: SleepTimesChart,
    today: LocalDate,
    onOpenNight: (LocalDate) -> Unit,
    onAddMissingNight: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val use24h = LocalUse24HourClock.current
    ChartTextScale {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val columnWidth = (maxWidth - AxisWidth) / chart.columns.size.coerceAtLeast(1)
            val days = weekdayLabels(chart.columns.map { it.nightOf }, columnWidth)
            SleepTimesPlot(chart, days, today, grid, use24h, onOpenNight, onAddMissingNight)
        }
    }
}

@Composable
private fun SleepTimesPlot(
    chart: SleepTimesChart,
    days: List<String>,
    today: LocalDate,
    grid: Color,
    use24h: Boolean,
    onOpenNight: (LocalDate) -> Unit,
    onAddMissingNight: (LocalDate) -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(PlotHeight),
            ) {
                chart.ticks.forEach { tick ->
                    val y = tick.fraction * size.height
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
            }
            Row(Modifier.fillMaxWidth()) {
                chart.columns.forEachIndexed { index, column ->
                    NightColumnView(
                        column = column,
                        chart = chart,
                        day = days[index],
                        today = today,
                        isLatest = column.wakeDate == today,
                        onClick = { if (column.isMissing) onAddMissingNight(column.wakeDate) else onOpenNight(column.wakeDate) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        AxisLabels(chart.ticks.map { it.fraction to formatClockAxis(it.time, use24h) }, PlotHeight)
    }
}

@Composable
private fun NightColumnView(
    column: SleepTimesColumn,
    chart: SleepTimesChart,
    day: String,
    today: LocalDate,
    /** Last night: its label is highlighted. */
    isLatest: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SleepSaverTheme.colors
    val tide = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val description = nightDescription(column, today)
    val action = stringResource(if (column.isMissing) R.string.progress_add_this_night else R.string.progress_open_night)
    Column(
        modifier = modifier.chartColumnClick(description, action, onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(PlotHeight),
        ) {
            val barWidth = min(BarWidth.toPx(), size.width * 0.6f)
            if (column.isMissing) {
                drawPlaceholder(chart.placeholderTop, chart.placeholderBottom, barWidth, colors.textTertiary, colors.textSecondary, colors.card)
            }
            column.segments.forEach { segment ->
                val fill = when (segment.kind) {
                    SegmentKind.MAIN -> tide
                    SegmentKind.EXTRA -> tide.copy(alpha = 0.45f)
                    SegmentKind.NAP -> colors.napBlock
                }
                drawSegment(segment, barWidth, fill, stroke = if (segment.kind == SegmentKind.NAP) colors.sleepBlockStroke else null)
            }
            val top = column.topMost
            if (column.isEdited && top != null) {
                val y = (top * size.height - 7.dp.toPx()).coerceAtLeast(3.dp.toPx())
                drawCircle(onSurface.copy(alpha = 0.9f), radius = 2.5.dp.toPx(), center = Offset(size.width / 2, y))
            }
        }
        if (chart.hasOffAxis) {
            FixedRow(PillRowHeight) {
                if (column.offAxis.isNotEmpty()) NapPill(formatDuration(column.offAxisTotal))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = day,
            style = chartLabelStyle,
            color = if (isLatest) onSurface else colors.textTertiary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.wrapContentWidth(unbounded = true),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = column.total?.let { formatDuration(it).replace(' ', '\n') } ?: stringResource(R.string.progress_missing_duration),
            style = chartLabelStyle,
            color = if (column.isMissing) colors.textTertiary else colors.textSecondary,
            textAlign = TextAlign.Center,
            minLines = 2,
            softWrap = false,
            modifier = Modifier.wrapContentWidth(unbounded = true),
        )
    }
}

@Composable
private fun nightDescription(column: SleepTimesColumn, today: LocalDate): String {
    val night = formatNightLabel(column.wakeDate, today)
    val total = column.total
    return if (column.isMissing || total == null) {
        stringResource(R.string.progress_missing_night_description, night)
    } else {
        stringResource(R.string.progress_night_description, night, monthDay(column.nightOf), formatDuration(total))
    }
}

@Composable
private fun NapPill(text: String) {
    Box(
        modifier = Modifier
            .wrapContentWidth(unbounded = true)
            .clip(CircleShape)
            .background(SleepSaverTheme.colors.napBlock)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(text, style = chartLabelStyle, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
    }
}

private fun DrawScope.drawSegment(segment: BarSegment, barWidth: Float, fill: Color, stroke: Color?) {
    val top = segment.top * size.height
    val bottom = maxOf(segment.bottom * size.height, top + MinSegment.toPx())
    val height = bottom - top
    val left = (size.width - barWidth) / 2
    val radius = CornerRadius(min(barWidth / 2, height / 2))
    val fade = min(FadeLength.toPx(), height / 2) / height
    val brush = if (segment.fadesTop || segment.fadesBottom) {
        val stops = buildList {
            if (segment.fadesTop) {
                add(0f to fill.copy(alpha = 0f))
                add(fade to fill)
            } else {
                add(0f to fill)
            }
            if (segment.fadesBottom) {
                add(1f - fade to fill)
                add(1f to fill.copy(alpha = 0f))
            } else {
                add(1f to fill)
            }
        }
        Brush.verticalGradient(*stops.toTypedArray(), startY = top, endY = bottom)
    } else {
        SolidColor(fill)
    }
    drawRoundRect(brush, topLeft = Offset(left, top), size = Size(barWidth, height), cornerRadius = radius)
    if (stroke != null) {
        drawRoundRect(stroke, topLeft = Offset(left, top), size = Size(barWidth, height), cornerRadius = radius, style = Stroke(1.dp.toPx()))
    }
}

/** A dashed outline where the night would be, with a "+" in it (on a [background] disc that hides the grid). */
private fun DrawScope.drawPlaceholder(
    topFraction: Float,
    bottomFraction: Float,
    barWidth: Float,
    outline: Color,
    plus: Color,
    background: Color,
) {
    val width = barWidth + 4.dp.toPx()
    val top = topFraction * size.height
    val height = (bottomFraction - topFraction) * size.height
    val left = (size.width - width) / 2
    drawRoundRect(
        color = outline.copy(alpha = 0.8f),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(width / 2),
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
    val center = Offset(size.width / 2, top + height / 2)
    val arm = 4.dp.toPx()
    drawCircle(background, radius = arm + 2.dp.toPx(), center = center)
    val strokeWidth = 1.5.dp.toPx()
    drawLine(plus, center - Offset(arm, 0f), center + Offset(arm, 0f), strokeWidth, StrokeCap.Round)
    drawLine(plus, center - Offset(0f, arm), center + Offset(0f, arm), strokeWidth, StrokeCap.Round)
}

@Composable
private fun SleepTimesLegend(chart: SleepTimesChart, modifier: Modifier = Modifier) {
    val tide = MaterialTheme.colorScheme.primary
    val colors = SleepSaverTheme.colors
    val onSurface = MaterialTheme.colorScheme.onSurface
    val columns = chart.columns
    val hasMain = columns.any { column -> column.segments.any { it.kind == SegmentKind.MAIN } }
    val hasExtra = columns.any { column -> column.segments.any { it.kind == SegmentKind.EXTRA } }
    val hasNap = chart.hasOffAxis || columns.any { column -> column.segments.any { it.kind == SegmentKind.NAP } }
    Legend(modifier) {
        if (hasMain) LegendItem(stringResource(R.string.progress_legend_asleep)) { pill(tide) }
        if (hasExtra) LegendItem(stringResource(R.string.progress_legend_extra)) { pill(tide.copy(alpha = 0.45f)) }
        if (hasNap) LegendItem(stringResource(R.string.progress_legend_nap)) { pill(colors.napBlock, colors.sleepBlockStroke) }
        if (columns.any { it.isEdited }) {
            LegendItem(stringResource(R.string.progress_legend_edited)) {
                drawCircle(onSurface.copy(alpha = 0.9f), radius = 2.5.dp.toPx(), center = center)
            }
        }
        if (columns.any { it.isMissing }) {
            LegendItem(stringResource(R.string.progress_legend_missing)) {
                drawRoundRect(
                    color = colors.textTertiary,
                    cornerRadius = CornerRadius(size.height / 2),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
                )
            }
        }
    }
}

private fun DrawScope.pill(fill: Color, stroke: Color? = null) {
    drawRoundRect(fill, cornerRadius = CornerRadius(size.height / 2))
    if (stroke != null) drawRoundRect(stroke, cornerRadius = CornerRadius(size.height / 2), style = Stroke(1.dp.toPx()))
}

@Composable
private fun NightRowItem(
    row: NightRow,
    today: LocalDate,
    onOpenNight: (LocalDate) -> Unit,
    onAddMissingNight: (LocalDate) -> Unit,
) {
    val colors = SleepSaverTheme.colors
    val use24h = LocalUse24HourClock.current
    val action = stringResource(if (row.isMissing) R.string.progress_add_this_night else R.string.progress_open_night)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = action, role = Role.Button) {
                if (row.isMissing) onAddMissingNight(row.wakeDate) else onOpenNight(row.wakeDate)
            }
            .semantics(mergeDescendants = true) {}
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatNightLabel(row.wakeDate, today), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                if (row.isEdited) {
                    Box(
                        Modifier
                            .padding(start = 8.dp)
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)),
                    )
                    Text(
                        text = stringResource(R.string.progress_edited),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(start = 5.dp),
                    )
                }
            }
            val fellAsleep = row.fellAsleep
            val wokeUp = row.wokeUp
            Text(
                text = if (row.isMissing || fellAsleep == null || wokeUp == null) {
                    stringResource(R.string.progress_no_data)
                } else {
                    formatTimeRange(fellAsleep, wokeUp, use24h)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (row.isMissing) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.progress_add),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        } else {
            Text(
                text = row.total?.let(::formatDuration).orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
