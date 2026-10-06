package com.sleepsaver.feature.progress.chart

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.AwakeKind
import com.sleepsaver.core.domain.model.AwakePeriod
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepBlock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** A labelled grid line: [fraction] of the plot height (0 = top) and the clock time it stands for. */
data class ClockTick(val fraction: Float, val time: LocalTime)

/**
 * The Sleep times chart's vertical clock axis: from [start] on the evening a night began (top) to [hours] later
 * the next morning (bottom). Positions are wall-clock, so a bar's ends line up with the times printed in the list
 * even on daylight-saving nights (a spring-forward night looks an hour longer than it was, which is what the
 * clock on the wall said).
 */
class SleepTimesAxis(
    val start: LocalTime = LocalTime.of(21, 0),
    val hours: Int = 14,
    private val tickEveryHours: Int = 2,
) {
    private val totalMinutes = hours * 60.0

    /** 0 at the top ([start] on [nightOf]), 1 at the bottom. Not clamped: values outside 0..1 are off the axis. */
    fun position(local: LocalDateTime, nightOf: LocalDate): Double =
        ChronoUnit.SECONDS.between(nightOf.atTime(start), local) / 60.0 / totalMinutes

    val ticks: List<ClockTick> =
        (0..hours step tickEveryHours).map { ClockTick(it / hours.toFloat(), start.plusHours(it.toLong())) }
}

enum class SegmentKind { MAIN, EXTRA, NAP }

/**
 * One drawn piece of sleep in a column, as fractions of the plot height (0 = top), already clipped to 0..1.
 * [fadesTop]/[fadesBottom] mark an end cut off by the axis: sleep continued past it.
 */
data class BarSegment(
    val top: Float,
    val bottom: Float,
    val kind: SegmentKind,
    val fadesTop: Boolean = false,
    val fadesBottom: Boolean = false,
)

/** A block that lies entirely outside the axis (an afternoon nap), drawn as a small pill under the bar. */
data class OffAxisBlock(val kind: SegmentKind, val asleep: Duration)

/** One night's column. [wakeDate] is the morning it ended; it's labelled with [nightOf]'s weekday. */
data class SleepTimesColumn(
    val wakeDate: LocalDate,
    val segments: List<BarSegment>,
    val offAxis: List<OffAxisBlock>,
    val isMissing: Boolean,
    val isEdited: Boolean,
    /** Main + extra + naps; null when the night is missing. */
    val total: Duration?,
) {
    val nightOf: LocalDate get() = wakeDate.minusDays(1)

    /** Sum of the off-axis blocks, for the pill under the bar. */
    val offAxisTotal: Duration get() = offAxis.fold(Duration.ZERO) { acc, block -> acc + block.asleep }

    /** Top of the highest visible segment, where the "edited" dot sits. */
    val topMost: Float? get() = segments.minOfOrNull { it.top }
}

data class SleepTimesChart(
    val columns: List<SleepTimesColumn>,
    val ticks: List<ClockTick>,
    /** Where a missing night's dashed "+" placeholder goes: the window's typical sleep, or 11 PM – 7 AM. */
    val placeholderTop: Float,
    val placeholderBottom: Float,
) {
    val hasOffAxis: Boolean get() = columns.any { it.offAxis.isNotEmpty() }
}

