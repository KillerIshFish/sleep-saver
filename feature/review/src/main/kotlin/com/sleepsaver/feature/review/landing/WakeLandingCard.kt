package com.sleepsaver.feature.review.landing

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.data.repository.MovementTrace
import com.sleepsaver.core.domain.alarm.AlarmTuning
import com.sleepsaver.core.domain.alarm.LandingStage
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.alarm.TuningReason
import com.sleepsaver.core.domain.alarm.WakeLanding
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.ui.components.SectionCard
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.keepTimesTogether
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.review.R
import com.sleepsaver.feature.review.common.alignTextStart
import com.sleepsaver.feature.review.detail.HALF_HOUR_TICKS
import com.sleepsaver.feature.review.detail.StageChart
import com.sleepsaver.feature.review.detail.StageChartBand
import com.sleepsaver.feature.review.detail.StageChartFrame
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private val ChartHeight = 132.dp
private val CompactChartHeight = 112.dp
private val MovementBandHeight = 24.dp
private const val MAX_CHART_GROWTH = 1.6f

/**
 * Wake landing: where this morning's planned alarm landed on the watch's hypnogram, in the watch's words only ("Watch
 * estimate: light sleep when it rang"). A mini stage chart around the ring with the window shaded and ticks for the
 * ring, snoozes and stop; the ring, reason, stage and window lines; and, when the restless trigger was retuned this
 * week, a note with "Reset to default". No grades, no red.
 *
 * [compact] (Night detail) draws a shorter chart and leaves the tuning note out; [movement] adds the phone's movement
 * as a 24 dp band under the chart, on the same time axis.
 */
