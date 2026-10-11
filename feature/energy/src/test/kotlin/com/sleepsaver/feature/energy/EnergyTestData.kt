package com.sleepsaver.feature.energy

import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.BedtimeMode
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.night.NightAssembler
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.SampleData
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Ready states built from SampleData through the real engine, for tests and screenshots. */
internal object EnergyTestData {
    val zone: ZoneId = SampleData.zone

    fun at(date: LocalDate, hour: Int, minute: Int = 0, zone: ZoneId = this.zone): Instant =
        ZonedDateTime.of(date, LocalTime.of(hour, minute), zone).toInstant()

    fun at(hour: Int, minute: Int = 0): Instant = at(SampleData.today, hour, minute)

    fun ready(
        now: Instant = SampleData.now,
        zone: ZoneId = this.zone,
        nights: List<Night> = SampleData.nights(today = now.atZone(zone).toLocalDate(), zone = zone),
        settings: UserSleepSettings = SampleData.settings,
        alarms: List<PlannedAlarm> = emptyList(),
        anchoredAt: Instant? = null,
    ): EnergyUiState.Ready {
        val insights = SampleData.insights(now = now, zone = zone, settings = settings, nights = nights)
        return energyReadyState(insights, nights, settings.sleepNeed, now, alarms = alarms, anchoredAt = anchoredAt)
    }

    /** 11:30 PM, anchored at 10:55 PM, tomorrow's 6:45 AM alarm armed: the no-clock night. */
    fun nightMode(): EnergyUiState.Ready =
        ready(now = at(23, 30), alarms = listOf(PlanSamples.plannedAlarm()), anchoredAt = at(22, 55))

    /** Last night hasn't synced: nights end yesterday. */
    fun waiting(now: Instant = at(8, 40)): EnergyUiState.Ready =
        ready(now = now, nights = SampleData.nights(includeLastNight = false))

    /**
     * Nights from a simple bed/wake pattern ([bed] on the evening before or the early morning of each wake date,
     * [wake] on the wake date), ending on [today].
     */
    fun patternNights(
        today: LocalDate,
        bed: LocalTime,
        wake: LocalTime,
        count: Int = 21,
        zone: ZoneId = this.zone,
        includeLastNight: Boolean = true,
    ): List<Night> {
        val sessions = (0 until count).mapNotNull { k ->
            if (k == 0 && !includeLastNight) return@mapNotNull null
            val wakeDate = today.minusDays(k.toLong())
            val bedDate = if (bed.isAfter(wake)) wakeDate.minusDays(1) else wakeDate
            // A little night-to-night jitter so it looks real.
            val jitter = ((k * 7) % 11 - 5).toLong()
            session("p:$k", LocalDateTime.of(bedDate, bed).plusMinutes(jitter), LocalDateTime.of(wakeDate, wake).plusMinutes(jitter / 2), zone)
        }
        return NightAssembler().assemble(sessions, SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE))
    }

    /** A night owl: asleep around 2:10 AM, up around 10:05 AM. */
    fun nightOwl(now: Instant = at(15, 30)): EnergyUiState.Ready {
        val today = now.atZone(zone).toLocalDate()
        val nights = patternNights(today, LocalTime.of(2, 10), LocalTime.of(10, 5))
        return ready(now = now, nights = nights, settings = SampleData.settings.copy(typicalWake = LocalTime.of(10, 0)))
    }

    /**
     * A night owl at 00:40, still on yesterday's schedule because yesterday's bedtime (~01:55) is ahead: what the
     * engine reports between midnight and the end of yesterday's melatonin window. Built from the evening before
     * with the calendar moved past midnight, so it doesn't depend on the engine's own rollover.
     */
    fun nightOwlAfterMidnight(): EnergyUiState.Ready {
        val yesterday = SampleData.today.minusDays(1)
        val evening = at(yesterday, 23, 30)
        val now = at(SampleData.today, 0, 40)
        val nights = patternNights(yesterday, LocalTime.of(2, 10), LocalTime.of(10, 5))
        val settings = SampleData.settings.copy(typicalWake = LocalTime.of(10, 0))
        val insights = SampleData.insights(now = evening, zone = zone, settings = settings, nights = nights).copy(
            today = SampleData.today,
            now = now,
            lastNight = null,
            lastNightState = com.sleepsaver.core.domain.model.LastNightState.NOT_EXPECTED_YET,
        )
        return energyReadyState(insights, nights, settings.sleepNeed, now)
    }

    fun session(id: String, start: LocalDateTime, end: LocalDateTime, zone: ZoneId = this.zone): RawSleepSession {
        val s = start.atZone(zone).toInstant()
        val e = end.atZone(zone).toInstant()
        return RawSleepSession(
            id = id,
            origin = SleepConfig.GARMIN_CONNECT_PACKAGE,
            start = s,
            end = e,
            startOffset = zone.rules.getOffset(s),
            endOffset = zone.rules.getOffset(e),
        )
    }

    val longNeed: Duration = Duration.ofMinutes(10 * 60 + 30)

    /** A long need with bedtime set to leave room for it, so wind-down reaches back into the evening peak. */
    val longNeedSettings: UserSleepSettings =
        SampleData.settings.copy(sleepNeed = longNeed, bedtimeMode = BedtimeMode.SLEEP_NEED)
}
