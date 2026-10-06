package com.sleepsaver.core.testing

import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.debt.SleepDebtCalculator
import com.sleepsaver.core.domain.energy.EnergyScheduleCalculator
import com.sleepsaver.core.domain.habits.HabitCatalog
import com.sleepsaver.core.domain.insights.InsightsEngine
import com.sleepsaver.core.domain.model.DailyInsights
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SleepDebt
import com.sleepsaver.core.domain.model.SleepStage
import com.sleepsaver.core.domain.model.SleepStageType
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.model.VitalSample
import com.sleepsaver.core.domain.model.VitalType
import com.sleepsaver.core.domain.night.NightAssembler
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * Deterministic, realistic sample data run through the real domain engine. For ViewModel tests,
 * previews and screenshot tests. Default "now" is Tue 2026-10-06 10:20 in New York.
 */
object SampleData {
    val zone: ZoneId = ZoneId.of("America/New_York")
    val now: Instant = ZonedDateTime.of(2026, 10, 6, 10, 20, 0, 0, zone).toInstant()
    val today: LocalDate get() = now.atZone(zone).toLocalDate()
    val settings = UserSleepSettings()
    val habits: List<HabitSettings> get() = HabitCatalog().defaultSettings()

    /**
     * [count] nights ending on [today] (or yesterday when [includeLastNight] is false): weekday alarms ~06:50,
     * weekend lie-ins ~08:40, a short night every 9th night, a split night every 7th, a nap every 6th day,
     * light/deep/REM cycles and a couple of brief awakenings per night.
     */
    fun sessions(
        today: LocalDate = this.today,
        zone: ZoneId = this.zone,
        count: Int = 30,
        includeLastNight: Boolean = true,
        seed: Int = 7,
    ): List<RawSleepSession> {
        val random = Random(seed)
        val first = if (includeLastNight) 0 else 1
        val out = mutableListOf<RawSleepSession>()
        for (k in first until first + count) {
            val wakeDate = today.minusDays(k.toLong())
            val weekend = wakeDate.dayOfWeek == DayOfWeek.SATURDAY || wakeDate.dayOfWeek == DayOfWeek.SUNDAY
            val short = k % 9 == 4
            val bedMinutes = when {
                short -> 25 * 60 + 30 + random.nextInt(-20, 20)
                weekend -> 24 * 60 + 10 + random.nextInt(-40, 40)
                else -> 23 * 60 + random.nextInt(-30, 30)
            }
            val wakeMinutes = when {
                weekend -> 8 * 60 + 40 + random.nextInt(-40, 40)
                else -> 6 * 60 + 50 + random.nextInt(-15, 15)
            }
            val bed = LocalDateTime.of(wakeDate.minusDays(1), LocalTime.MIDNIGHT).plusMinutes(bedMinutes.toLong())
            val wake = LocalDateTime.of(wakeDate, LocalTime.MIDNIGHT).plusMinutes(wakeMinutes.toLong())
            if (k % 7 == 3) {
                val split = bed.plusMinutes(150)
                out += session("sample:$k:a", bed, split, zone, random)
                out += session("sample:$k:b", split.plusMinutes(30), wake, zone, random)
            } else {
                out += session("sample:$k", bed, wake, zone, random)
            }
            if (k % 6 == 2) {
                val napStart = LocalDateTime.of(wakeDate, LocalTime.of(14, 0)).plusMinutes(random.nextLong(0, 60))
                out += session("sample:$k:nap", napStart, napStart.plusMinutes(25L + random.nextInt(0, 20)), zone, random, stages = false)
            }
        }
        return out.sortedBy { it.start }
    }

