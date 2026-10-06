package com.sleepsaver.feature.progress.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.ui.components.SectionHeader
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.color
import com.sleepsaver.core.ui.components.label
import com.sleepsaver.core.ui.format.formatDuration
import com.sleepsaver.core.ui.format.formatHours1
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.progress.ProgressUiState
import com.sleepsaver.feature.progress.R
import com.sleepsaver.feature.progress.chart.DebtChart
import com.sleepsaver.feature.progress.chart.DebtPoint
import java.time.LocalDate

private val DebtPlotHeight = 200.dp

internal fun LazyListScope.sleepDebtItems(state: ProgressUiState.Ready) {
    item(key = "debt-chart") {
        SleepSaverCard(modifier = Modifier.fillMaxWidth(), contentPadding = ChartCardPadding) {
            DebtChartView(state.debt, state.today)
            Spacer(Modifier.height(Spacing.l))
            Legend(Modifier.padding(horizontal = 12.dp)) {
                DebtStatus.entries.forEach { status ->
                    val color = status.color
                    LegendItem(status.label()) { drawCircle(color, radius = 4.dp.toPx(), center = center) }
                }
            }
        }
    }
    item(key = "debt-list-header") {
        SectionHeader(
            title = stringResource(R.string.progress_all_sleep_debt),
            modifier = Modifier.padding(top = Spacing.xl, bottom = Spacing.s),
        )
    }
    item(key = "debt-list") {
        SleepSaverCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
            state.debt.points.asReversed().forEachIndexed { index, point ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                DebtRowItem(point, state.today)
            }
        }
    }
}

@Composable
internal fun DebtChartView(chart: DebtChart, today: LocalDate, modifier: Modifier = Modifier) {
    ChartTextScale {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val columnWidth = (maxWidth - AxisWidth) / chart.points.size.coerceAtLeast(1)
            DebtPlot(chart, today, weekdayLabels(chart.points.map { it.date }, columnWidth))
        }
    }
}

