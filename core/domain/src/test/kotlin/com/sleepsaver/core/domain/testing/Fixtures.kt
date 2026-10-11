package com.sleepsaver.core.domain.testing

import com.sleepsaver.core.domain.alarm.AlarmEvent
import com.sleepsaver.core.domain.alarm.AlarmOccurrence
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingOutcome
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Anchor
import com.sleepsaver.core.domain.journal.AnchorSource
import com.sleepsaver.core.domain.model.AwakeKind
import com.sleepsaver.core.domain.model.AwakePeriod
import com.sleepsaver.core.domain.model.BlockKind
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SleepBlock
import com.sleepsaver.core.domain.model.SleepStage
import com.sleepsaver.core.domain.model.SleepStageType
import com.sleepsaver.core.domain.model.SleepTiming
import com.sleepsaver.core.domain.model.StageTotals
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.NightRules
import com.sleepsaver.core.domain.schedule.ScheduleTimes
import com.sleepsaver.core.domain.schedule.WakeStyle
import com.sleepsaver.core.domain.schedule.WeekPlan
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Shared builders for domain tests. Times are local wall-clock in the given offset/zone.
 * Frozen after stage 1 of v2: B1 and B2 add their own helpers in `alarm/AlarmFixtures.kt` / `patterns/PatternFixtures.kt`.
 */
object Fx {
    const val GARMIN = SleepConfig.GARMIN_CONNECT_PACKAGE
    const val OTHER = "com.example.othertracker"
    val UTC: ZoneOffset = ZoneOffset.UTC
    val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
    val LONDON: ZoneId = ZoneId.of("Europe/London")

    /** 2026-03-08 in New York: clocks jump from 02:00 to 03:00 (a 23-h day; 02:30 doesn't exist). */
    val SPRING_FORWARD: LocalDate = LocalDate.of(2026, 3, 8)

    /** 2026-11-01 in New York: clocks fall back from 02:00 to 01:00 (a 25-h day; 01:30 happens twice). */
    val FALL_BACK: LocalDate = LocalDate.of(2026, 11, 1)

    fun dt(date: LocalDate, time: String): LocalDateTime = LocalDateTime.of(date, LocalTime.parse(time))