@Composable
internal fun WakeLandingCard(
    landing: WakeLanding,
    night: Night?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    movement: List<MovementTrace> = emptyList(),
    tuningNote: AlarmTuning? = null,
    onResetTuning: () -> Unit = {},
) {
    val secondary = SleepSaverTheme.colors.textSecondary
    SectionCard(title = stringResource(R.string.review_landing_title), modifier = modifier.fillMaxWidth()) {
        if (landing.hasStages) {
            val chart = remember(landing, night, movement) { LandingChart.of(landing, night, movement) }
            LandingChartView(chart, landing, compact)
            LandingLegend(chart)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = ringLine(landing),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            landing.event.reason.reasonRes()?.let { res ->
                Text(stringResource(res), style = MaterialTheme.typography.bodyMedium, color = secondary)
            }
            if (landing.hasStages) {
                Text(stringResource(landing.stageAtRing.stageRes()), style = MaterialTheme.typography.bodyMedium, color = secondary)
                landing.windowDeepShare?.let { share ->
                    Text(
                        text = stringResource(R.string.review_landing_window_deep, (share * 100).roundToInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondary,
                    )
                }
            } else {
                Text(stringResource(R.string.review_landing_no_stages), style = MaterialTheme.typography.bodyMedium, color = secondary)
            }
        }
        if (!compact && tuningNote != null) TuningNote(tuningNote, onResetTuning)
    }
}

/** The tuning row Wake landing explains: a RAISED/LOWERED change in the last `tuningNoteDays`; a RESET reads as none. */
internal fun AlarmTuning?.noteAsOf(now: Instant, config: SleepConfig = SleepConfig.DEFAULT): AlarmTuning? =
    this?.takeIf { it.reason != TuningReason.RESET && now.isBefore(it.changedAt + Duration.ofDays(config.surfacing.tuningNoteDays)) }

/** "Rang 6:11 AM · 19 min early · 1 snooze · stopped 6:20 AM", or "Rang 6:30 AM at your wake time". */
@Composable
private fun ringLine(landing: WakeLanding): String {
    val event = landing.event
    val rang = clockTime(event.firstRingAt, event.offset)
    val onTime = event.firstRingAt.truncatedTo(ChronoUnit.MINUTES) == event.target.truncatedTo(ChronoUnit.MINUTES)
    val parts = buildList {
        when {
            landing.minutesEarly > 0 -> {
                add(stringResource(R.string.review_landing_rang, rang))
                add(stringResource(R.string.review_landing_early, landing.minutesEarly))
            }
            onTime -> add(stringResource(R.string.review_landing_rang_on_time, rang))
            // A catch-up ring after a restart came after the wake time; the reason line says so.
            else -> add(stringResource(R.string.review_landing_rang, rang))
        }
        val snoozes = event.snoozeTimes.size
        if (snoozes > 0) add(pluralStringResource(R.plurals.review_landing_snoozes, snoozes, snoozes))
        event.stoppedAt?.let { add(stringResource(R.string.review_landing_stopped, clockTime(it, event.offset))) }
    }
    return parts.reduce { line, part -> stringResource(R.string.review_joined, line, part) }.keepTimesTogether()
}

@StringRes
private fun RingReason.reasonRes(): Int? = when (this) {
    RingReason.RESTLESS -> R.string.review_landing_reason_restless
    RingReason.DEADLINE -> R.string.review_landing_reason_deadline
    RingReason.PLACEMENT_FALLBACK -> R.string.review_landing_reason_placement
    RingReason.SENSING_UNAVAILABLE -> R.string.review_landing_reason_sensing
    RingReason.FIXED_TIME -> R.string.review_landing_reason_fixed
    RingReason.CATCH_UP -> R.string.review_landing_reason_catch_up
    // Not the first ring of a planned alarm: nothing to explain.
    RingReason.SNOOZE_END, RingReason.CATNAP_END, RingReason.TEST, RingReason.BACKUP -> null
}

@StringRes
private fun LandingStage.stageRes(): Int = when (this) {
    LandingStage.LIGHT -> R.string.review_landing_stage_light
    LandingStage.REM -> R.string.review_landing_stage_rem
    LandingStage.AWAKE -> R.string.review_landing_stage_awake
    LandingStage.DEEP -> R.string.review_landing_stage_deep
    LandingStage.UNSCORED -> R.string.review_landing_stage_unscored
}

/** Why the alarm retuned itself this week, counting the rings that set it off ("after 3 rings"); null after a reset. */
@Composable
@ReadOnlyComposable
internal fun tuningText(tuning: AlarmTuning, config: SleepConfig = SleepConfig.DEFAULT): String? = when (tuning.reason) {
    TuningReason.RAISED_AFTER_DEEP_RINGS -> config.surfacing.tuneRaiseDeepRings.let { rings ->
        pluralStringResource(R.plurals.review_landing_tuned_raised, rings, rings)
    }
    TuningReason.LOWERED_AFTER_LATE_RINGS -> config.surfacing.tuneLowerDeadlineRings.let { rings ->
        pluralStringResource(R.plurals.review_landing_tuned_lowered, rings, rings)
    }
    TuningReason.RESET -> null
}

@Composable
private fun TuningNote(tuning: AlarmTuning, onReset: () -> Unit) {
    val text = tuningText(tuning) ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
            .padding(start = Spacing.l, end = Spacing.l, top = Spacing.m, bottom = Spacing.xs),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        TextLinkButton(
            text = stringResource(R.string.review_landing_reset),
            onClick = onReset,
            modifier = Modifier.alignTextStart(),
        )
    }
}

