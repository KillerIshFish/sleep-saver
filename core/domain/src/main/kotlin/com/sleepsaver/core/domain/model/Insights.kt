package com.sleepsaver.core.domain.model

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.WeekPlan
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// ---------------------------------------------------------------- Settings

/** The user-editable settings the engine needs. Defaults match SleepConfig. */
data class UserSleepSettings(
    val sleepNeed: Duration = Duration.ofHours(8),
    /** The user's own wake time: used when [wakeTimeMode] is FIXED, and until there are enough nights to learn it. */
    val typicalWake: LocalTime = LocalTime.of(7, 0),
    val wakeTimeMode: WakeTimeMode = WakeTimeMode.FROM_DATA,
    /** The user's own bedtime: used when [bedtimeMode] is FIXED, and until there are enough nights to learn it. */
    val bedtime: LocalTime = LocalTime.of(23, 0),
    val bedtimeMode: BedtimeMode = BedtimeMode.FROM_DATA,
    val streakThreshold: Duration = Duration.ofHours(5),
    val lowDebtMax: Duration = Duration.ofHours(2),
    val highDebtMin: Duration = Duration.ofHours(5),
    val source: SourceSelection = SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE),
    /**
     * The week plan, its one-offs and the personal latency (Drop anchor). Filled by InsightsRepositoryImpl /
     * ScheduleRepositoryImpl from the week-plan tables and anchors; SettingsRepository leaves the defaults.
     */
    val week: WeekPlan = WeekPlan.DEFAULT,
    val oneOffs: OneOffs = OneOffs(),
    /** The personal latency; null = `SleepConfig.sleepLatency`. */
    val personalLatency: Duration? = null,
) {
    val timing: SleepTiming
        get() = SleepTiming(wakeTimeMode, typicalWake, bedtimeMode, bedtime, week, oneOffs, personalLatency)
}

/** Where the schedule's wake time comes from. */
enum class WakeTimeMode {
    /** Learned from the wake times in recent sleep data (weekdays and weekends apart). */
    FROM_DATA,

    /** Always the user's own wake time. */
    FIXED,
}

/** Where tonight's target bedtime comes from. */
enum class BedtimeMode {
    /** When the user usually falls asleep, learned from recent sleep data (weekday and weekend nights apart). */
    FROM_DATA,

    /** Tomorrow's wake time minus the sleep need and the time it takes to fall asleep. */
    SLEEP_NEED,

    /** Always the user's own bedtime. */
    FIXED,
}

/**
 * The user's choices for the schedule's two anchors. [wakeTime] and [bedtime] are their own times: used when the
 * mode is FIXED, and as the fallback while there aren't enough nights to learn from. A day's set times in [week]
 * (and its one-offs) win over both (see ScheduleTimes): with Tuesday's morning set to 05:30, Tuesday's W is 05:30
 * whatever the mode.
 */
data class SleepTiming(
    val wakeTimeMode: WakeTimeMode = WakeTimeMode.FROM_DATA,
    val wakeTime: LocalTime = LocalTime.of(7, 0),
    val bedtimeMode: BedtimeMode = BedtimeMode.FROM_DATA,
    val bedtime: LocalTime = LocalTime.of(23, 0),
    /** Per-day set wake times, bedtimes, wind-down lengths and Night steps switches. */
    val week: WeekPlan = WeekPlan.DEFAULT,
    /** Day off, Just this once and Pause alarms rows. */
    val oneOffs: OneOffs = OneOffs(),
    /** The personal latency (time to fall asleep after Drop anchor); null = `SleepConfig.sleepLatency`. */
    val latency: Duration? = null,
)

/** Where a schedule's wake time or bedtime actually came from. */
enum class TimingSource {
    /** Learned from sleep data. */
    LEARNED,

    /** Wake time minus sleep need (bedtime only). */
    SLEEP_NEED,

    /** The user's fixed time. */
    FIXED,

    /** Learning was asked for but there aren't enough nights yet: the user's own time stands in. */
    NOT_ENOUGH_DATA,

    /** The week plan's set time or a one-off ("Just this once"). */
    WEEK_PLAN,
}

/**
 * Wake and fall-asleep times learned from recent nights, shown next to the settings. A value is null when there
 * aren't enough nights to learn it.
 */
data class LearnedSleepTiming(
    val weekdayWake: LocalTime? = null,
    val weekendWake: LocalTime? = null,
    /** Nights before a weekday (Sunday–Thursday nights). */
    val weekdayFellAsleep: LocalTime? = null,
    /** Nights before a weekend day (Friday and Saturday nights). */
    val weekendFellAsleep: LocalTime? = null,
)

// ---------------------------------------------------------------- Debt

data class DebtContribution(
    val wakeDate: LocalDate,
    /** 1 = last night. */
    val index: Int,
    val weight: Double,
    /** null when the night is missing. */
    val totalSleep: Duration?,
    /** need − total, floored at −maxSurplusCredit. null when missing. */
    val delta: Duration?,
    /** weight · delta. null when missing. */
    val weighted: Duration?,
)

