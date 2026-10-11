package com.sleepsaver.core.data.repository

import com.sleepsaver.core.domain.alarm.AlarmEvent
import com.sleepsaver.core.domain.alarm.AlarmOccurrence
import com.sleepsaver.core.domain.alarm.AlarmTuning
import com.sleepsaver.core.domain.alarm.DeepPrior
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.alarm.MovementEpoch
import com.sleepsaver.core.domain.alarm.SurfacingWindow
import com.sleepsaver.core.domain.alarm.WakeLanding
import com.sleepsaver.core.domain.journal.Anchor
import com.sleepsaver.core.domain.journal.AnchorSource
import com.sleepsaver.core.domain.journal.FeelSource
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.journal.MorningFeelEntry
import com.sleepsaver.core.domain.journal.PromptKind
import com.sleepsaver.core.domain.journal.TomorrowList
import com.sleepsaver.core.domain.model.BedtimeMode
import com.sleepsaver.core.domain.model.DailyInsights
import com.sleepsaver.core.domain.model.DebtChangeBreakdown
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.NightOverride
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.domain.model.SleepDebt
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.model.VitalSample
import com.sleepsaver.core.domain.model.WakeTimeMode
import com.sleepsaver.core.domain.nightsteps.NightStepsSession
import com.sleepsaver.core.domain.nightsteps.StepAdherence
import com.sleepsaver.core.domain.nightsteps.StepSuggestion
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.domain.patterns.AnchorNightStats
import com.sleepsaver.core.domain.patterns.KeelResult
import com.sleepsaver.core.domain.patterns.MarkEffect
import com.sleepsaver.core.domain.patterns.OddNight
import com.sleepsaver.core.domain.patterns.PersonalLatency
import com.sleepsaver.core.domain.patterns.PlanCheck
import com.sleepsaver.core.domain.patterns.SocialJetlag
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.DayPart
import com.sleepsaver.core.domain.schedule.DayPlan
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.PlanPause
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.domain.schedule.WeekPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.OutputStream
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

// ---------------------------------------------------------------- Sleep data

interface SleepRepository {
    /**
     * Every assembled night for the active source (demo origin in demo mode, otherwise the selected
     * Health Connect origin / all origins), with the user's edits applied. Ascending by wakeDate.
     */
    val nights: Flow<List<Night>>

    fun night(wakeDate: LocalDate): Flow<Night?>

    /** The same night with no edits applied (what the data source said). Used for "Revert" and data-source info. */
    fun rawNight(wakeDate: LocalDate): Flow<Night?>

    /** Packages that have written sleep sessions to Health Connect (demo excluded). */
    val origins: Flow<List<String>>

    fun vitals(from: Instant, to: Instant): Flow<List<VitalSample>>

    suspend fun saveOverride(override: NightOverride)

    /** Deletes the override and added-awake rows for [wakeDate]: the night goes back to raw data. */
    suspend fun revert(wakeDate: LocalDate)

    /** Adds a night that has no raw data (an override with isManualNight = true). */
    suspend fun addManualNight(wakeDate: LocalDate, fellAsleep: Instant, wokeUp: Instant, offset: ZoneOffset)

    suspend fun addNap(start: Instant, end: Instant, offset: ZoneOffset): Long

    /** Manual nap blocks carry sessionIds = ["manual-nap:<id>"]. */
    suspend fun deleteNap(id: Long)

    suspend fun addAwake(wakeDate: LocalDate, start: Instant, end: Instant): Long

    suspend fun deleteAwake(id: Long)
}

// ---------------------------------------------------------------- Insights

sealed interface InsightsState {
    data object Loading : InsightsState

    /** No present nights at all for the active source. The UI shows a fix-it empty state. */
    data object NoNights : InsightsState

    data class Ready(val insights: DailyInsights) : InsightsState
}

interface InsightsRepository {
    /** Recomputed when nights, settings or habits change, and once a minute (for "now"). */
    val state: StateFlow<InsightsState>

