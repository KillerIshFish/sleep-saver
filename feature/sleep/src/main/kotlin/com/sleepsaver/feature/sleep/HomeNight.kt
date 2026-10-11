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
 * [NightRules.isNight] for the night in progress or just ahead (§5.0).
 *
 * - Before [schedule]'s wake zone opens (the small hours, once the schedule has moved on to the new day) it is last
 *   night: from the evening-before bedtime (that alarm's planned bedtime, else the schedule's bedtime a day earlier)
 *   until this morning's alarm window or wake zone.
 * - Otherwise it is tonight: from `schedule.targetBedtime` until tomorrow's alarm window or wake zone.
 *
 * Only that night's own alarm and anchor count (the anchor's wake date by [NightRules.wakeDateOf]), so an alarm days
 * away never stretches the night and last night's anchor doesn't keep the morning dark. [anchoredAt] is
 * `NightController.state.night?.anchoredAt`.
 * Example: bedtime 23:00 and tomorrow's 6:45 alarm with a 30-min window → night from 23:00 (or Drop anchor) to 6:15.
 */
internal fun homeNight(
    schedule: EnergySchedule,
    now: Instant,
    alarms: List<PlannedAlarm>,
    anchoredAt: Instant?,
    config: SleepConfig = SleepConfig.DEFAULT,
): HomeNight {
    val lastNight = now < schedule.wakeZone.start
    val wakeDate = if (lastNight) schedule.date else schedule.date.plusDays(1)
    val alarm = alarms.firstOrNull { it.occurrence.date == wakeDate }
    val bedtime = if (lastNight) {
        alarm?.plannedBedtime ?: schedule.targetBedtime.atZone(schedule.zone).minusDays(1).toInstant()
    } else {
        schedule.targetBedtime
    }
    val wakeZoneStart = if (lastNight) schedule.wakeZone.start else schedule.tomorrowWakeZone.start
    val end = NightRules.nightEnd(alarm?.window?.start, wakeZoneStart)
    val anchor = anchoredAt?.takeIf { NightRules.wakeDateOf(it, schedule.zone.rules.getOffset(it), config) == wakeDate }
    return HomeNight(wakeDate, alarm, NightRules.isNight(now, bedtime, end, anchor, config))
}
