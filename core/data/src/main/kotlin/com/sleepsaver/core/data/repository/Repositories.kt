package com.sleepsaver.core.data.repository

import com.sleepsaver.core.domain.model.DailyInsights
import com.sleepsaver.core.domain.model.DebtChangeBreakdown
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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
    suspend fun setTypicalWake(time: LocalTime)
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

    /** Everything: raw sessions + stages, overrides, naps, added awake, vitals, habits, routine, settings. */
    suspend fun exportJson(out: OutputStream)
}

interface DataResetter {
    /** Deletes the database and settings, cancels sync work and reminders. */
    suspend fun resetAll()
}