    /** debt(d) for each d in [from, to]. */
    fun debtSeries(from: LocalDate, to: LocalDate): Flow<List<SleepDebt>>

    /**
     * The energy schedule for [date]: today, a past day (the timeline), or tomorrow (the reminder planner), which is
     * planned from what is known today.
     */
    fun schedule(date: LocalDate): Flow<EnergySchedule?>

    suspend fun breakdown(wakeDate: LocalDate): DebtChangeBreakdown
}

// ---------------------------------------------------------------- Settings

enum class ClockFormat { SYSTEM, H12, H24 }

data class AppSettings(
    val sleep: UserSleepSettings = UserSleepSettings(),
    val demoMode: Boolean = false,
    val demoStartedAt: Instant? = null,
    val clockFormat: ClockFormat = ClockFormat.SYSTEM,
    val morningReviewEnabled: Boolean = true,
    val onboardingDone: Boolean = false,
    val needSuggestionDismissedValue: Duration? = null,
    val needSuggestionDismissedAt: Instant? = null,
)

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun setSleepNeed(need: Duration)
    /** The user's own wake time: used when the wake-time mode is FIXED, and before there's data to learn from. */
    suspend fun setTypicalWake(time: LocalTime)
    suspend fun setWakeTimeMode(mode: WakeTimeMode)

    /** The user's own bedtime: used when the bedtime mode is FIXED, and before there's data to learn from. */
    suspend fun setBedtime(time: LocalTime)
    suspend fun setBedtimeMode(mode: BedtimeMode)
    suspend fun setStreakThreshold(threshold: Duration)
    suspend fun setDebtThresholds(lowMax: Duration, highMin: Duration)
    suspend fun setSource(source: SourceSelection)
    /** Flag only. Use [DemoModeController] to actually switch (it also loads/clears the demo data). */
    suspend fun setDemoModeFlag(enabled: Boolean, startedAt: Instant?)
    suspend fun setClockFormat(format: ClockFormat)
    suspend fun setMorningReviewEnabled(enabled: Boolean)
    suspend fun setOnboardingDone(done: Boolean)
    suspend fun dismissNeedSuggestion(value: Duration, at: Instant)
}

// ---------------------------------------------------------------- Sync

enum class SyncTrigger { APP_OPEN, MANUAL, PERIODIC, RETRY, ONBOARDING }

enum class SyncError {
    HEALTH_CONNECT_NOT_INSTALLED,
    HEALTH_CONNECT_UPDATE_REQUIRED,
    PERMISSION_DENIED,
    RATE_LIMITED,
    /** Background read attempted without READ_HEALTH_DATA_IN_BACKGROUND. */
    BACKGROUND_NOT_ALLOWED,
    UNKNOWN,
}

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus

    /** Initial history import; progress 0..1 when known. */
    data class Importing(val progress: Float?) : SyncStatus
}

data class SyncState(
    val status: SyncStatus = SyncStatus.Idle,
    val lastSuccessAt: Instant? = null,
    val lastError: SyncError? = null,
)

data class SyncResult(
    val success: Boolean,
    val sessionsChanged: Int = 0,
    val error: SyncError? = null,
    /** True when this sync brought in the night ending this morning for the first time. */
    val lastNightArrived: Boolean = false,
)

interface SyncRepository {
    val state: StateFlow<SyncState>

    /** Incremental sync (Changes API). No-op in demo mode. Never throws. */
    suspend fun sync(trigger: SyncTrigger): SyncResult

    /**
     * Full history import (onboarding step 3, or a re-import from settings). Runs the same follow-up as [sync]
     * (morning notification, fallback reminder, waiting retries). Never throws.
     */
    suspend fun importHistory(): SyncResult

    /** Enqueue/refresh the periodic WorkManager job (it no-ops without background permission). */
    fun ensurePeriodicSync()

    /** Start the retry chain while last night is missing (15/30/60 min… until SleepConfig.waitingGiveUpAt). */
    fun startWaitingRetries()

    /** Forget the changes token and sync timestamps (used by "Reset all data"). */
    suspend fun resetSyncState()
}

