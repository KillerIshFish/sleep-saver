package com.sleepsaver.feature.energy.timeline

import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.PhaseType
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong

private const val MILLIS_PER_HOUR = 3_600_000.0
private const val MILLIS_PER_MINUTE = 60_000L

/** The stretch of time the timeline shows, in elapsed (instant) time. */
data class TimelineRange(val start: Instant, val end: Instant) {
    init {
        require(end.isAfter(start)) { "Empty timeline range: $start..$end" }
    }

    val duration: Duration get() = Duration.between(start, end)

    fun contains(t: Instant): Boolean = !t.isBefore(start) && !t.isAfter(end)

    fun overlaps(from: Instant, to: Instant): Boolean = to.isAfter(start) && from.isBefore(end)

    companion object {
        /** Shown before last night's fell-asleep time. */
        val LeadIn: Duration = Duration.ofMinutes(30)

        /** Shown after tonight's melatonin window. */
        val LeadOut: Duration = Duration.ofMinutes(60)

        /** Extra sleep segments earlier than this before the main sleep don't stretch the range. */
        private val MaxEarlierSegment: Duration = Duration.ofHours(6)

        /**
         * From [LeadIn] before last night's sleep started (its earliest segment, or W − need when last night is
         * missing) to [LeadOut] after tonight's melatonin window ends: about 24–26 hours. Stretched so [now] is always
         * inside with some room on both sides (a late night owl, or a very short need).
         */
        fun of(schedule: EnergySchedule, lastNight: Night?, need: Duration, now: Instant): TimelineRange {
            val sleepStart = lastNight?.sleepStart() ?: (schedule.typicalWake.toInstant() - need)
            val melatoninEnd = schedule.phase(PhaseType.MELATONIN_WINDOW)?.end
                ?: (schedule.targetBedtime + Duration.ofMinutes(30))
            val start = minOf(sleepStart - LeadIn, now - LeadIn)
            val end = maxOf(melatoninEnd + LeadOut, now + LeadOut)
            return TimelineRange(start, if (end.isAfter(start)) end else start + Duration.ofHours(24))
        }

        private fun Night.sleepStart(): Instant? {
            val main = fellAsleep ?: main?.start ?: return null
            val earliestExtra = extraSegments.minOfOrNull { it.start }
                ?.takeIf { it.isBefore(main) && !it.isBefore(main - MaxEarlierSegment) }
            return earliestExtra ?: main
        }
    }
}

/** An axis tick on a whole or half local hour. */
data class TimelineTick(val time: Instant, val y: Float, val isHour: Boolean, val isMidnight: Boolean)

/**
 * Maps time to vertical position: time flows downward at [pxPerHour], measured in elapsed time, so a 23-hour or
 * 25-hour DST day is simply shorter or longer and its axis skips or repeats a local hour.
 */
class TimelineGeometry(
    val range: TimelineRange,
    val pxPerHour: Float,
    val topInset: Float = 0f,
    val bottomInset: Float = 0f,
) {
    init {
        require(pxPerHour > 0f) { "pxPerHour must be positive" }
    }

    /** Height of the range itself, without insets. */
    val contentHeight: Float = (range.duration.toMillis() / MILLIS_PER_HOUR * pxPerHour).toFloat()

    val height: Float = topInset + contentHeight + bottomInset

    val top: Float get() = topInset
    val bottom: Float get() = topInset + contentHeight

    fun yOf(t: Instant): Float =
        topInset + (Duration.between(range.start, t).toMillis() / MILLIS_PER_HOUR * pxPerHour).toFloat()

    /** [yOf] kept inside the range. */
    fun yOfClamped(t: Instant): Float = yOf(t).coerceIn(top, bottom)

    fun timeAt(y: Float): Instant =
        range.start.plusMillis(((y - topInset) / pxPerHour * MILLIS_PER_HOUR).roundToLong())

    /** Every whole and half local hour inside the range, in [zone] (any real UTC offset is a multiple of 15 min). */
    fun ticks(zone: ZoneId): List<TimelineTick> {
        val step = 15 * MILLIS_PER_MINUTE
        val first = Math.floorDiv(range.start.toEpochMilli() + step - 1, step) * step
        val out = mutableListOf<TimelineTick>()
        var ms = first
        while (ms <= range.end.toEpochMilli()) {
            val t = Instant.ofEpochMilli(ms)
            val local = t.atZone(zone).toLocalTime()
            if (local.minute == 0 || local.minute == 30) {
                out += TimelineTick(
                    time = t,
                    y = yOf(t),
                    isHour = local.minute == 0,
                    isMidnight = local.minute == 0 && local.hour == 0,
                )
            }
            ms += step
        }
        return out
    }
}

