package com.sleepsaver.core.data.demo

import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.source.ChangeBatch
import com.sleepsaver.core.data.source.SleepDataSource
import com.sleepsaver.core.data.source.VitalIds
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SleepStage
import com.sleepsaver.core.domain.model.SleepStageType
import com.sleepsaver.core.domain.model.VitalSample
import com.sleepsaver.core.domain.model.VitalType
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class DemoDataset(val sessions: List<RawSleepSession>, val vitals: List<VitalSample>)

/**
 * Synthetic, deterministic sleep for demo mode: [NIGHTS] nights in the device zone, ending with the latest
 * night that has already ended (normally the one that ended this morning).
 *
 * Every day is generated from its own seeded [Random], and the recurring patterns (short nights every 8–10 days,
 * a fragmented night about weekly, a long nap every few weeks) come from fixed walks over the calendar, so a
 * given date always produces the same sleep, whatever day the dataset is generated on.
 *
 *  - Mon–Fri wakes: bedtime ~23:00 ± 40 m, alarm ~06:45 ± 15 m. Sat/Sun wakes: ~00:15 ± 45 m, ~08:45 ± 50 m.
 *  - Short nights are 5–6 h in bed. Fragmented nights are two sessions 20–50 min apart.
 *  - 0–3 awake stages (5–25 min) per night over ~90 min cycles: deep early, REM growing later, light in between.
 *  - Naps on ~15 % of days, starting 13:00–15:30, 20–45 min (60–80 min on long-nap days).
 *  - Vitals: resting HR 52–60, HRV 35–70 ms, steps 4k–14k for each finished day, sleeping HR per session.
 */
