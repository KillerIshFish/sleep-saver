package com.sleepsaver.core.domain.schedule

import com.sleepsaver.core.domain.config.SleepConfig
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

// ---------------------------------------------------------------- Enums

/** What the alarm does on a day: nothing, ring at the wake time, or ring at a restless moment in the window before it. */
enum class AlarmMode { OFF, FIXED, SURFACING }

/** Wake-up style (a cue profile; see AlarmConfig.cue). Example: QUIET_BED vibrates for 2 min before any sound. */
enum class WakeStyle { CALM, STANDARD, FIRM, QUIET_BED }

/** Alarm sounds. All but SYSTEM_ALARM are synthesized on the phone (no files, no downloads). */
enum class AlarmTone { SOFT_CHIMES, WARM_RISE, BRIGHT_BELLS, SYSTEM_ALARM }

/** Snooze lengths: TAPERING 9, 7, 5, 3, 2 min; FIXED a set length and count; NONE no snooze. */
enum class SnoozeStyle { TAPERING, FIXED, NONE }

/** The two halves of a day plan, for "Copy to…". */
enum class DayPart { MORNING, EVENING }

/** A one-off for one date: no alarm that day (DAY_OFF), or a different wake time and alarm (JUST_ONCE). */
enum class OverrideKind { DAY_OFF, JUST_ONCE }

/** Presets: "Work week + free weekend" and "Same every day". */
enum class WeekPreset { WORK_WEEK, SAME_EVERY_DAY }

/** Where a resolved day's wake time came from. */
enum class WakeSource { SET_TIME, JUST_ONCE, USUAL }

// ---------------------------------------------------------------- Day plan

/** How Snooze behaves. Example: TAPERING with overrun 10 min never snoozes past 6:40 for a 6:30 wake time. */
data class SnoozeSettings(
    val style: SnoozeStyle = SnoozeStyle.TAPERING,
    val fixedLength: Duration = SleepConfig.DEFAULT.alarm.fixedSnoozeDefault,
    val fixedCount: Int = SleepConfig.DEFAULT.alarm.fixedSnoozeCountDefault,
    /** No snooze ends later than the wake time + overrun. */
    val overrun: Duration = SleepConfig.DEFAULT.alarm.overrunDefault,
)

/** A day's alarm. Example: `AlarmSettings(mode = SURFACING, window = 30 min)` may ring from 6:00 for a 6:30 wake time. */
data class AlarmSettings(
    val mode: AlarmMode = AlarmMode.OFF,
    /** Surfacing window length (used when mode == SURFACING). */
    val window: Duration = SleepConfig.DEFAULT.alarm.defaultWindow,
    val style: WakeStyle = SleepConfig.DEFAULT.alarm.defaultWeekdayStyle,
    val tone: AlarmTone = SleepConfig.DEFAULT.alarm.defaultTone,
    /** 0.1..1.0 of the current alarm volume (player gain; the system volume is never changed). */
    val volume: Float = SleepConfig.DEFAULT.alarm.defaultVolume,
    val snooze: SnoozeSettings = SnoozeSettings(),
)

/** The morning of a day: its wake time and alarm. */
data class MorningPlan(
    /** Set wake time; null = usual (learned or Settings). Non-null whenever alarm.mode != OFF (the repository enforces it). */
    val wakeTime: LocalTime? = null,
    val alarm: AlarmSettings = AlarmSettings(),
)

/** The evening of a day ("Monday evening (before Tuesday)"): bedtime and Night steps. */
data class EveningPlan(
    /** Set bedtime; null = the Settings bedtime rule. A time before noon means after midnight. */
    val bedtime: LocalTime? = null,
    val nightStepsOn: Boolean = true,
    /** Wind-down phase length (30..150 min). Night steps run from the phase start to the bedtime. */
    val windDownLength: Duration = SleepConfig.DEFAULT.windDownLength,
    /** Routine activity ids for this evening, in order; null = the every-evening list (selected activities). */
    val routine: List<Long>? = null,
)

/** One weekday. The morning belongs to [day]'s wake date; the evening to [day]'s evening (the night before day + 1). */
data class DayPlan(val day: DayOfWeek, val morning: MorningPlan = MorningPlan(), val evening: EveningPlan = EveningPlan())

// ---------------------------------------------------------------- One-offs

/**
 * A change for one date only, stored as its own row; the week plan is never edited by it. Example: a JUST_ONCE for
 * Saturday at 05:30 with mode FIXED rings that Saturday only, even when Saturday normally has no alarm.
 */
data class DateOverride(
    val date: LocalDate,
    val kind: OverrideKind,
    /** JUST_ONCE: the wake time for this date (required). */
    val time: LocalTime? = null,
    /** JUST_ONCE: the alarm mode for this date (required; OFF = move the wake time without an alarm). */
    val mode: AlarmMode? = null,
) {
    init {
        if (kind == OverrideKind.JUST_ONCE) require(time != null && mode != null) { "just-once needs a time and a mode" }
    }
}

/** "Pause alarms until …": no alarms from [from] through [until] (inclusive). */
data class PlanPause(val from: LocalDate, val until: LocalDate) {
    init {
        require(!until.isBefore(from)) { "a pause can't end before it starts" }
    }

    fun covers(date: LocalDate): Boolean = !date.isBefore(from) && !date.isAfter(until)
}

