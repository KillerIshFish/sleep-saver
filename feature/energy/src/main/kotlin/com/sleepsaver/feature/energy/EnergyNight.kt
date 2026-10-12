package com.sleepsaver.feature.energy

import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.schedule.NightRules
import java.time.Instant
import java.time.LocalDate

/**
 * Whether the Energy tab is in the no-clock night (§5.0): [NightRules.focus], the rule Tonight and Sleep home use too,
 * with each night's bounds from the energy [schedule] and that morning's alarm ([NightRules.boundsFrom]).
 *
 * [morningStop] (this morning's planned alarm stopped or timed out) ends last night at once. [anchoredAt] is
 * `NightController.state.night?.anchoredAt`; it counts only for the night of its own wake date. [endedAt] is
 * `NightController.state.endedAt`, the last "I'm up": it ends that night's no-clock time until the next anchor, as on
 * Tonight.
 * Example: bedtime 23:00 and tomorrow's 6:45 alarm with a 30-min window → night from 23:00 (or Drop anchor) to 6:15.
 */
internal fun isEnergyNight(
    schedule: EnergySchedule,
    now: Instant,
    alarms: List<PlannedAlarm>,
    anchoredAt: Instant?,
    morningStop: Instant? = null,
    endedAt: Instant? = null,
    config: SleepConfig = SleepConfig.DEFAULT,
): Boolean {
    val today = now.atZone(schedule.zone).toLocalDate()
    fun bounds(evening: LocalDate) = alarms.firstOrNull { it.occurrence.date == evening.plusDays(1) }.let { alarm ->
        NightRules.boundsFrom(schedule, evening, alarm?.window?.start, alarm?.plannedBedtime)
    }
    return NightRules.focus(
        now = now,
        zone = schedule.zone,
        lastNight = bounds(today.minusDays(1)),
        tonight = bounds(today),
        morningStop = morningStop,
        anchoredAt = anchoredAt,
        endedAt = endedAt,
        eveningStillYesterday = schedule.date < today,
        config = config,
    ).isNight
}
