package com.sleepsaver.core.domain.alarm

import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.AlarmSettings
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Where a planned alarm came from: the day's plan, or a "Just this once" row. */
enum class AlarmSource { DAY_PLAN, JUST_ONCE }

/**
 * One planned alarm. Its id survives zone changes and re-arming; editing the time makes a new id.
 * Example: Wednesday 2026-10-07 at 06:30 has the id "2026-10-07T06:30" in every zone.
 */
data class AlarmOccurrence(
    /** The wake date. */
    val date: LocalDate,
    val localTime: LocalTime,
    /** The wake time: the latest moment it rings. */
    val target: Instant,
    /** The offset [target] resolved to (store it with events). */
    val offset: ZoneOffset,
    val settings: AlarmSettings,
    val source: AlarmSource,
) {
    /** "2026-10-07T06:30": date + local wall time (minutes). */
    val id: String get() = occurrenceId(date, localTime)

    /** Base window: settings.window for SURFACING, zero for FIXED. */
    val window: Duration get() = if (settings.mode == AlarmMode.SURFACING) settings.window else Duration.ZERO

    companion object {
        fun occurrenceId(date: LocalDate, time: LocalTime): String = "${date}T${time.truncatedTo(ChronoUnit.MINUTES)}"
    }
}

/**
 * What already happened to planned alarms, which AlarmPlanner.upcoming never plans again: [ids] of the occurrences that
 * ended (stopped, timed out, missed or cancelled), and the [dates] whose alarm ended the morning
 * ([RingOutcome.endsMorning]), where only a just-once alarm can still ring.
 *
 * An occurrence is its date and time, so a just-once alarm at the very time that already ended there is that same
 * occurrence and never rings either. Example: Monday's 6:50 rang and the clock went back to Sunday: Monday at 6:50
 * can't ring again, even set just this once; Monday at 6:55 just this once can.
 */
data class EndedAlarms(val ids: Set<String> = emptySet(), val dates: Set<LocalDate> = emptySet()) {
    /** Whether an alarm at [time] on [date] stays unplanned; [justOnce] = a one-off made it. */
    fun leavesOut(date: LocalDate, time: LocalTime, justOnce: Boolean): Boolean =
        AlarmOccurrence.occurrenceId(date, time) in ids || (!justOnce && date in dates)
}
