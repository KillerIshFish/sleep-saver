package com.sleepsaver.feature.progress.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Width of the clock / hours labels on the right of every chart. */
internal val AxisWidth = 26.dp

/** Horizontal padding inside a chart card: tighter than other cards so 14 columns get room. */
internal val ChartCardPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 20.dp, bottom = 16.dp)

/**
 * Text inside charts grows with the system font size only up to this factor: 14 columns can't fit bigger labels.
 * Every number in a chart is also in the list below it, which scales fully.
 */
private const val MAX_CHART_FONT_SCALE = 1.1f

/** Bars on the 14-column charts. */
internal val BarWidth = 10.dp

/** Day names and months stay English like the rest of the copy (see core:ui DateFormat). */
private val English = Locale.US

/** "SAT" */
internal fun weekdayShort(date: LocalDate): String =
    date.dayOfWeek.getDisplayName(DateTextStyle.SHORT, English).uppercase(English)

/** "S" */
internal fun weekdayNarrow(date: LocalDate): String = date.dayOfWeek.getDisplayName(DateTextStyle.NARROW, English)

/** "Saturday" */
internal fun weekdayFull(date: LocalDate): String = date.dayOfWeek.getDisplayName(DateTextStyle.FULL, English)

/** "Oct 6" */
internal fun monthDay(date: LocalDate): String = "${date.month.getDisplayName(DateTextStyle.SHORT, English)} ${date.dayOfMonth}"

/** Small chart labels: tighter than labelSmall so "WED" and "12.4" fit a 14-column chart on a phone. */
internal val chartLabelStyle: TextStyle
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, lineHeight = 12.sp, letterSpacing = 0.sp)

/** Caps the font scale for [content] at [MAX_CHART_FONT_SCALE] (see there). */
@Composable
internal fun ChartTextScale(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (density.fontScale <= MAX_CHART_FONT_SCALE) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides Density(density.density, MAX_CHART_FONT_SCALE), content = content)
    }
}

/**
 * Weekday labels for chart columns [columnWidth] wide: "WED", or "W" when three letters don't fit (large fonts,
 * narrow screens), so neighbouring labels never run into each other.
 */
@Composable
internal fun weekdayLabels(dates: List<LocalDate>, columnWidth: Dp): List<String> {
    val measurer = rememberTextMeasurer()
    val style = chartLabelStyle
    val density = LocalDensity.current
    val fits = remember(measurer, style, density, columnWidth) {
        val widest = WIDEST_WEEKDAYS.maxOf { measurer.measure(it, style).size.width }
        widest <= with(density) { (columnWidth - 2.dp).toPx() }
    }
    return dates.map { if (fits) weekdayShort(it) else weekdayNarrow(it) }
}

private val WIDEST_WEEKDAYS = listOf("WED", "MON", "SUN")

/**
 * Right-hand axis labels, each centred on its grid line. [labels] pairs a fraction of [height] from the top
 * with its text. Labels on the first and last line overhang the plot by half a line, so leave room around it.
 */
@Composable
internal fun AxisLabels(labels: List<Pair<Float, String>>, height: Dp, modifier: Modifier = Modifier) {
    val style = chartLabelStyle
    val color = SleepSaverTheme.colors.textTertiary
    Layout(
        content = { labels.forEach { (_, text) -> Text(text, style = style, color = color, maxLines = 1, softWrap = false) } },
        modifier = modifier
            .width(AxisWidth)
            .height(height),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val plot = height.roundToPx()
        val start = 6.dp.roundToPx()
        layout(constraints.maxWidth, plot) {
            placeables.forEachIndexed { index, placeable ->
                val y = (labels[index].first * plot - placeable.height / 2f).roundToInt()
                placeable.place(start, y)
            }
        }
    }
}

/** A legend entry: a small swatch drawn by [swatch] and its label. */
@Composable
internal fun LegendItem(label: String, swatch: DrawScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 14.dp, height = 10.dp), onDraw = swatch)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = SleepSaverTheme.colors.textSecondary,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Legend(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/**
 * Makes one chart column a single accessible button: it reads as [description] instead of its loose labels,
 * and [actionLabel] names what a tap does. Not clipped, so pills and labels wider than a column still show.
 */
internal fun Modifier.chartColumnClick(description: String, actionLabel: String, action: () -> Unit): Modifier =
    this
        .clickable(onClickLabel = actionLabel, role = Role.Button, onClick = action)
        .clearAndSetSemantics {
            contentDescription = description
            role = Role.Button
            onClick(label = actionLabel) {
                action()
                true
            }
        }
