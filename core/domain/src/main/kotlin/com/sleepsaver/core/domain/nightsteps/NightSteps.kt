package com.sleepsaver.core.domain.nightsteps

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.domain.schedule.EveningPlan
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** A checked step (stored per evening). [nightOf] is the evening; [offset] the one [doneAt] was recorded in. */
data class StepLog(val nightOf: LocalDate, val activityId: Long, val technique: Technique?, val doneAt: Instant, val offset: ZoneOffset)

/** One of tonight's steps, from [start] to [end] (a zero-length step is a checklist item). */
data class NightStep(val activity: RoutineActivity, val start: Instant, val end: Instant, val doneAt: Instant?) {
    val done: Boolean get() = doneAt != null
}

/**
 * Tonight's checklist. Example: B = 23:00, wind-down 90 min → "Night steps 21:00–23:00, bedtime 23:00" (the window
 * starts at the wind-down phase start: B − melatoninHalfWidth − length).
 */
data class NightStepsSession(
    /** The evening (EnergySchedule.date); the wake date is nightOf + 1. */
    val nightOf: LocalDate,
    /** Wind-down phase start: the start of the Night steps window ("Night steps from 9:45 PM"). */
    val windowStart: Instant,
    /** Target bedtime B. */
    val bedtime: Instant,
    val steps: List<NightStep>,
    /** The evening plan's switch. */
    val enabled: Boolean,
    /** True when B came from the user's own bedtime because there are no nights yet. */
    val fromSettingsOnly: Boolean = false,
) {
    val doneCount: Int get() = steps.count { it.done }
    val isComplete: Boolean get() = steps.isNotEmpty() && steps.all { it.done }

    /** First unchecked step whose end ≥ now; else the first unchecked step; null when all are done. */
    fun currentStep(now: Instant): NightStep? {
        val open = steps.filter { !it.done }
        return open.firstOrNull { it.end >= now } ?: open.firstOrNull()
    }
}

/**
 * The evening's routine: the evening plan's own list (ids in order, unknown ids dropped) or, when null, the
 * selected activities in their stored order. A repeated id is listed once.
 */
fun routineFor(evening: EveningPlan, activities: List<RoutineActivity>): List<RoutineActivity> {
    val own = evening.routine ?: return activities.filter { it.selected }
    val byId = activities.associateBy { it.id }
    return own.distinct().mapNotNull { byId[it] }
}

/** Places a routine before the target bedtime (DECISIONS A2). Example: lead 30 min, duration 10 min, B 23:00 → 22:30–22:40. */
class NightStepsPlanner(private val config: SleepConfig = SleepConfig.DEFAULT) {

    /**
     * start = bedtime − lead, end = start + duration (may pass B; overlaps allowed). Sorted by start, then by the
     * routine order. doneAt from [logs] with that nightOf and activity id.
     */
    fun plan(
        nightOf: LocalDate,
        windowStart: Instant,
        bedtime: Instant,
        routine: List<RoutineActivity>,
        logs: List<StepLog>,
        enabled: Boolean,
        fromSettingsOnly: Boolean = false,
    ): NightStepsSession {
        val doneAt = logs.filter { it.nightOf == nightOf }
            .groupBy { it.activityId }
            .mapValues { (_, done) -> done.minOf { it.doneAt } }
        val steps = routine
            .map { activity ->
                val start = bedtime - activity.lead
                NightStep(activity, start, start + activity.duration, doneAt[activity.id])
            }
            .sortedBy { it.start } // stable: equal starts keep the routine order
        return NightStepsSession(nightOf, windowStart, bedtime, steps, enabled, fromSettingsOnly)
    }
}
