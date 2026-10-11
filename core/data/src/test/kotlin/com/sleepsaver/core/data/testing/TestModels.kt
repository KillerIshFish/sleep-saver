package com.sleepsaver.core.data.testing

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.AwakePeriod
import com.sleepsaver.core.domain.model.BlockKind
import com.sleepsaver.core.domain.model.CircadianAnchor
import com.sleepsaver.core.domain.model.DailyInsights
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.EnergyCurve
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.LastNightState
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.NightOverride
import com.sleepsaver.core.domain.model.SleepBlock
import com.sleepsaver.core.domain.model.SleepDebt
import com.sleepsaver.core.domain.model.SleepNeedEstimateResult
import com.sleepsaver.core.domain.model.StageTotals
import com.sleepsaver.core.domain.model.Streak
import com.sleepsaver.core.domain.model.TimeSpan
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

const val GARMIN = SleepConfig.GARMIN_CONNECT_PACKAGE

/** A present night from local [fellAsleep] (on wakeDate − 1) to local [wokeUp] (on wakeDate) in [offset]. */
fun testNight(
    wakeDate: LocalDate,
    fellAsleep: String = "23:00",
    wokeUp: String = "07:00",
    offset: ZoneOffset = ZoneOffset.UTC,
    awake: Duration = Duration.ZERO,
    napSleep: Duration = Duration.ZERO,
    stageTotals: StageTotals? = null,
    interruptions: List<AwakePeriod> = emptyList(),
    override: NightOverride? = null,
    origin: String? = GARMIN,
): Night {
    val start = LocalDateTime.of(wakeDate.minusDays(1), LocalTime.parse(fellAsleep)).toInstant(offset)
    val end = LocalDateTime.of(wakeDate, LocalTime.parse(wokeUp)).toInstant(offset)
    val main = Duration.between(start, end).minus(awake)
    return Night(
        wakeDate = wakeDate,
        main = SleepBlock(BlockKind.MAIN, start, end, offset, offset, main, origin = origin),
        fellAsleep = start,
        wokeUp = end,
        fellAsleepOffset = offset,
        wokeUpOffset = offset,
        mainSleep = main,
        awakeInMain = awake,
        extraSleep = Duration.ZERO,
        napSleep = napSleep,
        totalSleep = main.plus(napSleep),
        stageTotals = stageTotals,
        interruptions = interruptions,
        override = override,
        origin = origin,
    )
}

/** A day with a nap but no night sleep: not present. */
fun napOnlyDay(wakeDate: LocalDate, nap: Duration = Duration.ofMinutes(25)): Night = Night(
    wakeDate = wakeDate,
    main = null,
    fellAsleep = null,
    wokeUp = null,
    fellAsleepOffset = null,
    wokeUpOffset = null,
    mainSleep = Duration.ZERO,
    awakeInMain = Duration.ZERO,
    extraSleep = Duration.ZERO,
    napSleep = nap,
    totalSleep = nap,
    stageTotals = null,
    origin = GARMIN,
)

fun testInsights(
    today: LocalDate,
    lastNightState: LastNightState,
    zone: ZoneId = ZoneOffset.UTC,
): DailyInsights {
    val wake = today.atTime(7, 0).atZone(zone)
    val bedtime = wake.plusHours(15).toInstant()
    val schedule = EnergySchedule(
        date = today,
        zone = zone,
        typicalWake = wake,
        actualWake = null,
        wakeZone = TimeSpan(wake.minusMinutes(30).toInstant(), wake.plusMinutes(30).toInstant()),
        tomorrowWakeZone = TimeSpan(wake.plusHours(23).plusMinutes(30).toInstant(), wake.plusHours(24).plusMinutes(30).toInstant()),
        targetBedtime = bedtime,
        lastNightBedtime = bedtime.minus(Duration.ofDays(1)),
        phases = emptyList(),
        curve = EnergyCurve(emptyList()),
        anchor = CircadianAnchor(midpointMinuteOfDay = 180.0, typicalWake = LocalTime.of(7, 0), nightsUsed = 0, isFallback = true),
    )
    val debt = SleepDebt(asOf = today, debt = Duration.ZERO, rawWeightedSum = Duration.ZERO, contributions = emptyList(), missingNights = emptyList())
    return DailyInsights(
        today = today,
        now = wake.plusHours(1).toInstant(),
        zone = zone,
        lastNight = if (lastNightState == LastNightState.AVAILABLE) testNight(today) else null,
        lastNightState = lastNightState,
        debt = debt,
        debtChange = null,
        status = DebtStatus.LOW,
        streak = Streak(0, null),
        energyPotential = 100,
        schedule = schedule,
        currentPhase = null,
        upcomingPhases = emptyList(),
        habits = emptyList(),
        nextHabit = null,
        missingNights = emptyList(),
        needEstimate = SleepNeedEstimateResult.NotEnoughData(0, 14, 0, 4),
    )
}

fun instant(value: String): Instant = Instant.parse(value)