data class SleepDebt(
    val asOf: LocalDate,
    /** max(0, Σ w·delta). */
    val debt: Duration,
    /** Σ w·delta before clamping at zero (negative = banked sleep). */
    val rawWeightedSum: Duration,
    val contributions: List<DebtContribution>,
    val missingNights: List<LocalDate>,
) {
    val hours: Double get() = debt.toMinutes() / 60.0
}

enum class DebtStatus { LOW, MODERATE, HIGH }

data class Streak(
    /** Consecutive days with debt under the threshold. */
    val days: Int,
    /** First day of the current streak, null when days == 0. */
    val since: LocalDate?,
)

/** For the morning-review recalculation: how last night moved the debt. */
data class DebtChangeBreakdown(
    val wakeDate: LocalDate,
    val need: Duration,
    val slept: Duration,
    /** debt(D−1) */
    val before: Duration,
    /** need − slept, capped like the debt formula (the i=1 term, weight 1). */
    val lastNightDelta: Duration,
    /** after − before − lastNightDelta: older nights weigh less every day. Usually ≤ 0. */
    val fade: Duration,
    /** debt(D) */
    val after: Duration,
)

// ---------------------------------------------------------------- Sleep need

enum class Confidence { LOW, MEDIUM, HIGH }

data class SleepNeedEstimate(
    val need: Duration,
    /** Nights woken at least [freeNightWakeDelay] after [typicalWeekdayWake] (less any first recovery night). */
    val freeNightsUsed: Int,
    val interquartileRange: Duration,
    val confidence: Confidence,
    val typicalWeekdayWake: LocalTime,
    /**
     * How much later than [typicalWeekdayWake] a wake had to be for its night to count as free
     * (`SleepConfig.freeNightWakeDelay`), so copy can say what "free" meant: "you woke at least 45 min later than on a
     * usual weekday". Nothing here knows whether an alarm rang.
     */
    val freeNightWakeDelay: Duration = SleepConfig.DEFAULT.freeNightWakeDelay,
)

sealed interface SleepNeedEstimateResult {
    data class Available(val estimate: SleepNeedEstimate) : SleepNeedEstimateResult
    data class NotEnoughData(
        val nightsSoFar: Int,
        val nightsRequired: Int,
        val freeNightsSoFar: Int,
        val freeNightsRequired: Int,
    ) : SleepNeedEstimateResult
}

// ---------------------------------------------------------------- Circadian / energy

data class CircadianAnchor(
    /** Weighted circular mean of main-sleep midpoints, minutes after local midnight [0, 1440). */
    val midpointMinuteOfDay: Double,
    /** W, the day's typical wake time: learned from recent wake times, or the user's own (see [wakeSource]). */
    val typicalWake: LocalTime,
    val nightsUsed: Int,
    /** True when W had to fall back to the user's own wake time (not enough nights yet). */
    val isFallback: Boolean,
    val wakeSource: TimingSource = TimingSource.LEARNED,
    val bedtimeSource: TimingSource = TimingSource.SLEEP_NEED,
)

enum class PhaseType {
    GROGGINESS,
    MORNING_PEAK,
    AFTERNOON_DIP,
    EVENING_PEAK,
    WIND_DOWN,
    MELATONIN_WINDOW,
}

data class Phase(
    val type: PhaseType,
    val start: Instant,
    val end: Instant,
    /** Start-time shift vs yesterday's schedule; negative = earlier. null = no yesterday to compare. */
    val shiftVsYesterday: Duration? = null,
) {
    fun contains(t: Instant): Boolean = !t.isBefore(start) && t.isBefore(end)
}

data class TimeSpan(val start: Instant, val end: Instant) {
    val duration: Duration get() = Duration.between(start, end)
    fun contains(t: Instant): Boolean = !t.isBefore(start) && t.isBefore(end)
}

data class EnergyPoint(val time: Instant, val value: Float)

/** Normalised 0..1 energy, from actual wake to target bedtime, at SleepConfig.curveStep. */
data class EnergyCurve(val points: List<EnergyPoint>) {
    fun valueAt(t: Instant): Float? {
        if (points.isEmpty() || t.isBefore(points.first().time) || t.isAfter(points.last().time)) return null
        val i = points.indexOfFirst { !it.time.isBefore(t) }
        if (i <= 0) return points.first().value
        val a = points[i - 1]
        val b = points[i]
        val span = Duration.between(a.time, b.time).toMillis().toFloat()
        val f = if (span == 0f) 0f else Duration.between(a.time, t).toMillis() / span
        return a.value + (b.value - a.value) * f
    }
}

