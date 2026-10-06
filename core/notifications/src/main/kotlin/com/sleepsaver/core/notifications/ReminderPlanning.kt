package com.sleepsaver.core.notifications

import com.sleepsaver.core.domain.model.HabitType
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
 * `lastPosted` holds, per habit, the time of the reminder posted most recently. A habit posted within
 * [repostGap] of its current time was already posted for this day: when a schedule update (say, last night
 * syncing and nudging typical wake) moves it a few minutes later, it isn't posted a second time.
 *
 * When the alarm fires, every pending reminder due by now + [dueTolerance] is posted together, so reminders
 * a minute or two apart (dim lights + evening routine) share one alarm.
 */
internal class ReminderPlanning(
    private val horizon: Duration = Duration.ofHours(36),
    private val dueTolerance: Duration = Duration.ofMinutes(2),
    private val catchUp: Duration = Duration.ofMinutes(15),
    private val repostGap: Duration = Duration.ofHours(12),
) {
    /** Pending reminders ordered by time; the same habit at the same time (overlapping days' lists) counts once. */
    fun pending(
        habits: List<ScheduledHabit>,
        now: Instant,
        postedThrough: Instant?,
        lastPosted: Map<HabitType, Instant> = emptyMap(),
    ): List<ScheduledHabit> {
        val after = lowerBound(now, postedThrough)
        val until = now.plus(horizon)
        return habits
            .filter { it.enabled && it.reminderEnabled && it.time.isAfter(after) && !it.time.isAfter(until) }
            .filterNot { postedForThisDay(it, now, lastPosted[it.type]) }
            .distinctBy { it.type to it.time }
            .sortedBy { it.time }
    }

    /** When to arm the alarm: the earliest pending reminder (possibly overdue, so it fires right away), or null for none. */
    fun nextAlarm(
        habits: List<ScheduledHabit>,
        now: Instant,
        postedThrough: Instant?,
        lastPosted: Map<HabitType, Instant> = emptyMap(),
    ): Instant? = pending(habits, now, postedThrough, lastPosted).firstOrNull()?.time

    /** What to post when the alarm fires at [now]. */
    fun due(
        habits: List<ScheduledHabit>,
        now: Instant,
        postedThrough: Instant?,
        lastPosted: Map<HabitType, Instant> = emptyMap(),
    ): List<ScheduledHabit> {
        val until = now.plus(dueTolerance)
        return pending(habits, now, postedThrough, lastPosted).filter { !it.time.isAfter(until) }
    }

    /** The new `postedThrough` after firing at [now]: everything [due] covered, and nothing later. */
    fun postedThroughAfterFiring(now: Instant): Instant = now.plus(dueTolerance)

    private fun lowerBound(now: Instant, postedThrough: Instant?): Instant {
        // A postedThrough beyond what a fire at `now` could have covered means the clock was set back: start over from now.
        val posted = postedThrough?.takeUnless { it.isAfter(now.plus(dueTolerance)) } ?: now
        return maxOf(posted, now.minus(catchUp))
    }

    private fun postedForThisDay(habit: ScheduledHabit, now: Instant, lastPosted: Instant?): Boolean =
        // A lastPosted in the future means the clock was set back; don't let it silence anything.
        lastPosted != null && !lastPosted.isAfter(now.plus(dueTolerance)) &&
            Duration.between(lastPosted, habit.time).abs() < repostGap
}
