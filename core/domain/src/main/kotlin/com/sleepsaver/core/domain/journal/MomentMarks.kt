package com.sleepsaver.core.domain.journal

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Where a derived mark logged at moments lands (PLAN §3.14): Hush on (each Hush play's start) and Anchor dropped (each
 * tap). [dates] = the wake dates it marks; [first] = the first wake date it could have marked (null without moments).
 */
data class MomentMarkDates(val dates: Set<LocalDate>, val first: LocalDate?)

/**
 * A moment goes with the night of its stored wake date ([com.sleepsaver.core.domain.schedule.NightRules.wakeDateOf]:
 * 23:10 Monday → Tuesday, 01:00 Tuesday → Tuesday) when it fell in that night's span:
 *
 * - from its onset (main sleep or an earlier extra segment) − journal.momentLead, so the evening's wind-down counts
 *   and Hush for an afternoon nap doesn't. Before that the night was still ahead: no night, and that one is the first
 *   it could have marked;
 * - until its morning: the later of its last wake-up (main sleep or a later extra segment; that day's naps aren't the
 *   night) and [plannedWake] of its wake date (the plan's set time, else the usual wake: ScheduleTimes.wake) at the
 *   night's wake-up offset. So a night that ended at 03:00 without more sleep still runs until the planned 07:00, and
 *   Hush from Night reset at 03:20 counts for it. At or after its morning the moment was daytime use: no night, and the
 *   first night it could have marked is the next one.
 *
 * A night that isn't in yet keeps its moments.
 *
 * Example: Monday night 23:10 → Tuesday 07:20, planned wake 07:00. Hush started Monday 15:00 → no night (first possible:
 * Tuesday); Monday 21:30 → Tuesday; Tuesday 05:40 → Tuesday; Tuesday 10:27 → no night (first possible: Wednesday);
 * Tuesday 22:30 → Wednesday (past the 14:00 cutoff).
 */
fun momentMarkDates(
    moments: List<Pair<Instant, LocalDate>>,
    nights: Map<LocalDate, Night>,
    plannedWake: (LocalDate) -> LocalTime,
    config: SleepConfig = SleepConfig.DEFAULT,
): MomentMarkDates {
    val spans = HashMap<LocalDate, NightSpan?>() // one plannedWake call per wake date
    val dates = HashSet<LocalDate>()
    var first: LocalDate? = null
    for ((at, wakeDate) in moments) {
        if (wakeDate !in spans) spans[wakeDate] = nights[wakeDate]?.span(plannedWake, config)
        val from = spans[wakeDate]?.from
        val until = spans[wakeDate]?.until
        val possible = when {
            from != null && at < from -> wakeDate // before the night: it was still ahead
            until != null && at >= until -> wakeDate.plusDays(1) // after its morning: it was over
            else -> wakeDate.also { dates += it }
        }
        if (first == null || possible < first) first = possible
    }
    return MomentMarkDates(dates, first)
}

/** Where a night's moments fall: [from] (inclusive) until [until] (exclusive); null = open on that side. */
private class NightSpan(val from: Instant?, val until: Instant?)

private fun Night.span(plannedWake: (LocalDate) -> LocalTime, config: SleepConfig): NightSpan? {
    if (!isPresent) return null
    val onset = (listOfNotNull(fellAsleep) + extraSegments.map { it.start }).minOrNull()
    val lastWokeUp = (listOfNotNull(wokeUp) + extraSegments.map { it.end }).maxOrNull()
    val morning = (wokeUpOffset ?: fellAsleepOffset)?.let { LocalDateTime.of(wakeDate, plannedWake(wakeDate)).toInstant(it) }
    return NightSpan(from = onset?.minus(config.journal.momentLead), until = listOfNotNull(lastWokeUp, morning).maxOrNull())
}