data class EnergySchedule(
    /**
     * The day this schedule is for (a wake date). In [DailyInsights] it is today, or yesterday while a night owl's
     * evening (yesterday's wind-down / melatonin window) runs past midnight.
     */
    val date: LocalDate,
    val zone: ZoneId,
    /** W: typical wake for [date]. */
    val typicalWake: ZonedDateTime,
    /** Wa: when the user actually woke today, if last night is in. */
    val actualWake: Instant?,
    val wakeZone: TimeSpan,
    val tomorrowWakeZone: TimeSpan,
    /** B: tonight's target bedtime. */
    val targetBedtime: Instant,
    /** B of the evening before [date]: last night's target bedtime, by that evening's own plan. */
    val lastNightBedtime: Instant,
    /** Ordered by start: grogginess, morning peak, afternoon dip, evening peak, wind-down, melatonin window. */
    val phases: List<Phase>,
    val curve: EnergyCurve,
    val anchor: CircadianAnchor,
    /** The evening plan's Night steps switch for [date]'s evening. */
    val nightStepsOn: Boolean = true,
) {
    fun phase(type: PhaseType): Phase? = phases.firstOrNull { it.type == type }
    fun currentPhase(now: Instant): Phase? = phases.lastOrNull { it.contains(now) }
    fun upcomingPhases(now: Instant): List<Phase> = phases.filter { it.start.isAfter(now) }
}

// ---------------------------------------------------------------- Habits

enum class HabitType {
    MORNING_LIGHT,
    CAFFEINE_CUTOFF,
    LARGE_MEAL_CUTOFF,
    ALCOHOL_CUTOFF,
    EXERCISE_CUTOFF,
    DIM_LIGHTS,
    EVENING_ROUTINE,
    BEDTIME,
    NAP_WINDOW,
}

/** What a habit's time is measured from. */
enum class HabitAnchor { TYPICAL_WAKE, TARGET_BEDTIME, WIND_DOWN_START, MELATONIN_START, AFTERNOON_DIP_START }

/** Notification channel groups. */
enum class ReminderCategory { MORNING_LIGHT, CUTOFFS, WIND_DOWN, BEDTIME, NAPS }

data class HabitDefinition(
    val type: HabitType,
    val anchor: HabitAnchor,
    /** Signed offset from the anchor (negative = before). */
    val defaultOffset: Duration,
    /** How long the habit's window lasts after its time, if it has one. */
    val window: Duration?,
    val defaultEnabled: Boolean,
    val defaultReminder: Boolean,
    val category: ReminderCategory,
)

/** The user's per-habit settings (stored in Room). */
data class HabitSettings(
    val type: HabitType,
    val enabled: Boolean,
    val reminderEnabled: Boolean,
    val offset: Duration,
)

data class ScheduledHabit(
    val type: HabitType,
    val time: Instant,
    val windowEnd: Instant?,
    val enabled: Boolean,
    val reminderEnabled: Boolean,
    val category: ReminderCategory,
)

/**
 * A wind-down activity (a Night step). [selected] = on the every-evening list. Placed [lead] before the target
 * bedtime and [duration] long: Slow tide with lead 30 min and duration 10 min runs 22:30–22:40 for a 23:00 bedtime.
 */
data class RoutineActivity(
    val id: Long,
    val label: String,
    val emoji: String,
    val isCustom: Boolean,
    val selected: Boolean,
    /** The technique this activity is (null for custom activities and unknown stored names). */
    val technique: Technique? = null,
    val lead: Duration = SleepConfig.DEFAULT.steps.customLead,
    val duration: Duration = SleepConfig.DEFAULT.steps.customDuration,
)

// ---------------------------------------------------------------- Home screen roll-up

enum class LastNightState {
    /** Last night's data is in. */
    AVAILABLE,

    /** Before the expected wake time; nothing to wait for yet. */
    NOT_EXPECTED_YET,

    /** Expected but not synced from Garmin yet. Show the waiting state + "Sync now". */
    WAITING,

    /** Still missing after SleepConfig.waitingGiveUpAt: offer manual entry. */
    MISSING,
}

data class DailyInsights(
    val today: LocalDate,
    val now: Instant,
    val zone: ZoneId,
    val lastNight: Night?,
    val lastNightState: LastNightState,
    /** debt(today) when last night is in, otherwise debt(yesterday). */
    val debt: SleepDebt,
    /** debt − previous day's debt; null without a previous day. */
    val debtChange: Duration?,
    val status: DebtStatus,
    val streak: Streak,
    val energyPotential: Int,
    /**
     * Today's schedule, except between midnight and the end of yesterday's melatonin window (night owls), when it is
     * yesterday's: tonight's wind-down and bedtime are still ahead. [currentPhase], [upcomingPhases], [habits] and
     * [nextHabit] come from the same schedule. Compare `schedule.date` with [today] to tell.
     */
    val schedule: EnergySchedule,
    val currentPhase: Phase?,
    val upcomingPhases: List<Phase>,
    val habits: List<ScheduledHabit>,
    val nextHabit: ScheduledHabit?,
    /** Missing nights inside the 14-night debt window, never before the user's first night with data. */
    val missingNights: List<LocalDate>,
    val needEstimate: SleepNeedEstimateResult,
    /** Wake and fall-asleep times learned from recent nights (for the settings). */
    val learnedTiming: LearnedSleepTiming = LearnedSleepTiming(),
)

// ---------------------------------------------------------------- Vitals (optional data)

enum class VitalType { RESTING_HR, HRV_RMSSD, SLEEP_HR_AVG, STEPS_DAY }

data class VitalSample(
    val id: String,
    val type: VitalType,
    val time: Instant,
    val value: Double,
    val origin: String,
)
