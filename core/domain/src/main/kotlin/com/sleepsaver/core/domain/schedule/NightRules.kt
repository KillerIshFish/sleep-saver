package com.sleepsaver.core.domain.schedule

import com.sleepsaver.core.domain.config.SleepConfig
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Night-time rules shared by Tonight, Sleep home, Energy, reminders and the alarm. Example: with bedtime 23:00 and a
 * 6:00 window start, 23:30 is night (no clock anywhere), 6:00 is not.
 */
object NightRules {

    /**
     * The evening (EnergySchedule.date) a moment belongs to when there is no schedule: yesterday while now is before
     * yesterday's bedtime + melatoninHalfWidth, else today (mirrors InsightsEngine's night-owl rule).
     */
    fun eveningFor(now: Instant, zone: ZoneId, bedtimeOn: (LocalDate) -> Instant, config: SleepConfig = SleepConfig.DEFAULT): LocalDate {
        val today = now.atZone(zone).toLocalDate()
        val yesterday = today.minusDays(1)
        return if (now < bedtimeOn(yesterday) + config.melatoninHalfWidth) yesterday else today
    }

    /** Where the no-clock night ends: the next alarm's effective window start, else the wake zone start. */
    fun nightEnd(alarmWindowStart: Instant?, wakeZoneStart: Instant?): Instant? = alarmWindowStart ?: wakeZoneStart

    /**
     * No-clock night: (anchored, or now ≥ bedtime) and now < end; a bedtime more than week.nightBedtimeMaxLead (18 h)
     * before end is ignored. [anchoredAt] = NightController.state.night?.anchoredAt (§5.0).
     * Without an [end] only an anchor makes it night, until anchor.nightMaxLength after it (when the night state ends
     * by itself).
     */
    fun isNight(now: Instant, bedtime: Instant?, end: Instant?, anchoredAt: Instant?, config: SleepConfig = SleepConfig.DEFAULT): Boolean {
        if (end == null) return anchoredAt != null && now < anchoredAt + config.anchor.nightMaxLength
        if (now >= end) return false
        if (anchoredAt != null) return true
        val usableBedtime = bedtime?.takeIf { Duration.between(it, end) <= config.week.nightBedtimeMaxLead }
        return usableBedtime != null && now >= usableBedtime
    }

    /**
     * The wake date a night-time moment belongs to: local time ≥ config.nightEndCutoff (14:00) → next date.
     * Example: 23:10 on Monday → Tuesday; 01:00 on Tuesday → Tuesday.
     */
    fun wakeDateOf(at: Instant, offset: ZoneOffset, config: SleepConfig = SleepConfig.DEFAULT): LocalDate {
        val local = at.atOffset(offset)
        return if (local.toLocalTime() >= config.nightEndCutoff) local.toLocalDate().plusDays(1) else local.toLocalDate()
    }
}
