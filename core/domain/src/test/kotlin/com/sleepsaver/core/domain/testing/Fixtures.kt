package com.sleepsaver.core.domain.testing

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.BlockKind
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SleepBlock
import com.sleepsaver.core.domain.model.SleepStage
import com.sleepsaver.core.domain.model.SleepStageType
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Shared builders for domain tests. Times are local wall-clock in the given offset/zone. */
object Fx {
    const val GARMIN = SleepConfig.GARMIN_CONNECT_PACKAGE
    const val OTHER = "com.example.othertracker"
    val UTC: ZoneOffset = ZoneOffset.UTC
    val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
    val LONDON: ZoneId = ZoneId.of("Europe/London")

    fun dt(date: LocalDate, time: String): LocalDateTime = LocalDateTime.of(date, LocalTime.parse(time))

    /** Offset that [zone] had at local [ldt] (DST-aware). */
    fun offsetAt(zone: ZoneId, ldt: LocalDateTime): ZoneOffset = zone.rules.getOffset(ldt)

    /** A stage given as local times in [offset]. */
    data class LocalStage(val start: LocalDateTime, val end: LocalDateTime, val type: SleepStageType)

    fun session(
        id: String,
        start: LocalDateTime,
        end: LocalDateTime,
        startOffset: ZoneOffset = UTC,
        endOffset: ZoneOffset = startOffset,
        origin: String = GARMIN,
        stages: List<LocalStage> = emptyList(),
    ): RawSleepSession = RawSleepSession(
        id = id,
        origin = origin,
        start = start.toInstant(startOffset),
        end = end.toInstant(endOffset),
        startOffset = startOffset,
        endOffset = endOffset,
        stages = stages.map { SleepStage(it.start.toInstant(startOffset), it.end.toInstant(startOffset), it.type) },
        lastModified = end.toInstant(endOffset),
    )

    /** Session in a real zone; offsets are resolved per endpoint, so DST nights get two different offsets. */
    fun sessionInZone(
        id: String,
        start: LocalDateTime,
        end: LocalDateTime,
        zone: ZoneId,
        origin: String = GARMIN,
    ): RawSleepSession = session(id, start, end, offsetAt(zone, start), offsetAt(zone, end), origin)

    /**
     * A ready-made present [Night] (no naps, no stages) for tests that don't exercise the assembler.
     * fellAsleep is on wakeDate − 1 when it's later than [wokeUp] (the usual case).
     * [total] overrides the computed main-sleep duration (e.g. to simulate awake time).
     */
    fun night(
        wakeDate: LocalDate,
        fellAsleep: String = "23:00",
        wokeUp: String = "07:00",
        total: Duration? = null,
        offset: ZoneOffset = UTC,
        napSleep: Duration = Duration.ZERO,
    ): Night {
        val wake = LocalTime.parse(wokeUp)
        val asleep = LocalTime.parse(fellAsleep)
        val startDate = if (asleep.isAfter(wake)) wakeDate.minusDays(1) else wakeDate
        val start = LocalDateTime.of(startDate, asleep).toInstant(offset)
        val end = LocalDateTime.of(wakeDate, wake).toInstant(offset)
        val inBed = Duration.between(start, end)
        val main = total ?: inBed
        val block = SleepBlock(
            kind = BlockKind.MAIN,
            start = start,
            end = end,
            startOffset = offset,
            endOffset = offset,
            asleep = main,
            sessionIds = listOf("fx:$wakeDate"),
            origin = GARMIN,
        )
        return Night(
            wakeDate = wakeDate,
            main = block,
            fellAsleep = start,
            wokeUp = end,
            fellAsleepOffset = offset,
            wokeUpOffset = offset,
            mainSleep = main,
            awakeInMain = inBed.minus(main),
            extraSleep = Duration.ZERO,
            napSleep = napSleep,
            totalSleep = main.plus(napSleep),
            stageTotals = null,
            origin = GARMIN,
        )
    }

    /** nights keyed by wake date, for the debt/streak APIs. */
    fun byDate(vararg nights: Night): Map<LocalDate, Night> = nights.associateBy { it.wakeDate }

    fun hours(h: Double): Duration = Duration.ofSeconds((h * 3600).toLong())
    fun minutes(m: Long): Duration = Duration.ofMinutes(m)
}
