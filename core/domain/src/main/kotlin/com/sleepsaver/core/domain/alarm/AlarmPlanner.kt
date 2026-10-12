package com.sleepsaver.core.domain.alarm

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.ResolvedDay
import com.sleepsaver.core.domain.schedule.WeekPlan
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Picks alarms from the week plan. Pure. Example: Mon–Fri at 06:30 and now Friday 07:00 → the next alarm is Monday
 * 06:30 (Saturday and Sunday have none).
 */
class AlarmPlanner(private val config: SleepConfig = SleepConfig.DEFAULT) {

    /**
     * Alarms after [now], in time order, from dates today .. today + (week.alarmPlanningDays − 1) (today = now in [zone]):
     * for each date with resolve(date).alarmOn, target = ZonedDateTime.of(date, wakeTime, zone) (gap → forward,
     * overlap → earlier offset), source JUST_ONCE when the override made it, else DAY_PLAN.
     * Kept when target > now and id ∉ [exclude] (stopped, timed-out or in-progress occurrences), and, for a DAY_PLAN
     * alarm, date ∉ [endedDates] (mornings whose alarm already rang out or was missed). Editing the day's time makes a
     * new id, so the date is what stops an edit at 06:40 (06:30 → 07:15, meant for next week) from ringing again this
     * morning; a JUST_ONCE alarm on that date is kept, since the user set it for that very morning. At most [limit].
     * The two-week horizon means a skipped morning never hides its weekday's next one: Monday-only alarms, Monday's
     * already rang and the clock went back to Sunday evening → the Monday after, today + 8.
     */
    fun upcoming(
        week: WeekPlan,
        oneOffs: OneOffs,
        now: Instant,
        zone: ZoneId,
        exclude: Set<String> = emptySet(),
        endedDates: Set<LocalDate> = emptySet(),
        limit: Int = config.week.upcomingLimit,
    ): List<AlarmOccurrence> {
        val today = now.atZone(zone).toLocalDate()
        return dates(today, 0)
            .mapNotNull { date -> occurrence(week.resolve(date, oneOffs), zone) }
            .filter { it.target > now && it.id !in exclude && !(it.source == AlarmSource.DAY_PLAN && it.date in endedDates) }
            .sortedBy { it.target }
            .take(limit.coerceAtLeast(0))
    }

    fun next(
        week: WeekPlan,
        oneOffs: OneOffs,
        now: Instant,
        zone: ZoneId,
        exclude: Set<String> = emptySet(),
        endedDates: Set<LocalDate> = emptySet(),
    ): AlarmOccurrence? = upcoming(week, oneOffs, now, zone, exclude, endedDates, limit = 1).firstOrNull()

    /** Ids of every alarmOn date in today − 1 .. today + alarmPlanningDays − 1, ignoring now and exclusions ("still planned"). */
    fun plannedIds(week: WeekPlan, oneOffs: OneOffs, now: Instant, zone: ZoneId): Set<String> {
        val today = now.atZone(zone).toLocalDate()
        return dates(today, 1).mapNotNullTo(LinkedHashSet()) { date ->
            val day = week.resolve(date, oneOffs)
            val time = day.wakeTime
            if (day.alarmOn && time != null) AlarmOccurrence.occurrenceId(date, time) else null
        }
    }

    /** today − [daysBack] .. today + alarmPlanningDays − 1. */
    private fun dates(today: LocalDate, daysBack: Long): List<LocalDate> =
        (-daysBack until config.week.alarmPlanningDays.toLong()).map { today.plusDays(it) }

    private fun occurrence(day: ResolvedDay, zone: ZoneId): AlarmOccurrence? {
        val time = day.wakeTime
        if (!day.alarmOn || time == null) return null
        val target = ZonedDateTime.of(day.date, time, zone)
        return AlarmOccurrence(
            date = day.date,
            localTime = time,
            target = target.toInstant(),
            offset = target.offset,
            settings = day.alarm,
            source = if (day.isJustOnce) AlarmSource.JUST_ONCE else AlarmSource.DAY_PLAN,
        )
    }
}