/** Switches demo mode: loads 60 synthetic nights, or clears them and any edits made while in demo mode. */
interface DemoModeController {
    suspend fun setDemoMode(enabled: Boolean)
}

// ---------------------------------------------------------------- Habits

interface HabitsRepository {
    /** One entry per HabitType (defaults seeded on first read). */
    val habitSettings: Flow<List<HabitSettings>>
    val routineActivities: Flow<List<RoutineActivity>>
    suspend fun setEnabled(type: HabitType, enabled: Boolean)
    suspend fun setReminder(type: HabitType, enabled: Boolean)
    suspend fun setOffset(type: HabitType, offset: Duration)
    suspend fun resetOffset(type: HabitType)
    suspend fun setActivitySelected(id: Long, selected: Boolean)
    suspend fun addCustomActivity(label: String, emoji: String): Long
    suspend fun deleteActivity(id: Long)
}

// ---------------------------------------------------------------- Morning review

enum class ReviewStatus { CONFIRMED, ADJUSTED, DISMISSED }

data class MorningReview(
    val wakeDate: LocalDate,
    val status: ReviewStatus,
    val at: Instant,
    val debtBefore: Duration?,
    val debtAfter: Duration?,
)

interface ReviewRepository {
    fun review(wakeDate: LocalDate): Flow<MorningReview?>
    suspend fun record(review: MorningReview)

    /** Today's wake date when a review should be offered (enabled, last night present, not yet reviewed), else null. */
    val pendingReview: Flow<LocalDate?>
}

// ---------------------------------------------------------------- Export / reset

interface ExportRepository {
    /** One row per night: wake date, fell asleep, woke up, totals, stages, naps, source, edited. */
    suspend fun exportCsv(out: OutputStream)

    /**
     * Everything: raw sessions + stages, overrides, naps, added awake, vitals, habits, routine, settings, and (schema 2)
     * the week plan, one-offs and pause, Night steps logs, Tomorrow lists, marks, Morning feel, anchors, movement logs
     * (without epochs), alarm events, the alarm tuning and Hush plays.
     */
    suspend fun exportJson(out: OutputStream)
}

interface DataResetter {
    /** Stops the night services and alarms first, then deletes the database and settings and cancels sync work and reminders. */
    suspend fun resetAll()
}

// ---------------------------------------------------------------- Week plan

/** A planned alarm with tonight's effective window. */
data class PlannedAlarm(
    val occurrence: AlarmOccurrence,
    val window: SurfacingWindow,
    /** B on the evening before (plan, Settings rule or learned); null when it can't be computed. */
    val plannedBedtime: Instant?,
    val debtStatus: DebtStatus?,
    /** The latency [window] and [plannedBedtime] were computed with (personal latency, else config.sleepLatency). */
    val latency: Duration,
    /** The sleep need used for this plan (settings), for expectedOnset's target − need fallback. */
    val need: Duration,
)

/**
 * Everything the alarm scheduler arms from. [following] = up to alarm.followingMirrored (2) alarms after [next]
 * (for the locked-boot mirror).
 */
data class AlarmPlan(val next: PlannedAlarm?, val following: List<PlannedAlarm>, val plannedIds: Set<String>, val computedAt: Instant)

interface ScheduleRepository {
    /** Always seven days, Monday first; seeded with WeekPlan.DEFAULT on first read. Falls back to DEFAULT only for the UI. */
    val weekPlan: Flow<WeekPlan>

    /** Overrides with date ≥ today − overrideHistoryDays, and the pause (expired pauses are dropped). */
    val oneOffs: Flow<OneOffs>

    /**
     * Usual wake (learned or Settings, ignoring the plan's own times) for the next date of each weekday
     * (tomorrow .. today + 7). Never fails: falls back to Settings' wake time.
     */
    val usualWake: Flow<Map<DayOfWeek, LocalTime>>

    /**
     * For the UI: the next week.upcomingLimit (7) planned alarms (excluding handled ones). Recomputed when the plan,
     * one-offs, settings, nights, anchors or the local date change; minute ticks only drop alarms whose target passed.
     */
    val upcomingAlarms: Flow<List<PlannedAlarm>>
    val nextAlarm: Flow<PlannedAlarm?>