@Singleton
class FakeSleepDataSource internal constructor(
    private val timeProvider: TimeProvider,
    private val seed: Long,
) : SleepDataSource {

    @Inject constructor(timeProvider: TimeProvider) : this(timeProvider, DEFAULT_SEED)

    override val id: String = ID

    override suspend fun isReadable(): Boolean = true

    override suspend fun readSleepSessions(from: Instant, to: Instant): List<RawSleepSession> =
        generate().sessions.filter { it.end.isAfter(from) && it.start.isBefore(to) }

    override suspend fun readVitals(from: Instant, to: Instant, sessions: List<RawSleepSession>): List<VitalSample> {
        val sleepHrIds = sessions.mapTo(HashSet()) { VitalIds.sleepingHeartRate(it.id) }
        return generate().vitals.filter { vital ->
            if (vital.type == VitalType.SLEEP_HR_AVG) {
                vital.id in sleepHrIds
            } else {
                !vital.time.isBefore(from) && vital.time.isBefore(to)
            }
        }
    }

    override suspend fun newChangesToken(): String = TOKEN

    override suspend fun changesSince(token: String): ChangeBatch =
        ChangeBatch.Changes(emptyList(), emptyList(), emptyList(), emptyList(), TOKEN)

    /** The whole dataset as of [now]: nothing in it ends after [now]. */
    fun generate(now: Instant = timeProvider.now(), zone: ZoneId = timeProvider.zone()): DemoDataset {
        val today = now.atZone(zone).toLocalDate()
        val lastWakeDate = if (day(today, zone).nightSessions.last().end.isAfter(now)) today.minusDays(1) else today
        val firstWakeDate = lastWakeDate.minusDays(NIGHTS - 1L)
        val patterns = Patterns(seed, lastWakeDate)

        val sessions = mutableListOf<RawSleepSession>()
        val vitals = mutableListOf<VitalSample>()
        var date = firstWakeDate
        while (!date.isAfter(lastWakeDate)) {
            val generated = day(date, zone, patterns)
            val ended = (generated.nightSessions + listOfNotNull(generated.nap)).filter { !it.end.isAfter(now) }
            sessions += ended
            vitals += generated.dayVitals.filter { !it.time.isAfter(now) }
            if (date.isBefore(today)) vitals += generated.steps
            ended.forEach { vitals += generated.sleepingHeartRate(it) }
            date = date.plusDays(1)
        }
        return DemoDataset(sessions.sortedBy { it.start }, vitals)
    }

    // ------------------------------------------------------------ one day

    private class GeneratedDay(
        val nightSessions: List<RawSleepSession>,
        val nap: RawSleepSession?,
        val dayVitals: List<VitalSample>,
        val steps: VitalSample,
        private val restingHr: Int,
        private val seed: Long,
    ) {
        fun sleepingHeartRate(session: RawSleepSession): VitalSample {
            val rng = Random(session.id.hashCode().toLong() * 31 + seed)
            val bump = if (session == nap) rng.nextInt(6, 11) else rng.nextInt(2, 7)
            return VitalSample(VitalIds.sleepingHeartRate(session.id), VitalType.SLEEP_HR_AVG, session.end, (restingHr + bump).toDouble(), ORIGIN)
        }
    }

    private fun day(date: LocalDate, zone: ZoneId, patterns: Patterns = Patterns(seed, date)): GeneratedDay {
        val rng = Random(mix(seed, date.toEpochDay(), SALT_NIGHT))
        val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
        val short = date in patterns.shortNights
        val fragmented = !short && date in patterns.fragmentedNights

        // Minutes relative to midnight at the start of the wake date (negative = the evening before).
        val wakeMinute = if (weekend) 8 * 60 + 45 + rng.jitter(50) else 6 * 60 + 45 + rng.jitter(15)
        val bedMinute = when {
            short -> wakeMinute - rng.nextInt(5 * 60, 6 * 60 + 1)
            weekend -> 15 + rng.jitter(45)
            else -> -60 + rng.jitter(40)
        }
        val start = date.atStartOfDay().plusMinutes(bedMinute.toLong()).atZone(zone).toInstant()
        val end = date.atStartOfDay().plusMinutes(wakeMinute.toLong()).atZone(zone).toInstant()
        val stages = withAwakeStages(sleepCycles(start, end, rng), start, end, rng)

        val nightSessions = if (fragmented) {
            val length = Duration.between(start, end)
            val gapStart = start.plus(length.multipliedBy(rng.nextLong(40, 61)).dividedBy(100))
            val gapEnd = gapStart.plus(Duration.ofMinutes(rng.nextLong(20, 51)))
            listOf(
                session("demo:$date:night-1", start, gapStart, zone, stages),
                session("demo:$date:night-2", gapEnd, end, zone, stages),
            )
        } else {
            listOf(session("demo:$date:night", start, end, zone, stages))
        }

        val vitalRng = Random(mix(seed, date.toEpochDay(), SALT_VITALS))
        val restingHr = (55 + vitalRng.jitter(3) + if (short) 2 else 0).coerceIn(52, 60)
        val hrv = (52.0 + vitalRng.jitter(15) - if (short) 8 else 0).coerceIn(35.0, 70.0)
        val steps = if (weekend) vitalRng.nextInt(4_000, 12_001) else vitalRng.nextInt(6_000, 14_001)
        val wake = nightSessions.last().end

        return GeneratedDay(
            nightSessions = nightSessions,
            nap = nap(date, zone, patterns),
            dayVitals = listOf(
                VitalSample("demo:rhr:$date", VitalType.RESTING_HR, wake, restingHr.toDouble(), ORIGIN),
                VitalSample("demo:hrv:$date", VitalType.HRV_RMSSD, wake, hrv, ORIGIN),
            ),
            // Own id: VitalIds.steps(date) is the real Health Connect row for that day, which demo rows must not replace.
            steps = VitalSample("demo:steps:$date", VitalType.STEPS_DAY, date.atStartOfDay(zone).toInstant(), steps.toDouble(), ORIGIN),
            restingHr = restingHr,
            seed = seed,
        )
    }

    private fun nap(date: LocalDate, zone: ZoneId, patterns: Patterns): RawSleepSession? {
        val rng = Random(mix(seed, date.toEpochDay(), SALT_NAP))
        val long = date in patterns.longNaps
        if (!long && rng.nextDouble() >= NAP_PROBABILITY) return null
        val start = date.atTime(NAP_EARLIEST).plusMinutes(rng.nextLong(0, 151)).atZone(zone).toInstant()
        val minutes = if (long) rng.nextLong(60, 81) else rng.nextLong(20, 46)
        val end = start.plus(Duration.ofMinutes(minutes))
        val stages = if (long) {
            val deepStart = start.plus(Duration.ofMinutes(rng.nextLong(15, 26)))
            val deepEnd = deepStart.plus(Duration.ofMinutes(rng.nextLong(20, 31)))
            listOf(
                SleepStage(start, deepStart, SleepStageType.LIGHT),
                SleepStage(deepStart, deepEnd, SleepStageType.DEEP),
                SleepStage(deepEnd, end, SleepStageType.LIGHT),
            )
        } else {
            listOf(SleepStage(start, end, SleepStageType.LIGHT))
        }
        return session("demo:$date:nap", start, end, zone, stages)
    }

    /** A session over [start, end) carrying the parts of [stages] that fall inside it. */
    private fun session(id: String, start: Instant, end: Instant, zone: ZoneId, stages: List<SleepStage>) =
        RawSleepSession(
            id = id,
            origin = ORIGIN,
            start = start,
            end = end,
            startOffset = zone.rules.getOffset(start),
            endOffset = zone.rules.getOffset(end),
            stages = stages.mapNotNull { it.clipTo(start, end) },
            lastModified = end.plus(SYNC_DELAY),
        )

    /** ~90 min cycles: light, deep (long early, gone by the 4th cycle), light, REM (longer each cycle). */
    private fun sleepCycles(start: Instant, end: Instant, rng: Random): List<SleepStage> {
        val stages = mutableListOf<SleepStage>()
        var t = start
        var cycle = 0
        while (t.isBefore(end)) {
            val length = rng.nextInt(85, 101)
            var rem = min(45, 8 + 9 * cycle + rng.jitter(4))
            val deep = max(0, 40 - 12 * cycle + rng.jitter(6))
            rem = min(rem, length - deep - MIN_LIGHT_PER_CYCLE)
            val light = length - deep - rem
            val lightBefore = light * 3 / 5
            for ((minutes, type) in listOf(
                lightBefore to SleepStageType.LIGHT,
                deep to SleepStageType.DEEP,
                light - lightBefore to SleepStageType.LIGHT,
                rem to SleepStageType.REM,
            )) {
                if (minutes <= 0 || !t.isBefore(end)) continue
                val stageEnd = minOf(t.plus(Duration.ofMinutes(minutes.toLong())), end)
                stages += SleepStage(t, stageEnd, type)
                t = stageEnd
            }
            cycle++
        }
        return stages
    }

    /** Overlays 0–3 non-overlapping awake stretches of 5–25 min, away from the first 30 and last 15 minutes. */
    private fun withAwakeStages(stages: List<SleepStage>, start: Instant, end: Instant, rng: Random): List<SleepStage> {
        val earliest = start.plus(Duration.ofMinutes(30))
        val latest = end.minus(Duration.ofMinutes(15))
        val windowMinutes = Duration.between(earliest, latest).toMinutes()
        val awake = mutableListOf<SleepStage>()
        repeat(rng.nextInt(0, 4)) {
            val minutes = rng.nextLong(5, 26)
            if (windowMinutes <= minutes) return@repeat
            val awakeStart = earliest.plus(Duration.ofMinutes(rng.nextLong(0, windowMinutes - minutes)))
            val candidate = SleepStage(awakeStart, awakeStart.plus(Duration.ofMinutes(minutes)), SleepStageType.AWAKE)
            val clear = awake.none { it.start.isBefore(candidate.end.plusMinutes(10)) && candidate.start.isBefore(it.end.plusMinutes(10)) }
            if (clear) awake += candidate
        }
        val asleep = stages.flatMap { stage ->
            awake.fold(listOf(stage)) { pieces, gap -> pieces.flatMap { it.minus(gap) } }
        }
        return (asleep + awake).sortedBy { it.start }
    }

    /** Recurring patterns as fixed walks over the calendar, so they don't depend on the generation date. */
    private class Patterns(seed: Long, until: LocalDate) {
        val shortNights = walk(seed, SALT_SHORT, 8, 10, until)
        val fragmentedNights = walk(seed, SALT_FRAGMENTED, 6, 8, until)
        val longNaps = walk(seed, SALT_LONG_NAP, 18, 26, until)

        private fun walk(seed: Long, salt: Long, minGap: Long, maxGap: Long, until: LocalDate): Set<LocalDate> {
            val rng = Random(mix(seed, 0, salt))
            val dates = HashSet<LocalDate>()
            var date = PATTERN_START.plusDays(rng.nextLong(0, maxGap))
            while (!date.isAfter(until)) {
                dates += date
                date = date.plusDays(rng.nextLong(minGap, maxGap + 1))
            }
            return dates
        }
    }

    companion object {
        const val ID = "demo"
        const val ORIGIN = SleepConfig.DEMO_ORIGIN
        const val NIGHTS = 60
        private const val TOKEN = "demo"
        private const val DEFAULT_SEED = 20_261_006L
        private const val NAP_PROBABILITY = 0.12
        private const val MIN_LIGHT_PER_CYCLE = 20
        private val NAP_EARLIEST: LocalTime = LocalTime.of(13, 0)
        private val SYNC_DELAY: Duration = Duration.ofMinutes(7)
        private val PATTERN_START: LocalDate = LocalDate.of(2000, 1, 3)
        private const val SALT_NIGHT = 1L
        private const val SALT_NAP = 2L
        private const val SALT_VITALS = 3L
        private const val SALT_SHORT = 4L
        private const val SALT_FRAGMENTED = 5L
        private const val SALT_LONG_NAP = 6L

        private fun mix(seed: Long, day: Long, salt: Long): Long =
            seed * -0x61c8864680b583ebL + day * 0x2545F4914F6CDD1DL + salt * 0x9E3779B97F4A7C1L

        /** Roughly bell-shaped integer in [-spread, spread]. */
        private fun Random.jitter(spread: Int): Int = (nextInt(-spread, spread + 1) + nextInt(-spread, spread + 1)) / 2

        private fun Instant.plusMinutes(minutes: Long): Instant = plus(Duration.ofMinutes(minutes))

        private fun SleepStage.clipTo(start: Instant, end: Instant): SleepStage? {
            val s = maxOf(this.start, start)
            val e = minOf(this.end, end)
            return if (s.isBefore(e)) copy(start = s, end = e) else null
        }

        /** This stage with [gap] cut out: zero, one or two pieces. */
        private fun SleepStage.minus(gap: SleepStage): List<SleepStage> {
            if (!gap.start.isBefore(end) || !start.isBefore(gap.end)) return listOf(this)
            return listOfNotNull(
                copy(end = gap.start).takeIf { it.start.isBefore(it.end) },
                copy(start = gap.end).takeIf { it.start.isBefore(it.end) },
            )
        }
    }
}
