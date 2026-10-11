package com.sleepsaver.feature.sleep

import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.schedule.NightRules
import java.time.Instant
import java.time.LocalDate

/** The night home is about: the wake date it ends on, the alarm that ends it, and whether it's the no-clock night now. */
internal data class HomeNight(val wakeDate: LocalDate, val alarm: PlannedAlarm?, val isNight: Boolean)

/**
 * The night in progress or just ahead, by [NightRules.focus] (the rule Tonight and Energy use too), with each night's
 * bounds from the energy [schedule] and that morning's alarm ([NightRules.boundsFrom]).
 *
 * [morningStop] is when this morning's planned alarm was stopped or timed out ([NightRules.morningStop]): it ends last
 * night at once, even when an early surfacing ring came before the wake zone. [anchoredAt] is
 * `NightController.state.night?.anchoredAt`. Only that night's own alarm and anchor count, so an alarm days away never
 * stretches the night and last night's anchor doesn't keep the morning dark.
 * Example: bedtime 23:00 and tomorrow's 6:45 alarm with a 30-min window → night from 23:00 (or Drop anchor) to 6:15.
 */
internal fun homeNight(
    schedule: EnergySchedule,
    now: Instant,
    alarms: List<PlannedAlarm>,
    anchoredAt: Instant?,
    morningStop: Instant? = null,
    config: SleepConfig = SleepConfig.DEFAULT,
): HomeNight {
    val today = now.atZone(schedule.zone).toLocalDate()
    fun alarmFor(wakeDate: LocalDate): PlannedAlarm? = alarms.firstOrNull { it.occurrence.date == wakeDate }
    fun bounds(evening: LocalDate) = alarmFor(evening.plusDays(1)).let { alarm ->
        NightRules.boundsFrom(schedule, evening, alarm?.window?.start, alarm?.plannedBedtime)
    }
    val focus = NightRules.focus(
        now = now,
        zone = schedule.zone,
        lastNight = bounds(today.minusDays(1)),
        tonight = bounds(today),
        morningStop = morningStop,
        anchoredAt = anchoredAt,
        eveningStillYesterday = schedule.date < today,
        config = config,
    )
    return HomeNight(focus.wakeDate, alarmFor(focus.wakeDate), focus.isNight)
}
