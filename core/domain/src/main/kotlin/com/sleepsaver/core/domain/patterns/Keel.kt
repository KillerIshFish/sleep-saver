package com.sleepsaver.core.domain.patterns

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepBlock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/** Keel bands: Even keel (≥ 80), Fair (70–79), Choppy (< 70). */
enum class KeelBand { EVEN, FAIR, CHOPPY }

/** One asleep stretch. */
data class SleepSpan(val start: Instant, val end: Instant)

/** One wake date's 24-h window [noon before, noon of] in the night's offset, with its asleep spans. */
data class KeelDay(val wakeDate: LocalDate, val windowStart: Instant, val windowEnd: Instant, val asleep: List<SleepSpan>)

/** Keel as of a date: still calibrating (fewer than minValidDays nights), or a score. */
sealed interface KeelResult {
    data class Calibrating(val validDays: Int, val needed: Int) : KeelResult

    data class Ready(
        val score: Int,
        val band: KeelBand,
        val validDays: Int,
        val onsetSd: Duration?,
        val wakeSd: Duration?,
        val asOf: LocalDate,
    ) : KeelResult
}

/**
 * Keel: a 0–100 regularity score (SRI on 1-min epochs of main sleep, naps excluded, minute pairs 24 elapsed hours
 * apart, over windowDays wake dates; DECISIONS A7). score = clamp(round(200·P − 100), 0, 100) with P the share of
 * matching valid pairs (§2.8).
 *
 *  - Days are the wake dates d in [asOf − (windowDays − 1), asOf]; d is valid when night d is present. Its window is
 *    [noon(d − 1), noon(d)) in the night's offset (wokeUpOffset ?: fellAsleepOffset ?: main.endOffset).
 *  - Asleep = the main block minus its awake periods (a manual night: [fellAsleep, wokeUp]); extra segments never
 *    count, naps only with includeNaps.
 *  - Fewer than minValidDays valid days → Calibrating. Onset and wake SD are circular SDs of the valid nights' local
 *    times (at least sdMinNights), rounded to the minute.
 *
 * Example: 14 nights asleep 23:00–07:00 → 100 (Even keel); bedtimes alternating 22:00 and 02:00 with the same wake
 * time → 67 (Choppy): every pair of neighbouring days disagrees for 4 of their 24 hours.
 */
class KeelCalculator(private val config: SleepConfig = SleepConfig.DEFAULT) {

    fun keel(nights: List<Night>, asOf: LocalDate): KeelResult {
        val keel = config.keel
        val from = asOf.minusDays(keel.windowDays - 1L)
        val valid = nights
            .filter { it.isPresent && !it.wakeDate.isBefore(from) && !it.wakeDate.isAfter(asOf) }
            .associateBy { it.wakeDate }
            .values
            .mapNotNull { night -> dayOf(night)?.let { night to it } }
        if (valid.size < keel.minValidDays) return KeelResult.Calibrating(valid.size, keel.minValidDays)
        // Valid days that never sit 24 h apart (every other night only) have no pairs: still calibrating.
        val score = score(valid.map { it.second }) ?: return KeelResult.Calibrating(valid.size, keel.minValidDays)
        return KeelResult.Ready(
            score = score,
            band = band(score),
            validDays = valid.size,
            onsetSd = sdOf(valid.mapNotNull { it.first.localFellAsleep() }),
            wakeSd = sdOf(valid.mapNotNull { it.first.localWokeUp() }),
            asOf = asOf,
        )
    }

    /**
     * The SRI core over explicit days (also used for the planned keel); null without valid pairs. Every day passed
     * counts as valid. A minute is sampled at its midpoint on a grid from the earliest window start to the latest
     * window end; it is asleep when it lies in any day's span and valid when it lies in any day's window. Pairs are
     * (t, t + 24 h elapsed) with both ends valid.
     */
    fun score(days: List<KeelDay>): Int? {
        if (days.isEmpty()) return null
        val gridStart = days.minOf { it.windowStart }.toEpochMilli()
        val gridEnd = days.maxOf { it.windowEnd }.toEpochMilli()
        val size = ceilDiv(gridEnd - gridStart, EPOCH_MS).toInt()
        if (size <= LAG) return null
        val asleep = BooleanArray(size)
        val valid = BooleanArray(size)
        for (day in days) {
            fill(valid, gridStart, day.windowStart, day.windowEnd)
            for (span in day.asleep) fill(asleep, gridStart, span.start, span.end)
        }
        var pairs = 0
        var matches = 0
        for (i in 0 until size - LAG) {
            if (valid[i] && valid[i + LAG]) {
                pairs++
                if (asleep[i] == asleep[i + LAG]) matches++
            }
        }
        if (pairs == 0) return null
        val p = matches.toDouble() / pairs
        return Math.round(200 * p - 100).coerceIn(0L, 100L).toInt()
    }