    /** [time] on [date] in [zone] as an instant (ZonedDateTime.of: gap → forward, overlap → earlier offset). */
    fun instant(date: LocalDate, time: String, zone: ZoneId = UTC): Instant =
        ZonedDateTime.of(date, LocalTime.parse(time), zone).toInstant()

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
        return plainNight(wakeDate, start, end, offset, offset, total, napSleep)
    }

    /**
     * [night] in a real zone: each endpoint gets the offset [zone] has there (ZonedDateTime.of: gap → forward,
     * overlap → earlier offset), so a night across a DST change has two offsets and its real elapsed length.
     */
    fun nightInZone(
        wakeDate: LocalDate,
        fellAsleep: String = "23:00",
        wokeUp: String = "07:00",
        zone: ZoneId,
        total: Duration? = null,
    ): Night {
        val wake = LocalTime.parse(wokeUp)
        val asleep = LocalTime.parse(fellAsleep)
        val startDate = if (asleep.isAfter(wake)) wakeDate.minusDays(1) else wakeDate
        val start = ZonedDateTime.of(startDate, asleep, zone)
        val end = ZonedDateTime.of(wakeDate, wake, zone)
        return plainNight(wakeDate, start.toInstant(), end.toInstant(), start.offset, end.offset, total, Duration.ZERO)
    }

    /** [count] nights from [fellAsleep] to [wokeUp], the last one waking on [lastWakeDate]; oldest first. */
    fun regularNights(
        count: Int,
        lastWakeDate: LocalDate,
        fellAsleep: String = "23:00",
        wokeUp: String = "07:00",
        offset: ZoneOffset = UTC,
    ): List<Night> = (count - 1 downTo 0).map { back -> night(lastWakeDate.minusDays(back.toLong()), fellAsleep, wokeUp, offset = offset) }

    // ---------------------------------------------------------------- Staged nights (hypnograms)

    /**
     * Contiguous stages from [start]: `hypnogram(dt(d, "23:00"), LIGHT to 20, DEEP to 45)` = LIGHT 23:00–23:20,
     * DEEP 23:20–00:05. Minutes are wall-clock minutes (use them in a fixed offset, or away from DST changes).
     */
    fun hypnogram(start: LocalDateTime, vararg parts: Pair<SleepStageType, Long>): List<LocalStage> {
        var cursor = start
        return parts.map { (type, minutes) ->
            val stage = LocalStage(cursor, cursor.plusMinutes(minutes), type)
            cursor = stage.end
            stage
        }
    }

    /**
     * A Garmin-like hypnogram from [fellAsleep] (on the evening before [wakeDate]) to [wokeUp]: 90-min cycles with
     * deep sleep front-loaded (45, 35, 20, 10 min, then none) and REM growing later, then [finalAwake] minutes AWAKE
     * ending at [wokeUp] (the terminal awakening Garmin usually scores). The last cycle is cut at the end.
     */
    fun typicalStages(
        wakeDate: LocalDate,
        fellAsleep: String = "23:00",
        wokeUp: String = "07:00",
        finalAwake: Long = 0,
    ): List<LocalStage> {
        val wake = LocalTime.parse(wokeUp)
        val asleep = LocalTime.parse(fellAsleep)
        val start = LocalDateTime.of(if (asleep.isAfter(wake)) wakeDate.minusDays(1) else wakeDate, asleep)
        val end = LocalDateTime.of(wakeDate, wake)
        val sleepEnd = end.minusMinutes(finalAwake)
        val cycles = listOf(
            listOf(SleepStageType.LIGHT to 15L, SleepStageType.DEEP to 45L, SleepStageType.LIGHT to 20L, SleepStageType.REM to 10L),
            listOf(SleepStageType.LIGHT to 20L, SleepStageType.DEEP to 35L, SleepStageType.LIGHT to 20L, SleepStageType.REM to 15L),
            listOf(SleepStageType.LIGHT to 30L, SleepStageType.DEEP to 20L, SleepStageType.LIGHT to 20L, SleepStageType.REM to 20L),
            listOf(SleepStageType.LIGHT to 40L, SleepStageType.DEEP to 10L, SleepStageType.LIGHT to 15L, SleepStageType.REM to 25L),
        )
        val late = listOf(SleepStageType.LIGHT to 55L, SleepStageType.REM to 30L, SleepStageType.AWAKE to 5L)
        val stages = ArrayList<LocalStage>()
        var cursor = start
        var cycle = 0
        while (cursor.isBefore(sleepEnd)) {
            for ((type, minutes) in cycles.getOrElse(cycle) { late }) {
                if (!cursor.isBefore(sleepEnd)) break
                val stageEnd = cursor.plusMinutes(minutes).let { if (it.isAfter(sleepEnd)) sleepEnd else it }
                stages += LocalStage(cursor, stageEnd, type)
                cursor = stageEnd
            }
            cycle++
        }
        if (finalAwake > 0) stages += LocalStage(sleepEnd, end, SleepStageType.AWAKE)
        return stages
    }

    /**
     * A present night whose main block spans its [stages] (stages attached to main, awake stages as awake periods,
     * awakeInMain and stageTotals filled), with optional EXTRA segments ([extra], one stage list each) and nap time.
     * Stages are local times in [offset] and shouldn't overlap.
     */
    fun stagedNight(
        wakeDate: LocalDate,
        stages: List<LocalStage>,
        offset: ZoneOffset = UTC,
        extra: List<List<LocalStage>> = emptyList(),
        napSleep: Duration = Duration.ZERO,
    ): Night = stagedNightOf(wakeDate, stages, extra, napSleep, { it.toInstant(offset) }, { offset })

    /** [stagedNight] in a real zone: stage times resolve like ZonedDateTime.of, block endpoints get their own offsets. */
    fun stagedNightInZone(
        wakeDate: LocalDate,
        stages: List<LocalStage>,
        zone: ZoneId,
        extra: List<List<LocalStage>> = emptyList(),
    ): Night = stagedNightOf(wakeDate, stages, extra, Duration.ZERO, { ZonedDateTime.of(it, zone).toInstant() }, { zone.rules.getOffset(it) })

    /** [typicalStages] as a [stagedNight]. */
    fun typicalNight(
        wakeDate: LocalDate,
        fellAsleep: String = "23:00",
        wokeUp: String = "07:00",
        finalAwake: Long = 0,
        offset: ZoneOffset = UTC,
    ): Night = stagedNight(wakeDate, typicalStages(wakeDate, fellAsleep, wokeUp, finalAwake), offset)

    // ---------------------------------------------------------------- Plans, anchors, alarm events

    /**
     * A week plan where each listed day wakes at its time with an alarm in [mode] (OFF when the time is null);
     * unlisted days are the default day. `weekPlan(MONDAY to LocalTime.of(6, 30))`.
     */
    fun weekPlan(vararg days: Pair<DayOfWeek, LocalTime?>, mode: AlarmMode = AlarmMode.SURFACING): WeekPlan =
        days.fold(WeekPlan.DEFAULT) { plan, (day, time) ->
            val current = plan.day(day)
            plan.with(
                current.copy(
                    morning = current.morning.copy(
                        wakeTime = time,
                        alarm = current.morning.alarm.copy(mode = if (time == null) AlarmMode.OFF else mode),
                    ),
                ),
            )
        }

    /**
     * Present nights that follow a plan, one per wake date in [from]..[to] in [zone]: asleep from
     * B(D − 1) + latency to W(D), both from [ScheduleTimes] with no history (so FROM_DATA falls back to the timing's
     * own times). latency = timing.latency ?: sleepLatency. Pass `settings.copy(week = …).timing`.
     */
    fun nightsFollowing(
        timing: SleepTiming,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
        need: Duration = Duration.ofHours(8),
        config: SleepConfig = SleepConfig.DEFAULT,
    ): List<Night> {
        val times = ScheduleTimes(config)
        val latency = timing.latency ?: config.sleepLatency
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { date ->
            val wake = ZonedDateTime.of(date, times.wake(date, emptyList(), timing).first, zone).toInstant()
            val asleep = times.bedtime(date.minusDays(1), zone, emptyList(), need, timing).first + latency
            plainNight(date, asleep, wake, zone.rules.getOffset(asleep), zone.rules.getOffset(wake), null, Duration.ZERO)
        }.toList()
    }

    /** A Drop anchor at local [at] in [offset]; its wakeDate follows [NightRules.wakeDateOf]. */
    fun anchor(
        at: LocalDateTime,
        offset: ZoneOffset = UTC,
        source: AnchorSource = AnchorSource.TONIGHT,
        id: Long = 1L,
        wholeNightLog: Boolean = false,
    ): Anchor {
        val instant = at.toInstant(offset)
        return Anchor(id, instant, offset, NightRules.wakeDateOf(instant, offset), source, wholeNightLog)
    }

    /**
     * An alarm event with local times ("06:30") on [wakeDate]. windowStart = target − [window] for SURFACING, else the
     * target; sensing (SURFACING only) started sensingLeadIn before the window; a PLANNED event gets its occurrence id;
     * [stoppedAt] makes the outcome STOPPED unless [outcome] says otherwise.
     */
    fun alarmEvent(
        wakeDate: LocalDate,
        target: String,
        firstRingAt: String,
        reason: RingReason,
        mode: AlarmMode = AlarmMode.SURFACING,
        window: Duration = Duration.ofMinutes(30),
        kind: RingKind = RingKind.PLANNED,
        snoozes: List<String> = emptyList(),
        stoppedAt: String? = null,
        offset: ZoneOffset = UTC,
        maxZ: Double? = null,
        style: WakeStyle = WakeStyle.STANDARD,
        outcome: RingOutcome? = if (stoppedAt != null) RingOutcome.STOPPED else null,
        id: Long = 0,
    ): AlarmEvent {
        fun local(time: String): Instant = dt(wakeDate, time).toInstant(offset)
        val targetAt = local(target)
        val windowStart = if (mode == AlarmMode.SURFACING) targetAt - window else targetAt
        return AlarmEvent(
            id = id,
            kind = kind,
            occurrenceId = if (kind == RingKind.PLANNED) AlarmOccurrence.occurrenceId(wakeDate, LocalTime.parse(target)) else null,
            wakeDate = wakeDate,
            target = targetAt,
            windowStart = windowStart,
            mode = mode,
            style = style,
            firstRingAt = local(firstRingAt),
            reason = reason,
            maxZ = maxZ,
            snoozeTimes = snoozes.map(::local),
            stoppedAt = stoppedAt?.let(::local),
            outcome = outcome,
            sensingStart = if (mode == AlarmMode.SURFACING) windowStart - SleepConfig.DEFAULT.surfacing.sensingLeadIn else null,
            offset = offset,
        )
    }

    /** nights keyed by wake date, for the debt/streak APIs. */
    fun byDate(vararg nights: Night): Map<LocalDate, Night> = nights.associateBy { it.wakeDate }

    fun hours(h: Double): Duration = Duration.ofSeconds((h * 3600).toLong())
    fun minutes(m: Long): Duration = Duration.ofMinutes(m)

    // ---------------------------------------------------------------- internals

    private fun plainNight(
        wakeDate: LocalDate,
        start: Instant,
        end: Instant,
        startOffset: ZoneOffset,
        endOffset: ZoneOffset,
        total: Duration?,
        napSleep: Duration,
    ): Night {
        val inBed = Duration.between(start, end)
        val main = total ?: inBed
        val block = SleepBlock(
            kind = BlockKind.MAIN,
            start = start,
            end = end,
            startOffset = startOffset,
            endOffset = endOffset,
            asleep = main,
            sessionIds = listOf("fx:$wakeDate"),
            origin = GARMIN,
        )
        return Night(
            wakeDate = wakeDate,
            main = block,
            fellAsleep = start,
            wokeUp = end,
            fellAsleepOffset = startOffset,
            wokeUpOffset = endOffset,
            mainSleep = main,
            awakeInMain = inBed.minus(main),
            extraSleep = Duration.ZERO,
            napSleep = napSleep,
            totalSleep = main.plus(napSleep),
            stageTotals = null,
            origin = GARMIN,
        )
    }

    private fun stagedNightOf(
        wakeDate: LocalDate,
        stages: List<LocalStage>,
        extra: List<List<LocalStage>>,
        napSleep: Duration,
        toInstant: (LocalDateTime) -> Instant,
        offsetOf: (Instant) -> ZoneOffset,
    ): Night {
        require(stages.isNotEmpty()) { "a staged night needs stages" }
        val main = stagedBlock(BlockKind.MAIN, stages, "fx:$wakeDate", toInstant, offsetOf)
        val extras = extra.mapIndexed { i, segment -> stagedBlock(BlockKind.EXTRA, segment, "fx:$wakeDate:extra$i", toInstant, offsetOf) }
        val extraSleep = extras.fold(Duration.ZERO) { acc, block -> acc + block.asleep }
        fun total(type: SleepStageType) = main.stages.filter { it.type == type }.fold(Duration.ZERO) { acc, s -> acc + s.duration }
        val deep = total(SleepStageType.DEEP)
        val light = total(SleepStageType.LIGHT)
        val rem = total(SleepStageType.REM)
        val awake = main.awakePeriods.fold(Duration.ZERO) { acc, p -> acc + p.duration }
        return Night(
            wakeDate = wakeDate,
            main = main,
            extraSegments = extras,
            fellAsleep = main.start,
            wokeUp = main.end,
            fellAsleepOffset = main.startOffset,
            wokeUpOffset = main.endOffset,
            mainSleep = main.asleep,
            awakeInMain = awake,
            extraSleep = extraSleep,
            napSleep = napSleep,
            totalSleep = main.asleep + extraSleep + napSleep,
            stageTotals = StageTotals(
                deep = deep,
                light = light,
                rem = rem,
                awake = awake,
                unclassified = maxOf(Duration.ZERO, main.asleep - deep - light - rem),
            ),
            interruptions = main.awakePeriods.filter { it.duration >= SleepConfig.DEFAULT.interruptionMinDuration },
            origin = GARMIN,
        )
    }

    private fun stagedBlock(
        kind: BlockKind,
        local: List<LocalStage>,
        id: String,
        toInstant: (LocalDateTime) -> Instant,
        offsetOf: (Instant) -> ZoneOffset,
    ): SleepBlock {
        val stages = local.map { SleepStage(toInstant(it.start), toInstant(it.end), it.type) }.sortedBy { it.start }
        val start = stages.minOf { it.start }
        val end = stages.maxOf { it.end }
        val awake = stages.filter { it.type.isAwake }.map { AwakePeriod(it.start, it.end, AwakeKind.STAGE) }
        val awakeTotal = awake.fold(Duration.ZERO) { acc, p -> acc + p.duration }
        return SleepBlock(
            kind = kind,
            start = start,
            end = end,
            startOffset = offsetOf(start),
            endOffset = offsetOf(end),
            asleep = maxOf(Duration.ZERO, Duration.between(start, end) - awakeTotal),
            awakePeriods = awake,
            stages = stages,
            sessionIds = listOf(id),
            origin = GARMIN,
        )
    }
}