/** Energy 0..1 → x across [left]..[right]. */
class EnergyScale(val left: Float, val right: Float) {
    fun xOf(value: Float): Float = left + value.coerceIn(0f, 1f) * (right - left)
}

/**
 * Greedy lanes for spans that may overlap (a long sleep need pushes wind-down into the evening peak): each span
 * goes into the first lane whose previous span has ended. Returns the lane per span, in input order.
 */
fun assignLanes(spans: List<Pair<Instant, Instant>>): List<Int> {
    val laneEnds = mutableListOf<Instant>()
    val lanes = IntArray(spans.size)
    spans.indices.sortedWith(compareBy({ spans[it].first }, { it })).forEach { i ->
        val (start, end) = spans[i]
        val lane = laneEnds.indexOfFirst { !it.isAfter(start) }
        if (lane >= 0) {
            laneEnds[lane] = end
            lanes[i] = lane
        } else {
            laneEnds += end
            lanes[i] = laneEnds.lastIndex
        }
    }
    return lanes.toList()
}

/** A label that wants its top at [desiredTop]. [fixed] ones (the now pill, sleep blocks as obstacles) never move. */
data class LabelSlot(val desiredTop: Float, val height: Float, val fixed: Boolean = false)

/**
 * Places labels in one column without overlaps, keeping their order. Labels sit at their desired position when
 * there's room; otherwise they're pushed down (so a label never reads earlier than its time), and pushed up only
 * where a fixed slot or [maxBottom] leaves no room below. Returns each label's top, in input order.
 * When the labels can't all fit between [minTop] and [maxBottom], the lowest ones may overlap.
 */
fun placeLabels(slots: List<LabelSlot>, minTop: Float, maxBottom: Float, gap: Float): List<Float> {
    if (slots.isEmpty()) return emptyList()
    val order = slots.indices.sortedWith(compareBy({ slots[it].desiredTop }, { if (slots[it].fixed) 0 else 1 }, { it }))
    val tops = FloatArray(slots.size) { slots[it].desiredTop }

    // Push down.
    var cursor = minTop
    for (i in order) {
        val slot = slots[i]
        tops[i] = if (slot.fixed) slot.desiredTop else maxOf(slot.desiredTop, cursor)
        cursor = maxOf(cursor, tops[i] + slot.height + gap)
    }
    // Push up from the bottom and from fixed slots.
    var limit = maxBottom
    for (i in order.asReversed()) {
        val slot = slots[i]
        if (!slot.fixed) tops[i] = minOf(tops[i], limit - slot.height)
        limit = minOf(limit, tops[i] - gap)
    }
    // Never above the top edge.
    cursor = minTop
    for (i in order) {
        val slot = slots[i]
        if (!slot.fixed) tops[i] = maxOf(tops[i], cursor)
        cursor = maxOf(cursor, tops[i] + slot.height + gap)
    }
    return tops.toList()
}

/**
 * Where a long press inside the sleep block starts the added awake time: [pressed] rounded to the nearest
 * [stepMinutes] (every UTC offset is a multiple of 15 min, so this is also the local clock), kept inside
 * [blockStart]..[blockEnd] − [minLength].
 */
fun awakeStartFor(
    pressed: Instant,
    blockStart: Instant,
    blockEnd: Instant,
    stepMinutes: Int = 5,
    minLength: Duration = Duration.ofMinutes(5),
): Instant {
    val step = stepMinutes * MILLIS_PER_MINUTE
    val rounded = Instant.ofEpochMilli(Math.floorDiv(pressed.toEpochMilli() + step / 2, step) * step)
    val latest = maxOf(blockStart, blockEnd - minLength)
    return rounded.coerceIn(blockStart, latest)
}

/** The longest awake stretch (whole minutes, within [min]..[max]) that still ends inside the block. */
fun maxAwakeMinutes(start: Instant, blockEnd: Instant, min: Int = 5, max: Int = 120): Int {
    val available = (Duration.between(start, blockEnd).toMillis() / MILLIS_PER_MINUTE).toInt()
    return available.coerceIn(min, max)
}

private fun Instant.coerceIn(min: Instant, max: Instant): Instant = when {
    isBefore(min) -> min
    isAfter(max) -> max
    else -> this
}