    /**
     * For the alarm scheduler only: computed now from storage, no fallbacks. Throws when the plan, one-offs,
     * settings or nights can't be read. [exclude] = handled + in-progress occurrence ids from the mirror.
     */
    suspend fun computeAlarmPlan(now: Instant, exclude: Set<String>): AlarmPlan

    /**
     * Atomic read-modify-write of one day (Mutex + transaction). Never touches routine items. Normalises: alarm on and
     * no wake time → current usual wake rounded to timeRounding; clamps every range.
     */
    suspend fun updateDay(day: DayOfWeek, change: (DayPlan) -> DayPlan)

    /** null = the every-evening list (ownRoutine = false); else replaces the day's items (unknown ids dropped). */
    suspend fun setDayRoutine(day: DayOfWeek, activityIds: List<Long>?)
    suspend fun copyDay(from: DayOfWeek, to: Set<DayOfWeek>, parts: Set<DayPart>)
    suspend fun applyPreset(preset: WeekPreset, wake: LocalTime)

    /** Replaces the override for its date; prunes rows older than today − overridePruneDays. */
    suspend fun setOverride(override: DateOverride)
    suspend fun clearOverride(date: LocalDate)

    /** null = resume. require(until ≤ today + pauseMaxDays). */
    suspend fun setPause(pause: PlanPause?)
}

// ---------------------------------------------------------------- Night steps

interface NightStepsRepository {
    /**
     * Tonight's steps: the schedule day's evening (DailyInsights.schedule.date). Without nights (first run, no Health
     * Connect) it is timed from the evening plan's bedtime or Settings' bedtime (fromSettingsOnly = true).
     */
    val tonight: Flow<NightStepsSession?>
    fun session(nightOf: LocalDate): Flow<NightStepsSession?>

    /** All routine activities (selected = every-evening list), with technique, lead and duration. */
    val activities: Flow<List<RoutineActivity>>
    val adherence: Flow<StepAdherence>
    val suggestions: Flow<List<StepSuggestion>>
    suspend fun setDone(nightOf: LocalDate, activityId: Long, done: Boolean)

    /**
     * Adds the technique as a selected activity (emoji = technique.emoji, lead/duration = config.steps.timing(technique)),
     * or returns the existing activity with that technique (and selects it). [label] = the caller's localized title,
     * resolved in composition (never call a @Composable title() from a click lambda).
     */
    suspend fun addTechnique(technique: Technique, label: String): Long
    suspend fun addCustom(label: String, emoji: String, lead: Duration, duration: Duration): Long
    suspend fun setTiming(activityId: Long, lead: Duration, duration: Duration)

    /** The every-evening list (routine_activities.selected). */
    suspend fun setIncluded(activityId: Long, included: Boolean)

    /** Custom activities only (require). */
    suspend fun deleteCustom(activityId: Long)
}

// ---------------------------------------------------------------- Journal (marks, Morning feel, Tomorrow list)

interface JournalRepository {
    fun marks(wakeDate: LocalDate): Flow<Set<Mark>>
    val marksByDate: Flow<Map<LocalDate, Set<Mark>>>

    /** Stored marks only (require(!mark.derived)); require(markEditable(wakeDate, today)). */
    suspend fun setMark(wakeDate: LocalDate, mark: Mark, on: Boolean)
    fun feel(wakeDate: LocalDate): Flow<MorningFeelEntry?>
    val feels: Flow<Map<LocalDate, MorningFeelEntry>>

    /** null clears. [at]/[offset] default to now (a buffered ring-screen tap passes its own). */
    suspend fun setFeel(wakeDate: LocalDate, feel: MorningFeel?, source: FeelSource, at: Instant? = null, offset: ZoneOffset? = null)
    fun tomorrowList(nightOf: LocalDate): Flow<TomorrowList?>

