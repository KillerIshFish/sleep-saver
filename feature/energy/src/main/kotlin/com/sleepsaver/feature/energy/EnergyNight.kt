package com.sleepsaver.feature.energy

import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.schedule.NightRules
import java.time.Instant

/**
 * Whether the Energy tab is in the no-clock night (§5.0): [NightRules.isNight] for the night in progress or just ahead,
 * the same rule as Sleep home's (features can't share code, so it is repeated here).
 *
 * - Before [schedule]'s wake zone opens (the small hours, once the schedule has moved on to the new day) it is last
 *   night: from the evening-before bedtime (that alarm's planned bedtime, else the schedule's bedtime a day earlier)
 *   until this morning's alarm window or wake zone.
 * - Otherwise it is tonight: from `schedule.targetBedtime` until tomorrow's alarm window or wake zone.
 *
 * Only that night's own alarm and anchor count (the anchor's wake date by [NightRules.wakeDateOf]). [anchoredAt] is
 * `NightController.state.night?.anchoredAt`.
 * Example: bedtime 23:00 and tomorrow's 6:45 alarm with a 30-min window → night from 23:00 (or Drop anchor) to 6:15.
 */
internal fun isEnergyNight(
    schedule: EnergySchedule,
    now: Instant,
    alarms: List<PlannedAlarm>,
    anchoredAt: Instant?,
    config: SleepConfig = SleepConfig.DEFAULT,
): Boolean {
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
    return NightRules.isNight(now, bedtime, end, anchor, config)
}
