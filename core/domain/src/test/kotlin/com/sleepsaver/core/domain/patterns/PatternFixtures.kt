package com.sleepsaver.core.domain.patterns

import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.model.AwakeKind
import com.sleepsaver.core.domain.model.AwakePeriod
import com.sleepsaver.core.domain.model.BlockKind
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.NightOverride
import com.sleepsaver.core.domain.model.SleepBlock
import com.sleepsaver.core.domain.testing.Fx
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/** B2's own builders for the pattern-engine tests (Fx is frozen). Times are local wall-clock in the given offset. */
internal object PatternFx {

    /**
     * A night asleep [asleep] minutes and awake [awake] minutes in between, from [fellAsleep] on the evening before
     * [wakeDate] (or on [wakeDate] itself when it is before noon), plus [extra] minutes in an extra segment.
     * `night(d, asleep = 420, awake = 30)` = 23:00 → 07:30 with 7 h asleep.
     */
    fun night(
        wakeDate: LocalDate,
        asleep: Long,
        awake: Long = 0,
        fellAsleep: String = "23:00",
        extra: Long = 0,
        offset: ZoneOffset = Fx.UTC,
    ): Night {
        val onsetTime = LocalTime.parse(fellAsleep)
        val start = LocalDateTime.of(if (onsetTime < LocalTime.NOON) wakeDate else wakeDate.minusDays(1), onsetTime)
        val end = start.plusMinutes(asleep + awake)
        val base = Fx.night(wakeDate, fellAsleep, end.toLocalTime().toString(), total = Duration.ofMinutes(asleep), offset = offset)
        val main = checkNotNull(base.main)
        check(main.start == start.toInstant(offset) && main.end == end.toInstant(offset)) { "night must end on its wake date" }
        if (extra == 0L) return base
        val extraStart = end.plusHours(1)
        val segment = SleepBlock(
            kind = BlockKind.EXTRA,
            start = extraStart.toInstant(offset),
            end = extraStart.plusMinutes(extra).toInstant(offset),
            startOffset = offset,
            endOffset = offset,
            asleep = Duration.ofMinutes(extra),
        )
        return base.copy(
            extraSegments = listOf(segment),
            extraSleep = Duration.ofMinutes(extra),
            totalSleep = base.totalSleep + Duration.ofMinutes(extra),
        )
    }

    /** [night] with a nap block from [start] to [end] (local times in [offset]). */
    fun withNap(night: Night, start: LocalDateTime, end: LocalDateTime, offset: ZoneOffset = Fx.UTC): Night {
        val nap = SleepBlock(
            kind = BlockKind.NAP,
            start = start.toInstant(offset),
            end = end.toInstant(offset),
            startOffset = offset,
            endOffset = offset,
            asleep = Duration.between(start, end),
        )
        return night.copy(naps = night.naps + nap, napSleep = night.napSleep + nap.asleep, totalSleep = night.totalSleep + nap.asleep)
    }

    /**
     * A night the user added by hand from [fellAsleep] to [wokeUp], with an ADDED awake period [awakeFrom, awakeTo)
     * inside it (a manual night still counts as one span for Keel).
     */
    fun manualNight(wakeDate: LocalDate, fellAsleep: String, wokeUp: String, awakeFrom: LocalDateTime, awakeTo: LocalDateTime): Night {
        val base = Fx.night(wakeDate, fellAsleep, wokeUp)
        val main = checkNotNull(base.main)
        val awake = AwakePeriod(awakeFrom.toInstant(Fx.UTC), awakeTo.toInstant(Fx.UTC), AwakeKind.ADDED)
        return base.copy(
            main = main.copy(awakePeriods = listOf(awake), isManual = true, sessionIds = listOf("manual-night:$wakeDate")),
            mainSleep = base.mainSleep - awake.duration,
            awakeInMain = awake.duration,
            totalSleep = base.totalSleep - awake.duration,
            override = NightOverride(
                wakeDate = wakeDate,
                fellAsleep = main.start,
                wokeUp = main.end,
                isManualNight = true,
                offset = Fx.UTC,
                updatedAt = main.end,
            ),
            origin = null,
        )
    }

    /** [mark] on each of [dates]. */
    fun marked(mark: Mark, dates: Collection<LocalDate>): Map<LocalDate, Set<Mark>> = dates.associateWith { setOf(mark) }

    /** Merges mark maps date by date. */
    fun merge(vararg maps: Map<LocalDate, Set<Mark>>): Map<LocalDate, Set<Mark>> {
        val result = LinkedHashMap<LocalDate, Set<Mark>>()
        for (map in maps) for ((date, marks) in map) result[date] = result[date].orEmpty() + marks
        return result
    }

    /** The [count] dates ending on [last], oldest first. */
    fun dates(count: Int, last: LocalDate): List<LocalDate> = (count - 1 downTo 0).map { last.minusDays(it.toLong()) }

    /** SRI score for [pairs] valid minute pairs of which [mismatches] disagree (the hand computations in KeelTest). */
    fun sri(pairs: Int, mismatches: Int): Int = Math.round(200.0 * (pairs - mismatches) / pairs - 100).toInt()
}