@Composable
private fun DebtPlot(chart: DebtChart, today: LocalDate, days: List<String>) {
    val colors = SleepSaverTheme.colors
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val tide = MaterialTheme.colorScheme.primary
    val card = colors.card
    val onSurface = MaterialTheme.colorScheme.onSurface
    val statusColors = DebtStatus.entries.associateWith { it.color }
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(DebtPlotHeight),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val h = size.height
                    val step = size.width / chart.points.size
                    fun x(i: Int) = step * (i + 0.5f)
                    fun y(fraction: Float) = h - fraction * h
                    chart.tickHours.forEach { hours ->
                        val yy = y(hours / chart.maxHours.toFloat())
                        drawLine(grid, Offset(0f, yy), Offset(size.width, yy), strokeWidth = 1.dp.toPx())
                    }
                    chart.thresholdY?.let { t ->
                        val yy = y(t)
                        drawLine(
                            color = colors.textSecondary.copy(alpha = 0.7f),
                            start = Offset(0f, yy),
                            end = Offset(size.width, yy),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                        )
                    }
                    // Runs of consecutive days with a value: soft area + line.
                    runs(chart.points).forEach { run ->
                        val line = Path()
                        val area = Path().apply { moveTo(x(run.first()), h) }
                        run.forEachIndexed { k, i ->
                            val p = Offset(x(i), y(chart.points[i].y!!))
                            if (k == 0) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y)
                            area.lineTo(p.x, p.y)
                        }
                        if (run.size > 1) {
                            area.lineTo(x(run.last()), h)
                            area.close()
                            drawPath(area, Brush.verticalGradient(listOf(tide.copy(alpha = 0.16f), tide.copy(alpha = 0f)), startY = 0f, endY = h))
                            drawPath(line, tide.copy(alpha = 0.6f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                    chart.points.forEachIndexed { i, point ->
                        val fraction = point.y ?: return@forEachIndexed
                        val color = statusColors.getValue(point.status ?: DebtStatus.LOW)
                        val center = Offset(x(i), y(fraction))
                        val isToday = point.date == today
                        if (isToday) drawCircle(color.copy(alpha = 0.22f), radius = 9.dp.toPx(), center = center)
                        drawCircle(card, radius = 5.5.dp.toPx(), center = center)
                        if (point.nightMissing) {
                            drawCircle(color, radius = 3.5.dp.toPx(), center = center, style = Stroke(1.5.dp.toPx()))
                        } else {
                            drawCircle(color, radius = 4.dp.toPx(), center = center)
                        }
                    }
                }
                chart.thresholdY?.let { t ->
                    Text(
                        text = stringResource(R.string.progress_streak_limit, formatDuration(chart.threshold)),
                        style = chartLabelStyle,
                        color = colors.textSecondary,
                        modifier = Modifier
                            .offset(x = 4.dp, y = streakLabelOffset(t))
                            .clip(CircleShape)
                            .background(card)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                if (chart.isEmpty) {
                    // In the top band of the plot, clear of the streak line and its label.
                    Text(
                        text = stringResource(R.string.progress_debt_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 18.dp, start = 16.dp, end = 16.dp)
                            .clip(CircleShape)
                            .background(card)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                chart.points.forEach { point ->
                    Text(
                        text = point.debt?.let(::formatHours1) ?: stringResource(R.string.progress_missing_duration),
                        style = chartLabelStyle,
                        color = when {
                            point.debt == null -> colors.textTertiary
                            point.date == today -> onSurface
                            else -> colors.textSecondary
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .weight(1f)
                            .wrapContentWidth(unbounded = true),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth()) {
                chart.points.forEachIndexed { index, point ->
                    Text(
                        text = days[index],
                        style = chartLabelStyle,
                        color = if (point.date == today) onSurface else colors.textTertiary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .weight(1f)
                            .wrapContentWidth(unbounded = true),
                    )
                }
            }
        }
        AxisLabels(
            labels = chart.tickHours.map { (1f - it / chart.maxHours.toFloat()) to stringResource(R.string.progress_debt_axis_hours, it) },
            height = DebtPlotHeight,
        )
    }
}

/** Above the streak line at [fraction] of the plot height, or just below it when the line runs along the top. */
private fun streakLabelOffset(fraction: Float): Dp {
    val line = DebtPlotHeight * (1f - fraction)
    return if (line < 18.dp) line + 3.dp else line - 16.dp
}

/** Indices of consecutive points that have a value. */
private fun runs(points: List<DebtPoint>): List<List<Int>> {
    val result = mutableListOf<List<Int>>()
    var current = mutableListOf<Int>()
    points.forEachIndexed { i, point ->
        if (point.y != null) {
            current += i
        } else if (current.isNotEmpty()) {
            result += current
            current = mutableListOf()
        }
    }
    if (current.isNotEmpty()) result += current
    return result
}

@Composable
private fun DebtRowItem(point: DebtPoint, today: LocalDate) {
    val colors = SleepSaverTheme.colors
    val day = when (point.date) {
        today -> stringResource(R.string.progress_today)
        today.minusDays(1) -> stringResource(R.string.progress_yesterday)
        else -> weekdayFull(point.date)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.progress_day_and_date, day, monthDay(point.date)),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val status = point.status
            val (note, noteColor) = when {
                point.waiting -> stringResource(R.string.progress_debt_last_night_pending) to colors.textSecondary
                point.debt == null -> stringResource(R.string.progress_no_data) to colors.textTertiary
                point.nightMissing -> stringResource(R.string.progress_debt_night_missing, weekdayFull(point.date.minusDays(1))) to colors.textSecondary
                status != null -> status.label() to status.color
                else -> "" to Color.Unspecified
            }
            Text(note, style = MaterialTheme.typography.bodyMedium, color = noteColor, modifier = Modifier.padding(top = 2.dp))
        }
        val debt = point.debt
        if (debt != null) {
            Box(
                Modifier
                    .padding(end = 10.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background((point.status ?: DebtStatus.LOW).color),
            )
            val value = formatHours1(debt)
            val text = stringResource(R.string.progress_debt_hours, value)
            val emphasis = SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(
                text = buildAnnotatedString {
                    val at = text.indexOf(value)
                    if (at < 0) {
                        append(text)
                    } else {
                        append(text.substring(0, at))
                        withStyle(emphasis) { append(value) }
                        append(text.substring(at + value.length))
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        } else {
            Text(stringResource(R.string.progress_missing_duration), style = MaterialTheme.typography.bodyMedium, color = colors.textTertiary)
        }
    }
}
