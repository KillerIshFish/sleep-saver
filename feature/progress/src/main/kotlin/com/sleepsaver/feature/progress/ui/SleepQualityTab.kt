package com.sleepsaver.feature.progress.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.format.formatDuration
import com.sleepsaver.core.ui.format.formatNightLabel
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.progress.ProgressUiState
import com.sleepsaver.feature.progress.R
import com.sleepsaver.feature.progress.chart.QualitySummary
import com.sleepsaver.feature.progress.chart.StageBar
import com.sleepsaver.feature.progress.chart.StageChart
import com.sleepsaver.feature.progress.chart.VitalTrend
import java.time.LocalDate
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

private val StagePlotHeight = 170.dp
private val TrendHeight = 64.dp

internal fun LazyListScope.sleepQualityItems(
    state: ProgressUiState.Ready,
    onOpenNight: (LocalDate) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val quality = state.quality
    item(key = "quality-stages") {
        SleepSaverCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.progress_stages_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp, bottom = Spacing.l),
            )
            StageChartView(quality.chart, state.today, onOpenNight)
            Spacer(Modifier.height(Spacing.l))
            StageLegend(quality.chart, Modifier.padding(horizontal = 8.dp))
        }
    }
    item(key = "quality-summary") {
        QualitySummaryGrid(quality, Modifier.padding(top = Spacing.cardGap))
    }
    if (quality.hasVitals) {
        quality.restingHeartRate?.let { trend ->
            item(key = "quality-rhr") {
                VitalCard(
                    title = stringResource(R.string.progress_resting_hr),
                    unit = stringResource(R.string.progress_bpm),
                    trend = trend,
                    modifier = Modifier.padding(top = Spacing.cardGap),
                )
            }
        }
        quality.heartRateVariability?.let { trend ->
            item(key = "quality-hrv") {
                VitalCard(
                    title = stringResource(R.string.progress_hrv),
                    unit = stringResource(R.string.progress_ms),
                    trend = trend,
                    modifier = Modifier.padding(top = Spacing.cardGap),
                )
            }
        }
    } else {
        item(key = "quality-vitals-hint") {
            VitalsHint(onOpenSettings, Modifier.padding(top = Spacing.cardGap))
        }
    }
}

@Composable
internal fun StageChartView(
    chart: StageChart,
    today: LocalDate,
    onOpenNight: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SleepSaverTheme.colors
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val maxSeconds = chart.maxHours * 3600f
    Row(modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(StagePlotHeight),
            ) {
                chart.tickHours.forEach { hours ->
                    val y = size.height * (1f - hours / chart.maxHours.toFloat())
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
            }
            Row(Modifier.fillMaxWidth()) {
                chart.bars.forEachIndexed { index, bar ->
                    val description = stageDescription(bar, today)
                    val columnModifier = if (bar.isMissing) {
                        Modifier.semantics(mergeDescendants = true) {}
                    } else {
                        Modifier.chartColumnClick(description, stringResource(R.string.progress_open_night)) { onOpenNight(bar.wakeDate) }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .then(columnModifier),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Canvas(
                            Modifier
                                .fillMaxWidth()
                                .height(StagePlotHeight),
                        ) {
                            drawStageBar(bar, maxSeconds, colors.stageDeep, colors.stageLight, colors.stageRem, colors.stageAwake, colors.textTertiary)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = weekdayShort(bar.wakeDate.minusDays(1)),
                            style = chartLabelStyle,
                            color = if (index == chart.bars.lastIndex) onSurface else colors.textTertiary,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.wrapContentWidth(unbounded = true),
                        )
                    }
                }
            }
        }
        AxisLabels(
            labels = chart.tickHours.drop(1).map { (1f - it / chart.maxHours.toFloat()) to stringResource(R.string.progress_debt_axis_hours, it) },
            height = StagePlotHeight,
        )
    }
}

@Composable
private fun stageDescription(bar: StageBar, today: LocalDate): String {
    val night = formatNightLabel(bar.wakeDate, today)
    return if (bar.hasStages) {
        listOf(
            night,
            "${stringResource(R.string.progress_stage_deep)} ${formatDuration(bar.deep)}",
            "${stringResource(R.string.progress_stage_light)} ${formatDuration(bar.light)}",
            "${stringResource(R.string.progress_stage_rem)} ${formatDuration(bar.rem)}",
            "${stringResource(R.string.progress_stage_awake)} ${formatDuration(bar.awake)}",
        ).joinToString(", ")
    } else {
        "$night, ${stringResource(R.string.progress_stage_none)}"
    }
}

