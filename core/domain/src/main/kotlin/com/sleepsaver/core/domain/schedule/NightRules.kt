package com.sleepsaver.core.domain.schedule

import com.sleepsaver.core.domain.alarm.AlarmEvent
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.EnergySchedule
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * One night as a screen knows it: its evening's target [bedtime] and how its morning starts, at [alarmWindowStart] (that
 * wake date's alarm, null when it has none) or else at [wakeZoneStart]. Null = unknown.
 */
data class NightBounds(val bedtime: Instant?, val alarmWindowStart: Instant?, val wakeZoneStart: Instant?) {
    /** Where its no-clock time ends ([NightRules.nightEnd]). */
    val end: Instant? get() = NightRules.nightEnd(alarmWindowStart, wakeZoneStart)
}

/**
 * The night in focus at a moment ([NightRules.focus]): [nightOf] is its evening, [wakeDate] the morning after.
 * [bedtime] and [end] bound its no-clock time (null when unknown); [isNight] says whether that time is now.
 */
data class FocusNight(val nightOf: LocalDate, val bedtime: Instant?, val end: Instant?, val isNight: Boolean) {
    val wakeDate: LocalDate get() = nightOf.plusDays(1)
}

/**
 * Night-time rules shared by Tonight, Sleep home, Energy, reminders and the alarm. Example: with bedtime 23:00 and a
 * 6:00 window start, 23:30 is night (no clock anywhere), 6:00 is not.
 */
object NightRules {

    /**
     * The one "is it night" decision (§5.0) behind Tonight, Sleep home and Energy, so they never disagree.
     *
     * - Last night (the evening before today in [zone]) stays in focus until it ends: at this morning's alarm window
     *   start, else its wake zone start ([NightBounds.end]), and as soon as this morning's planned alarm was stopped or
     *   timed out ([morningStop], see [morningStop]): an early surfacing ring that was stopped ends the night then.
     *   [eveningStillYesterday] keeps it in focus regardless (the schedule hasn't rolled over yet: night owls).
     * - Otherwise tonight (today's evening) is in focus, from its bedtime until tomorrow's alarm window or wake zone.
     * - [anchoredAt] (`NightController.state.night?.anchoredAt`) counts only for the night of its own wake date
     *   ([wakeDateOf]), so last night's anchor never darkens the day and an anchor never stretches another night.
     * - [endedAt] ("I'm up") tapped during the night in focus (same wake date, after its anchor, at most
     *   week.nightBedtimeMaxLead before its bedtime) ends that night's no-clock time until the next anchor.
     *
     * Example: bedtime 23:00, a 6:45 alarm with a 30-min window: 23:30 and 2:00 are night (of the evening before 6:45),
     * 6:15 is not; with the ring stopped at 6:05, 6:06 is not either.
     */
    fun focus(
        now: Instant,
        zone: ZoneId,
        lastNight: NightBounds,
        tonight: NightBounds,
        morningStop: Instant? = null,
        anchoredAt: Instant? = null,
        endedAt: Instant? = null,
        eveningStillYesterday: Boolean = false,
        config: SleepConfig = SleepConfig.DEFAULT,
    ): FocusNight {
        val today = now.atZone(zone).toLocalDate()
        val lastNightEnd = listOfNotNull(lastNight.end, morningStop).minOrNull()
        val isLastNight = eveningStillYesterday || (lastNightEnd != null && now < lastNightEnd)
        val nightOf = if (isLastNight) today.minusDays(1) else today
        val bounds = if (isLastNight) lastNight else tonight
        val end = if (isLastNight) lastNightEnd else bounds.end
        val wakeDate = nightOf.plusDays(1)

        fun wakeDateAt(at: Instant): LocalDate = wakeDateOf(at, zone.rules.getOffset(at), config)
        val anchor = anchoredAt?.takeIf { wakeDateAt(it) == wakeDate }
        val ended = endedAt != null && endedAt <= now && (anchor == null || anchor < endedAt) && wakeDateAt(endedAt) == wakeDate &&
            (bounds.bedtime == null || endedAt >= bounds.bedtime - config.week.nightBedtimeMaxLead)
        return FocusNight(nightOf, bounds.bedtime, end, isNight = !ended && isNight(now, bounds.bedtime, end, anchor, config))
    }

    /**
     * When [event], the planned alarm of [wakeDate], ended that night: its stop, or its first ring when it timed out or
     * was missed. Null while it hasn't ended (or for a catnap, a test ring, another morning).
     */
    fun morningStop(event: AlarmEvent?, wakeDate: LocalDate): Instant? = event
        ?.takeIf { it.kind == RingKind.PLANNED && it.wakeDate == wakeDate && it.outcome != null }
        ?.let { it.stoppedAt ?: it.firstRingAt }

    /**
     * [evening]'s bounds from the energy [schedule]: its own evening has `targetBedtime` and `tomorrowWakeZone`; the
     * evening before it has [alarmBedtime] (the bedtime that morning's alarm was planned with), else the schedule's
     * bedtime a day earlier, and `wakeZone`. Another evening knows only the alarm. [alarmWindowStart] = the alarm of the
     * morning after [evening] (null without one).
     */
    fun boundsFrom(schedule: EnergySchedule, evening: LocalDate, alarmWindowStart: Instant?, alarmBedtime: Instant? = null): NightBounds =
        when (evening) {
            schedule.date -> NightBounds(schedule.targetBedtime, alarmWindowStart, schedule.tomorrowWakeZone.start)
            schedule.date.minusDays(1) -> NightBounds(
                bedtime = alarmBedtime ?: schedule.targetBedtime.atZone(schedule.zone).minusDays(1).toInstant(),
                alarmWindowStart = alarmWindowStart,
                wakeZoneStart = schedule.wakeZone.start,
            )
            else -> NightBounds(alarmBedtime, alarmWindowStart, wakeZoneStart = null)
        }

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