    fun band(score: Int): KeelBand = when {
        score >= config.keel.even -> KeelBand.EVEN
        score >= config.keel.fair -> KeelBand.FAIR
        else -> KeelBand.CHOPPY
    }

    /** Night d as a Keel day: its noon-to-noon window in the night's offset and its asleep spans. */
    private fun dayOf(night: Night): KeelDay? {
        val offset = night.wakeOffset() ?: return null
        return KeelDay(
            wakeDate = night.wakeDate,
            windowStart = noon(night.wakeDate.minusDays(1), offset),
            windowEnd = noon(night.wakeDate, offset),
            asleep = asleepSpans(night),
        )
    }

    private fun asleepSpans(night: Night): List<SleepSpan> {
        val main = night.main
        val mainSpans = if (night.isManual || main == null) {
            val start = night.fellAsleep
            val end = night.wokeUp
            if (start != null && end != null && end > start) listOf(SleepSpan(start, end)) else emptyList()
        } else {
            blockSpans(main)
        }
        return if (config.keel.includeNaps) mainSpans + night.naps.flatMap(::blockSpans) else mainSpans
    }

    private fun sdOf(times: List<LocalTime>): Duration? {
        if (times.size < config.keel.sdMinNights) return null
        return Duration.ofMinutes(Math.round(circularSdMinutes(times.map(::minuteOfDay))))
    }

    /** Marks the minutes whose midpoint lies in [from, to). */
    private fun fill(grid: BooleanArray, gridStart: Long, from: Instant, to: Instant) {
        val size = grid.size.toLong()
        val first = ceilDiv(from.toEpochMilli() - gridStart - HALF_EPOCH_MS, EPOCH_MS).coerceIn(0L, size).toInt()
        val end = ceilDiv(to.toEpochMilli() - gridStart - HALF_EPOCH_MS, EPOCH_MS).coerceIn(0L, size).toInt()
        for (i in first until end) grid[i] = true
    }

    private companion object {
        /** SRI epochs are one minute and pairs 24 elapsed hours apart: the index's definition, not tunables. */
        const val EPOCH_MS: Long = 60_000L
        const val HALF_EPOCH_MS: Long = EPOCH_MS / 2
        const val DAY_MS: Long = 86_400_000L

        /** Grid steps between the two minutes of a pair (1440). */
        const val LAG: Int = (DAY_MS / EPOCH_MS).toInt()

        fun noon(date: LocalDate, offset: ZoneOffset): Instant = LocalDateTime.of(date, LocalTime.NOON).toInstant(offset)

        /** ceil(a / b) for b > 0, with the Java 8 (long, long) floorDiv. */
        fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)
    }
}

// ---------------------------------------------------------------- Night helpers shared by the pattern engines

/** The offset a night's clock times are read in: wokeUpOffset ?: fellAsleepOffset ?: main.endOffset ?: the override's. */
internal fun Night.wakeOffset(): ZoneOffset? = wokeUpOffset ?: fellAsleepOffset ?: main?.endOffset ?: override?.offset

/** Local fell-asleep time in the offset it was recorded in (23:10), or null without one. */
internal fun Night.localFellAsleep(): LocalTime? {
    val at = fellAsleep ?: return null
    val offset = fellAsleepOffset ?: main?.startOffset ?: override?.offset ?: return null
    return at.atOffset(offset).toLocalTime()
}

/** Local woke-up time in the offset it was recorded in (06:45), or null without one. */
internal fun Night.localWokeUp(): LocalTime? {
    val at = wokeUp ?: return null
    val offset = wokeUpOffset ?: main?.endOffset ?: override?.offset ?: return null
    return at.atOffset(offset).toLocalTime()
}

/** Minutes after midnight, seconds included: 06:30:30 → 390.5. */
internal fun minuteOfDay(time: LocalTime): Double = time.toSecondOfDay() / 60.0

/** A duration in minutes, milliseconds included: 90 s → 1.5. */
internal fun Duration.minutesExact(): Double = toMillis() / 60_000.0

/** Whole minutes to the nearest minute: 89.5 → 90 min, −0.4 → 0. */
internal fun roundedMinutes(minutes: Double): Duration = Duration.ofMinutes(Math.round(minutes))

/** [block] minus its awake periods (overlapping periods merged), as asleep spans in time order. */
internal fun blockSpans(block: SleepBlock): List<SleepSpan> {
    val awake = block.awakePeriods
        .map { maxOf(it.start, block.start) to minOf(it.end, block.end) }
        .filter { (start, end) -> end > start }
        .sortedBy { it.first }
    val spans = ArrayList<SleepSpan>()
    var cursor = block.start
    for ((start, end) in awake) {
        if (start > cursor) spans += SleepSpan(cursor, start)
        if (end > cursor) cursor = end
    }
    if (block.end > cursor) spans += SleepSpan(cursor, block.end)
    return spans
}