/** The mini hypnogram: the window shaded behind the bars, ring / snooze / stop ticks on top, movement underneath. */
@Composable
private fun LandingChartView(chart: LandingChart, landing: WakeLanding, compact: Boolean) {
    val colors = SleepSaverTheme.colors
    val tide = MaterialTheme.colorScheme.primary
    val stopColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    val zone = landing.event.offset
    val description = stringResource(R.string.review_hypnogram_description, clockTime(chart.start, zone), clockTime(chart.end, zone))
    val band = if (chart.movement.isEmpty()) {
        null
    } else {
        StageChartBand(stringResource(R.string.review_landing_movement), MovementBandHeight) { frame, top, bottom ->
            drawMovement(chart, frame, top, bottom, tide.copy(alpha = 0.6f), colors.gridLine)
        }
    }
    // Large system fonts grow the row labels: give the rows room to match (up to 1.6×).
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, MAX_CHART_GROWTH)
    val plot = (if (compact) CompactChartHeight else ChartHeight) * fontScale
    StageChart(
        segments = chart.segments,
        start = chart.start,
        end = chart.end,
        zone = zone,
        tickMinutes = HALF_HOUR_TICKS,
        band = band,
        modifier = Modifier
            .fillMaxWidth()
            .height(if (band != null) plot + MovementBandHeight + 6.dp else plot)
            .semantics { contentDescription = description },
        underlay = { frame ->
            if (chart.hasWindow) {
                val left = frame.x(chart.windowStart)
                drawRect(
                    color = colors.wakeZone,
                    topLeft = Offset(left, frame.rowsTop),
                    size = Size(frame.x(chart.windowEnd) - left, frame.rowsBottom - frame.rowsTop),
                )
            }
        },
        overlay = { frame ->
            val bottom = frame.rowsBottom
            chart.snoozes.forEach { t ->
                val x = frame.x(t)
                drawLine(
                    tide.copy(alpha = 0.7f),
                    Offset(x, frame.rowsTop),
                    Offset(x, bottom),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                )
            }
            chart.stop?.let { t ->
                val x = frame.x(t)
                drawLine(stopColor, Offset(x, frame.rowsTop), Offset(x, bottom), strokeWidth = 1.5.dp.toPx())
            }
            val x = frame.x(chart.ring)
            drawLine(tide, Offset(x, frame.rowsTop), Offset(x, bottom), strokeWidth = 2.dp.toPx())
            drawCircle(tide, radius = 3.dp.toPx(), center = Offset(x, frame.rowsTop))
        },
    )
}

private fun DrawScope.drawMovement(chart: LandingChart, frame: StageChartFrame, top: Float, bottom: Float, bar: Color, base: Color) {
    drawLine(base, Offset(frame.plotLeft, bottom), Offset(frame.plotRight, bottom), strokeWidth = 1.dp.toPx())
    val height = bottom - top
    val minHeight = 1.dp.toPx()
    chart.movement.forEach { epoch ->
        val left = frame.x(epoch.start)
        val width = (frame.x(epoch.end) - left - 0.5.dp.toPx()).coerceAtLeast(1.dp.toPx())
        val h = (epoch.level * height).coerceAtLeast(minHeight)
        drawRoundRect(bar, topLeft = Offset(left, bottom - h), size = Size(width, h), cornerRadius = CornerRadius(1.dp.toPx()))
    }
}

/** "Your window", "Rang", "Snoozed", "Stopped": only what the chart shows. */
@Composable
private fun LandingLegend(chart: LandingChart) {
    val colors = SleepSaverTheme.colors
    val tide = MaterialTheme.colorScheme.primary
    val stopColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (chart.hasWindow) {
            LegendKey(stringResource(R.string.review_landing_legend_window)) {
                drawRoundRect(colors.wakeZone, cornerRadius = CornerRadius(2.dp.toPx()))
            }
        }
        LegendKey(stringResource(R.string.review_landing_legend_ring)) { tick(tide, 2.dp.toPx()) }
        if (chart.snoozes.isNotEmpty()) {
            LegendKey(stringResource(R.string.review_landing_legend_snooze)) {
                tick(tide.copy(alpha = 0.7f), 1.5.dp.toPx(), PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
            }
        }
        if (chart.stop != null) {
            LegendKey(stringResource(R.string.review_landing_legend_stop)) {
                tick(stopColor, 1.5.dp.toPx())
            }
        }
    }
}

@Composable
private fun LegendKey(label: String, swatch: DrawScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 12.dp, height = 12.dp), onDraw = swatch)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = SleepSaverTheme.colors.textSecondary,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

private fun DrawScope.tick(color: Color, width: Float, effect: PathEffect? = null) {
    drawLine(color, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), strokeWidth = width, pathEffect = effect)
}