    /** Blank deletes. */
    suspend fun setTomorrowList(nightOf: LocalDate, text: String)
    fun promptShown(wakeDate: LocalDate, kind: PromptKind): Flow<Boolean>
    suspend fun markPromptShown(wakeDate: LocalDate, kind: PromptKind)
}

// ---------------------------------------------------------------- Night log (anchors, movement, alarm history, tuning, Hush)

/** One movement log with its stored (non-missing) epochs, oldest first. [mode] = WHOLE_NIGHT | WINDOW. */
data class MovementTrace(val logId: Long, val mode: String, val start: Instant, val end: Instant?, val epochs: List<MovementEpoch>)

interface NightLogRepository {
    /**
     * The latest anchor whose wakeDate is the next wake date (null when none, or the night state ended). For alarm
     * math only (expectedOnset, the debt-aware window); night mode in the UI reads NightController.state (§5.0).
     */
    val activeAnchor: Flow<Anchor?>
    fun anchors(wakeDate: LocalDate): Flow<List<Anchor>>
    val allAnchors: Flow<List<Anchor>>
    suspend fun addAnchor(at: Instant, offset: ZoneOffset, source: AnchorSource, wholeNightLog: Boolean): Long
    suspend fun deleteAnchor(id: Long)
    suspend fun startMovementLog(
        start: Instant,
        offset: ZoneOffset,
        mode: String,
        wakeDate: LocalDate,
        anchorId: Long?,
        occurrenceId: String?,
    ): Long

    /** Missing epochs are skipped. */
    suspend fun addEpochs(logId: Long, epochs: List<MovementEpoch>)
    suspend fun endMovementLog(logId: Long, end: Instant)
    fun movement(wakeDate: LocalDate): Flow<List<MovementTrace>>

    /** Insert (id == 0) or update; returns the row id: the new id on insert, [event].id on update (never Room's −1). */
    suspend fun recordAlarmEvent(event: AlarmEvent): Long

    /** The latest PLANNED event for [wakeDate] (never a catnap or test). */
    fun alarmEvent(wakeDate: LocalDate): Flow<AlarmEvent?>

    /** The newest alarm.recentEventsLimit (30) events. */
    val recentAlarmEvents: Flow<List<AlarmEvent>>
    val lastTestRing: Flow<AlarmEvent?>

    /** Occurrence ids of PLANNED events with an outcome, wakeDate ≥ [since]. */
    suspend fun handledOccurrenceIds(since: LocalDate): Set<String>

    /** Wake landing for [wakeDate]'s planned event (null without an event; hasStages false without stages). */
    fun landing(wakeDate: LocalDate): Flow<WakeLanding?>
    val tuning: Flow<AlarmTuning?>

    /** Runs ZTuner over the latest landings and stores a new tuning row if a step is due. Idempotent. */
    suspend fun refreshTuning()
    suspend fun resetTuning()
    suspend fun startHushPlay(start: Instant, offset: ZoneOffset, color: HushColor): Long
    suspend fun endHushPlay(id: Long, end: Instant)
}

// ---------------------------------------------------------------- Patterns

/** Inputs for DebtProjector (computed per scrub step in the ViewModel). [settings] has the plan and personal latency merged. */
data class DebtPreviewInputs(val nights: List<Night>, val settings: UserSleepSettings, val zone: ZoneId)

interface PatternsRepository {
    val keel: Flow<KeelResult>
    val socialJetlag: Flow<SocialJetlag?>

    /** Planned keel + warnings for the current plan (null while loading / without inputs). */
    val planCheck: Flow<PlanCheck?>

    /** All marks × metrics (derived marks included). */
    val markEffects: Flow<List<MarkEffect>>
    fun oddNight(wakeDate: LocalDate): Flow<OddNight?>
    fun anchorStats(wakeDate: LocalDate): Flow<AnchorNightStats?>
    val personalLatency: Flow<PersonalLatency>

    /** null in demo mode or with fewer than priorMinNights stage nights. */
    val deepPrior: Flow<DeepPrior?>
    val debtPreviewInputs: Flow<DebtPreviewInputs?>
}
