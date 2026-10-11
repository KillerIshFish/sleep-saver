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
