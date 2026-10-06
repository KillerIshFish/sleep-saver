package com.sleepsaver.core.notifications

import com.sleepsaver.core.domain.model.ScheduledHabit
import java.time.Duration
import java.time.Instant

/**
 * Which habit reminders to post and when the single reminder alarm should go off (docs/PLAN.md §3.8). Pure.
 *
 * A reminder is *pending* when its habit and its reminder are both on, it hasn't been posted yet
 * (time after `postedThrough`) and its time is within [now − catchUp, now + horizon]. A null `postedThrough`
 * means nothing is overdue. The catch-up window keeps a reminder whose alarm Doze delayed past a replan:
 * the alarm is re-armed in the past, fires at once and posts it instead of silently dropping it.
 *
 * When the alarm fires, every pending reminder due by now + [dueTolerance] is posted together, so reminders
 * a minute or two apart (dim lights + evening routine) share one alarm.
 */
internal class ReminderPlanning(
    private val horizon: Duration = Duration.ofHours(36),
    private val dueTolerance: Duration = Duration.ofMinutes(2),
    private val catchUp: Duration = Duration.ofMinutes(15),
) {
    /** Pending reminders ordered by time; the same habit at the same time (today's and tomorrow's lists overlapping) counts once. */
    fun pending(habits: List<ScheduledHabit>, now: Instant, postedThrough: Instant?): List<ScheduledHabit> {
        val after = lowerBound(now, postedThrough)
        val until = now.plus(horizon)
        return habits
            .filter { it.enabled && it.reminderEnabled && it.time.isAfter(after) && !it.time.isAfter(until) }
            .distinctBy { it.type to it.time }
            .sortedBy { it.time }
    }

    /** When to arm the alarm: the earliest pending reminder (possibly overdue, so it fires right away), or null for none. */
    fun nextAlarm(habits: List<ScheduledHabit>, now: Instant, postedThrough: Instant?): Instant? =
        pending(habits, now, postedThrough).firstOrNull()?.time

    /** What to post when the alarm fires at [now]. */
    fun due(habits: List<ScheduledHabit>, now: Instant, postedThrough: Instant?): List<ScheduledHabit> {
        val until = now.plus(dueTolerance)
        return pending(habits, now, postedThrough).filter { !it.time.isAfter(until) }
    }

    /** The new `postedThrough` after firing at [now]: everything [due] covered, and nothing later. */
    fun postedThroughAfterFiring(now: Instant): Instant = now.plus(dueTolerance)

    private fun lowerBound(now: Instant, postedThrough: Instant?): Instant {
        // A postedThrough beyond what a fire at `now` could have covered means the clock was set back: start over from now.
        val posted = postedThrough?.takeUnless { it.isAfter(now.plus(dueTolerance)) } ?: now
        return maxOf(posted, now.minus(catchUp))
    }
}