/** Every one-off in force: per-date overrides (the last one for a date wins) and at most one pause. */
data class OneOffs(val overrides: List<DateOverride> = emptyList(), val pause: PlanPause? = null)

// ---------------------------------------------------------------- Week plan

/**
 * Exactly seven days, Monday first. Example: `WeekPlan.DEFAULT.day(SATURDAY).morning.alarm.style` is CALM.
 *
 * [resolve] applies the one-offs to a date:
 *  - override = the last override for the date; paused = the pause covers the date;
 *  - alarm mode: JUST_ONCE → its mode (an explicit one-off beats a pause); else paused or DAY_OFF → OFF; else the plan's;
 *  - wake time: JUST_ONCE → its time; else paused or DAY_OFF → null (usual: a day off sleeps to the usual time);
 *    else the plan's set time;
 *  - the evening always comes from the plan; one-offs never change evenings.
 */
data class WeekPlan(val days: List<DayPlan>) {
    init {
        require(days.map { it.day } == DayOfWeek.values().toList()) { "a week plan needs Monday..Sunday in order" }
    }

    fun day(day: DayOfWeek): DayPlan = days[day.value - 1]

    fun with(plan: DayPlan): WeekPlan = WeekPlan(days.map { if (it.day == plan.day) plan else it })

    /** Any day with an alarm (mode != OFF and a set time). */
    val hasAlarms: Boolean get() = days.any { it.morning.alarm.mode != AlarmMode.OFF && it.morning.wakeTime != null }

    /** [date]'s effective plan after [oneOffs] (rules in the class KDoc). */
    fun resolve(date: LocalDate, oneOffs: OneOffs = OneOffs()): ResolvedDay = ResolvedDay(
        date = date,
        plan = day(date.dayOfWeek),
        override = oneOffs.overrides.lastOrNull { it.date == date },
        paused = oneOffs.pause?.covers(date) == true,
    )

    /**
     * Copies [parts] of [from] onto [to] (EVENING includes bedtime, Night steps switch, length and routine).
     * Each part is replaced wholesale; [from] itself is never changed even when it is in [to].
     * Named copyParts so it never reads as the data class's generated copy(days).
     */
    fun copyParts(from: DayOfWeek, to: Set<DayOfWeek>, parts: Set<DayPart>): WeekPlan {
        val source = day(from)
        return WeekPlan(
            days.map { target ->
                if (target.day == from || target.day !in to) {
                    target
                } else {
                    target.copy(
                        morning = if (DayPart.MORNING in parts) source.morning else target.morning,
                        evening = if (DayPart.EVENING in parts) source.evening else target.evening,
                    )
                }
            },
        )
    }

    companion object {
        /** Every alarm off; style STANDARD Mon–Fri, CALM Sat–Sun (AlarmConfig defaults). */
        val DEFAULT: WeekPlan = WeekPlan(DayOfWeek.values().map { defaultDay(it) })

        /** A day with no set times and the alarm off, its defaults taken from [config]. */
        fun defaultDay(day: DayOfWeek, config: SleepConfig = SleepConfig.DEFAULT): DayPlan {
            val alarm = config.alarm
            val weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
            return DayPlan(
                day = day,
                morning = MorningPlan(
                    wakeTime = null,
                    alarm = AlarmSettings(
                        mode = AlarmMode.OFF,
                        window = alarm.defaultWindow,
                        style = if (weekend) alarm.defaultWeekendStyle else alarm.defaultWeekdayStyle,
                        tone = alarm.defaultTone,
                        volume = alarm.defaultVolume,
                        snooze = SnoozeSettings(
                            style = SnoozeStyle.TAPERING,
                            fixedLength = alarm.fixedSnoozeDefault,
                            fixedCount = alarm.fixedSnoozeCountDefault,
                            overrun = alarm.overrunDefault,
                        ),
                    ),
                ),
                evening = EveningPlan(windDownLength = config.windDownLength),
            )
        }
    }
}

/** A date's effective plan after one-offs (see [WeekPlan.resolve]). */
data class ResolvedDay(
    val date: LocalDate,
    val plan: DayPlan,
    val override: DateOverride?,
    val paused: Boolean,
) {
    val alarmMode: AlarmMode
        get() = when {
            isJustOnce -> override?.mode ?: AlarmMode.OFF
            paused || isDayOff -> AlarmMode.OFF
            else -> plan.morning.alarm.mode
        }

    val alarm: AlarmSettings get() = plan.morning.alarm.copy(mode = alarmMode)

    /** The date's own wake time, or null = usual. */
    val wakeTime: LocalTime?
        get() = when {
            isJustOnce -> override?.time
            paused || isDayOff -> null
            else -> plan.morning.wakeTime
        }

    val wakeSource: WakeSource
        get() = when {
            isJustOnce -> WakeSource.JUST_ONCE
            wakeTime != null -> WakeSource.SET_TIME
            else -> WakeSource.USUAL
        }

    /** The alarm rings on this date (a mode other than OFF and a time). */
    val alarmOn: Boolean get() = alarmMode != AlarmMode.OFF && wakeTime != null
    val isDayOff: Boolean get() = override?.kind == OverrideKind.DAY_OFF
    val isJustOnce: Boolean get() = override?.kind == OverrideKind.JUST_ONCE
    val evening: EveningPlan get() = plan.evening
}