/** Lays out the Sleep times chart: one column per wake date, gaps for awake time, clipped at the axis. */
class SleepTimesGeometry(
    private val axis: SleepTimesAxis = SleepTimesAxis(),
    private val interruptionMin: Duration = SleepConfig.DEFAULT.interruptionMinDuration,
) {

    fun chart(dates: List<LocalDate>, nightsByDate: Map<LocalDate, Night>, zone: ZoneId): SleepTimesChart {
        val columns = dates.map { column(it, nightsByDate[it], zone) }
        val (top, bottom) = placeholderSpan(columns)
        return SleepTimesChart(columns, axis.ticks, top, bottom)
    }

    fun column(wakeDate: LocalDate, night: Night?, zone: ZoneId): SleepTimesColumn {
        if (night == null) {
            return SleepTimesColumn(wakeDate, emptyList(), emptyList(), isMissing = true, isEdited = false, total = null)
        }
        val nightOf = wakeDate.minusDays(1)
        val segments = mutableListOf<BarSegment>()
        val offAxis = mutableListOf<OffAxisBlock>()
        val blocks = buildList {
            night.main?.let { add(Triple(it, SegmentKind.MAIN, night.interruptions)) }
            night.extraSegments.forEach { add(Triple(it, SegmentKind.EXTRA, it.awakePeriods.significant())) }
            night.naps.forEach { add(Triple(it, SegmentKind.NAP, it.awakePeriods.significant())) }
        }
        for ((block, kind, awake) in blocks) {
            val pieces = pieces(block, awake, nightOf, zone)
            val visible = pieces.filter { (top, bottom) -> bottom > 0.0 && top < 1.0 }
            if (visible.isEmpty()) {
                offAxis += OffAxisBlock(kind, block.asleep)
                continue
            }
            visible.mapTo(segments) { (top, bottom) ->
                BarSegment(
                    top = top.coerceIn(0.0, 1.0).toFloat(),
                    bottom = bottom.coerceIn(0.0, 1.0).toFloat(),
                    kind = kind,
                    fadesTop = top < 0.0,
                    fadesBottom = bottom > 1.0,
                )
            }
        }
        return SleepTimesColumn(
            wakeDate = wakeDate,
            segments = segments.sortedBy { it.top },
            offAxis = offAxis,
            isMissing = !night.isPresent,
            isEdited = night.isEdited,
            total = night.totalSleep.takeIf { night.isPresent },
        )
    }

    /**
     * The asleep stretches of [block] (its span minus [awake]) as unclamped axis positions. Positions only move
     * down within a block, so the repeated hour of a fall-back night can't fold a bar back on itself.
     */
    internal fun pieces(block: SleepBlock, awake: List<AwakePeriod>, nightOf: LocalDate, zone: ZoneId): List<Pair<Double, Double>> {
        val gaps = awake
            .map { maxOf(it.start, block.start) to minOf(it.end, block.end) }
            .filter { (s, e) -> e > s }
            .sortedBy { it.first }
        val asleep = mutableListOf<Pair<Instant, Instant>>()
        var cursor = block.start
        for ((s, e) in gaps) {
            if (s > cursor) asleep += cursor to s
            if (e > cursor) cursor = e
        }
        if (block.end > cursor) asleep += cursor to block.end

        var floor = Double.NEGATIVE_INFINITY
        return asleep.map { (s, e) ->
            val top = maxOf(axis.position(localAt(s, block, zone), nightOf), floor)
            val bottom = maxOf(axis.position(localAt(e, block, zone), nightOf), top)
            floor = bottom
            top to bottom
        }
    }

    /** Median start and end of the window's main sleep, so placeholders sit where a night usually goes. */
    private fun placeholderSpan(columns: List<SleepTimesColumn>): Pair<Float, Float> {
        val mains = columns.filter { !it.isMissing }.mapNotNull { column ->
            val main = column.segments.filter { it.kind == SegmentKind.MAIN }
            if (main.isEmpty()) null else main.minOf { it.top } to main.maxOf { it.bottom }
        }
        if (mains.isEmpty()) return DefaultPlaceholder
        val top = mains.map { it.first }.median()
        val bottom = mains.map { it.second }.median()
        return if (bottom - top < MIN_PLACEHOLDER) DefaultPlaceholder else top to bottom
    }

    private fun List<AwakePeriod>.significant(): List<AwakePeriod> =
        filter { it.kind != AwakeKind.STAGE || it.duration >= interruptionMin }

    private companion object {
        /** 11 PM – 7 AM on the default axis. */
        val DefaultPlaceholder = (2f / 14f) to (10f / 14f)
        const val MIN_PLACEHOLDER = 0.15f
    }
}

/**
 * The local date-time of [t] inside [block]. Sleep data carries the offsets the block started and ended in; on a
 * night where they differ (DST, travel) the device zone tells which side of the change [t] is on when it agrees
 * with them, otherwise the nearer end's offset is used.
 */
internal fun localAt(t: Instant, block: SleepBlock, zone: ZoneId): LocalDateTime {
    val offset: ZoneOffset = when {
        block.startOffset == block.endOffset -> block.startOffset
        else -> zone.rules.getOffset(t).takeIf { it == block.startOffset || it == block.endOffset }
            ?: if (Duration.between(block.start, t) <= Duration.between(t, block.end)) block.startOffset else block.endOffset
    }
    return LocalDateTime.ofInstant(t, offset)
}

private fun List<Float>.median(): Float {
    val sorted = sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
}