private fun DrawScope.drawStageBar(
    bar: StageBar,
    maxSeconds: Float,
    deep: Color,
    light: Color,
    rem: Color,
    awake: Color,
    faint: Color,
) {
    val barWidth = min(BarWidth.toPx(), size.width * 0.6f)
    val left = (size.width - barWidth) / 2
    val h = size.height
    if (bar.isMissing) {
        drawLine(faint.copy(alpha = 0.6f), Offset(left + 1.dp.toPx(), h - 1.dp.toPx()), Offset(left + barWidth - 1.dp.toPx(), h - 1.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
        return
    }
    val total = (bar.total.seconds / maxSeconds * h).coerceAtMost(h)
    if (total <= 0f) return
    val top = h - total
    val shape = Path().apply {
        addRoundRect(RoundRect(left, top, left + barWidth, h, CornerRadius(min(barWidth / 2, total / 2))))
    }
    clipPath(shape) {
        if (!bar.hasStages) {
            drawRect(faint.copy(alpha = 0.28f), topLeft = Offset(left, top), size = Size(barWidth, total))
            return@clipPath
        }
        var bottom = h
        listOf(bar.deep to deep, bar.light to light, bar.rem to rem, bar.other to faint.copy(alpha = 0.5f), bar.awake to awake)
            .forEach { (duration, color) ->
                val part = duration.seconds / maxSeconds * h
                if (part > 0f) {
                    drawRect(color, topLeft = Offset(left, bottom - part), size = Size(barWidth, part))
                    bottom -= part
                }
            }
    }
}

@Composable
private fun StageLegend(chart: StageChart, modifier: Modifier = Modifier) {
    val colors = SleepSaverTheme.colors
    Legend(modifier) {
        LegendItem(stringResource(R.string.progress_stage_deep)) { dot(colors.stageDeep) }
        LegendItem(stringResource(R.string.progress_stage_light)) { dot(colors.stageLight) }
        LegendItem(stringResource(R.string.progress_stage_rem)) { dot(colors.stageRem) }
        LegendItem(stringResource(R.string.progress_stage_awake)) { dot(colors.stageAwake) }
        if (chart.bars.any { !it.isMissing && !it.hasStages }) {
            LegendItem(stringResource(R.string.progress_stage_none)) { dot(colors.textTertiary.copy(alpha = 0.45f)) }
        }
    }
}

private fun DrawScope.dot(color: Color) = drawCircle(color, radius = 4.dp.toPx(), center = center)

@Composable
private fun QualitySummaryGrid(quality: QualitySummary, modifier: Modifier = Modifier) {
    val none = stringResource(R.string.progress_no_value)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
            StatCard(
                label = stringResource(R.string.progress_efficiency),
                value = quality.averageEfficiency?.let { stringResource(R.string.progress_percent, (it * 100).roundToInt()) } ?: none,
                caption = stringResource(R.string.progress_efficiency_caption),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            StatCard(
                label = stringResource(R.string.progress_interruptions),
                value = quality.averageInterruptions?.let { String.format(Locale.US, "%.1f", it) } ?: none,
                caption = stringResource(R.string.progress_interruptions_caption),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
            StatCard(
                label = stringResource(R.string.progress_deep_sleep),
                value = quality.averageDeep?.let(::formatDuration) ?: none,
                caption = stringResource(R.string.progress_average_caption),
                swatch = SleepSaverTheme.colors.stageDeep,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            StatCard(
                label = stringResource(R.string.progress_rem_sleep),
                value = quality.averageRem?.let(::formatDuration) ?: none,
                caption = stringResource(R.string.progress_average_caption),
                swatch = SleepSaverTheme.colors.stageRem,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, caption: String, modifier: Modifier = Modifier, swatch: Color? = null) {
    SleepSaverCard(modifier.semantics(mergeDescendants = true) {}, contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (swatch != null) {
                Box(
                    Modifier
                        .padding(end = 6.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(swatch),
                )
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = SleepSaverTheme.colors.textSecondary)
        }
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = SleepSaverTheme.colors.textTertiary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun VitalCard(title: String, unit: String, trend: VitalTrend, modifier: Modifier = Modifier) {
    val colors = SleepSaverTheme.colors
    val tide = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    SleepSaverCard(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = stringResource(R.string.progress_vital_range, trend.min.roundToInt().toString(), trend.max.roundToInt().toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = trend.average.roundToInt().toString(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.progress_vital_average, unit),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 6.dp, bottom = 5.dp),
            )
        }
        Spacer(Modifier.height(Spacing.l))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(TrendHeight),
        ) {
            val step = size.width / trend.values.size
            val inset = 5.dp.toPx()
            val plot = size.height - 2 * inset
            fun x(i: Int) = step * (i + 0.5f)
            fun y(v: Double) = inset + plot * (1f - trend.y(v))
            listOf(0f, 0.5f, 1f).forEach { f ->
                val yy = inset + plot * f
                drawLine(grid, Offset(0f, yy), Offset(size.width, yy), strokeWidth = 1.dp.toPx())
            }
            val avgY = y(trend.average)
            drawLine(
                color = colors.textTertiary.copy(alpha = 0.7f),
                start = Offset(0f, avgY),
                end = Offset(size.width, avgY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
            )
            var path: Path? = null
            trend.values.forEachIndexed { i, value ->
                if (value == null) {
                    path?.let { drawPath(it, tide.copy(alpha = 0.7f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                    path = null
                } else {
                    val p = Offset(x(i), y(value))
                    path = (path ?: Path().apply { moveTo(p.x, p.y) }).apply { lineTo(p.x, p.y) }
                }
            }
            path?.let { drawPath(it, tide.copy(alpha = 0.7f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)) }
            trend.values.forEachIndexed { i, value ->
                if (value != null) {
                    val c = Offset(x(i), y(value))
                    drawCircle(colors.card, radius = 4.dp.toPx(), center = c)
                    drawCircle(tide, radius = 2.75.dp.toPx(), center = c)
                }
            }
        }
    }
}

@Composable
private fun VitalsHint(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val tide = MaterialTheme.colorScheme.primary
    SleepSaverCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(tide.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.MonitorHeart, contentDescription = null, tint = tide, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text(
                    text = stringResource(R.string.progress_vitals_hint),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.progress_vitals_hint_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepSaverTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                TextLinkButton(
                    text = stringResource(R.string.progress_open_settings),
                    onClick = onOpenSettings,
                    modifier = Modifier.offset(x = (-12).dp),
                )
            }
        }
    }
}
