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
private const val SECONDS_PER_HOUR = 3_600.0

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

    /** The instant at [y], to the second (a pixel is ~20 s; finer would only be float noise). */
    fun timeAt(y: Float): Instant =
        range.start.plusSeconds(((y.toDouble() - topInset) / pxPerHour * SECONDS_PER_HOUR).roundToLong())

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

/**
 * A label that wants its top at [desiredTop] and points at [anchor] (the y of its time). [fixed] slots (the now
 * pill, night blocks) never move; every other label stays on the same side of them as its anchor, so nothing that
 * comes after "now" reads as before it.
 */
data class LabelSlot(
    val desiredTop: Float,
    val height: Float,
    val fixed: Boolean = false,
    val anchor: Float = desiredTop + height / 2,
)

/**
 * Places labels in one column without overlaps, each as close to its time as its neighbours allow. Fixed slots split
 * the column into free intervals; each movable label goes into the interval its anchor falls in, in anchor order (ties
 * keep input order). Inside an interval the labels keep that order and at least [gap] between them, and sit where the
 * sum of squared distances from their desired tops is smallest (pool adjacent violators): a lone label stays at its
 * time, and a crowd spreads evenly above and below its times instead of piling up under the first one, so no label
 * drifts far from its time. An interval too small for its labels hands its last ones to the next interval (and, at
 * the bottom, its first ones to the previous). Only when the whole column is too short may the lowest labels overlap.
 * Example: three 20-px labels that all want their top at 100, with a 5-px gap → 75, 100, 125.
 * Returns each label's top, in input order.
 */
fun placeLabels(slots: List<LabelSlot>, minTop: Float, maxBottom: Float, gap: Float): List<Float> {
    if (slots.isEmpty()) return emptyList()
    val tops = FloatArray(slots.size) { slots[it].desiredTop }

    // Fixed slots, merged where they touch, are obstacles; an obstacle counts from its earliest anchor.
    val obstacles = mutableListOf<Obstacle>()
    slots.filter { it.fixed }.sortedBy { it.desiredTop }.forEach { slot ->
        val last = obstacles.lastOrNull()
        if (last != null && slot.desiredTop < last.bottom + gap) {
            obstacles[obstacles.lastIndex] = Obstacle(
                top = last.top,
                bottom = maxOf(last.bottom, slot.desiredTop + slot.height),
                anchor = minOf(last.anchor, slot.anchor),
            )
        } else {
            obstacles += Obstacle(slot.desiredTop, slot.desiredTop + slot.height, slot.anchor)
        }
    }

    // The free intervals around the obstacles, each with the labels anchored in it, in anchor order.
    val starts = listOf(minTop) + obstacles.map { it.bottom + gap }
    val ends = obstacles.map { it.top - gap } + maxBottom
    val groups = List(starts.size) { ArrayDeque<Int>() }
    slots.indices
        .filter { !slots[it].fixed }
        .sortedWith(compareBy({ slots[it].anchor }, { it }))
        .forEach { i -> groups[obstacles.count { it.anchor <= slots[i].anchor }].addLast(i) }

    fun needed(group: Collection<Int>): Float =
        group.sumOf { slots[it].height.toDouble() }.toFloat() + gap * (group.size - 1).coerceAtLeast(0)

    fun capacity(k: Int): Float = ends[k] - starts[k]

    // Overfull intervals pass labels on: their latest ones down first, then (from the bottom) their earliest up.
    for (k in 0 until groups.lastIndex) {
        while (groups[k].isNotEmpty() && needed(groups[k]) > capacity(k)) groups[k + 1].addFirst(groups[k].removeLast())
    }
    for (k in groups.lastIndex downTo 1) {
        while (groups[k].isNotEmpty() && needed(groups[k]) > capacity(k)) groups[k - 1].addLast(groups[k].removeFirst())
    }

    groups.forEachIndexed { k, group ->
        if (group.isEmpty()) return@forEachIndexed
        val labels = group.toList()
        // Packed tightly, each label's top sits this far below the first one's.
        val offsets = FloatArray(labels.size)
        for (j in 1 until labels.size) offsets[j] = offsets[j - 1] + slots[labels[j - 1]].height + gap
        // The first label's top for each label to sit at its desired top; pooled where that would break the order.
        val pools = ArrayList<Pool>()
        labels.forEachIndexed { j, i ->
            pools += Pool(sum = slots[i].desiredTop - offsets[j], count = 1)
            while (pools.size > 1 && pools[pools.lastIndex - 1].mean > pools.last().mean) {
                val last = pools.removeAt(pools.lastIndex)
                pools[pools.lastIndex] = pools.last() + last
            }
        }
        // Inside the interval; an interval that can't hold them all lets them run past its end, not above its start.
        val highest = ends[k] - (offsets.last() + slots[labels.last()].height)
        var j = 0
        pools.forEach { pool ->
            val base = maxOf(minOf(pool.mean, highest), starts[k])
            repeat(pool.count) {
                tops[labels[j]] = base + offsets[j]
                j++
            }
        }
    }
    return tops.toList()
}

private data class Obstacle(val top: Float, val bottom: Float, val anchor: Float)

/** Labels whose first-label tops were pooled into one run: [sum] of those tops over [count] labels. */
private data class Pool(val sum: Float, val count: Int) {
    val mean: Float get() = sum / count
    operator fun plus(other: Pool) = Pool(sum + other.sum, count + other.count)
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
