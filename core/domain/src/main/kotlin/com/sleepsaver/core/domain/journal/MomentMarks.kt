package com.sleepsaver.core.domain.journal

import com.sleepsaver.core.domain.model.Night
import java.time.Instant
import java.time.LocalDate

/**
 * Where a derived mark logged at moments lands (PLAN §3.14): Hush on (each Hush play's start) and Anchor dropped (each
 * tap). [dates] = the wake dates it marks; [first] = the first wake date it could have marked (null without moments).
 */
data class MomentMarkDates(val dates: Set<LocalDate>, val first: LocalDate?)

/**
 * A moment goes with the night of its stored wake date ([com.sleepsaver.core.domain.schedule.NightRules.wakeDateOf]:
 * 23:10 Monday → Tuesday, 01:00 Tuesday → Tuesday) while that night is still on: before its last wake-up. At or after
 * it the night had ended, so the moment was daytime use and goes with no night, the way Night steps checked in the day
 * count for the evening ahead and never for the night before; the first night it could have marked is then the next
 * one. A night that isn't in yet (or has no wake time) keeps its moments.
 *
 * Example: Monday night ended Tuesday 07:20. Hush started Tuesday 05:40 → Tuesday; Tuesday 10:27 → no night (first
 * possible: Wednesday); Tuesday 22:30 → Wednesday (past the 14:00 cutoff).
 */
fun momentMarkDates(moments: List<Pair<Instant, LocalDate>>, nights: Map<LocalDate, Night>): MomentMarkDates {
    val dates = HashSet<LocalDate>()
    var first: LocalDate? = null
    for ((at, wakeDate) in moments) {
        val ended = nights[wakeDate]?.lastWokeUp()?.let { at >= it } == true
        if (!ended) dates += wakeDate
        val possible = if (ended) wakeDate.plusDays(1) else wakeDate
        if (first == null || possible < first) first = possible
    }
    return MomentMarkDates(dates, first)
}

/** When the night was over: the end of its main sleep or of a later extra segment (that day's naps aren't the night). */
private fun Night.lastWokeUp(): Instant? = (listOfNotNull(wokeUp) + extraSegments.map { it.end }).maxOrNull()