    fun nights(
        today: LocalDate = this.today,
        zone: ZoneId = this.zone,
        count: Int = 30,
        includeLastNight: Boolean = true,
    ): List<Night> = NightAssembler().assemble(
        sessions(today, zone, count, includeLastNight),
        SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE),
    )

    fun insights(
        now: Instant = this.now,
        zone: ZoneId = this.zone,
        settings: UserSleepSettings = this.settings,
        habits: List<HabitSettings> = this.habits,
        nights: List<Night> = nights(now.atZone(zone).toLocalDate(), zone),
    ): DailyInsights = InsightsEngine().compute(nights, settings, habits, now, zone)

    fun readyState(now: Instant = this.now, nights: List<Night> = nights()): InsightsState =
        InsightsState.Ready(insights(now = now, nights = nights))

    fun debtSeries(
        from: LocalDate,
        to: LocalDate,
        nights: List<Night> = nights(),
        settings: UserSleepSettings = this.settings,
    ): List<SleepDebt> = SleepDebtCalculator().series(from, to, nights.associateBy { it.wakeDate }, settings.sleepNeed)

    fun schedule(
        date: LocalDate = today,
        zone: ZoneId = this.zone,
        nights: List<Night> = nights(),
        settings: UserSleepSettings = this.settings,
    ): EnergySchedule {
        val debt = SleepDebtCalculator().debtAsOf(date, nights.associateBy { it.wakeDate }, settings.sleepNeed).debt
        return EnergyScheduleCalculator().schedule(date, zone, nights.filter { !it.wakeDate.isAfter(date) }, settings.sleepNeed, debt, settings.timing)
    }

    /** Resting HR, HRV and daily steps for each night in [nights]. */
    fun vitals(nights: List<Night> = nights(), seed: Int = 11): List<VitalSample> {
        val random = Random(seed)
        return nights.filter { it.isPresent }.flatMap { night ->
            val t = night.wokeUp ?: return@flatMap emptyList()
            listOf(
                VitalSample("rhr:${night.wakeDate}", VitalType.RESTING_HR, t, 52.0 + random.nextInt(0, 9), SleepConfig.GARMIN_CONNECT_PACKAGE),
                VitalSample("hrv:${night.wakeDate}", VitalType.HRV_RMSSD, t, 38.0 + random.nextInt(0, 30), SleepConfig.GARMIN_CONNECT_PACKAGE),
                VitalSample("steps:${night.wakeDate}", VitalType.STEPS_DAY, t, 4000.0 + random.nextInt(0, 9000), SleepConfig.GARMIN_CONNECT_PACKAGE),
            )
        }
    }

    private fun session(
        id: String,
        start: LocalDateTime,
        end: LocalDateTime,
        zone: ZoneId,
        random: Random,
        stages: Boolean = true,
    ): RawSleepSession {
        val startInstant = start.atZone(zone).toInstant()
        val endInstant = end.atZone(zone).toInstant()
        return RawSleepSession(
            id = id,
            origin = SleepConfig.GARMIN_CONNECT_PACKAGE,
            start = startInstant,
            end = endInstant,
            startOffset = zone.rules.getOffset(startInstant),
            endOffset = zone.rules.getOffset(endInstant),
            stages = if (stages) stagesFor(startInstant, endInstant, random) else emptyList(),
            lastModified = endInstant,
        )
    }

    /** ~90-minute cycles: deep-heavy early, REM-heavy late, with one or two short awakenings. */
    private fun stagesFor(start: Instant, end: Instant, random: Random): List<SleepStage> {
        val out = mutableListOf<SleepStage>()
        var t = start
        var cycle = 0
        val awakeAt = setOf(random.nextInt(1, 4), random.nextInt(2, 5))
        while (t.isBefore(end)) {
            val deep = (30 - cycle * 7).coerceAtLeast(5).toLong()
            val rem = (12 + cycle * 6).coerceAtMost(35).toLong()
            val plan = buildList {
                add(SleepStageType.LIGHT to 25L)
                add(SleepStageType.DEEP to deep)
                add(SleepStageType.LIGHT to 20L)
                add(SleepStageType.REM to rem)
                if (cycle in awakeAt) add(SleepStageType.AWAKE to (4L + random.nextInt(0, 12)))
            }
            for ((type, minutes) in plan) {
                if (!t.isBefore(end)) break
                val next = minOf(t.plusSeconds(minutes * 60), end)
                out += SleepStage(t, next, type)
                t = next
            }
            cycle++
        }
        return out
    }
}
