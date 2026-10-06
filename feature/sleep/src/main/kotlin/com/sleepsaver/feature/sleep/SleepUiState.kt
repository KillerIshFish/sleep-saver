package com.sleepsaver.feature.sleep

import androidx.compose.runtime.Immutable
import com.sleepsaver.core.data.healthconnect.HealthConnectStatus
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.data.repository.SyncStatus
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Confidence
import com.sleepsaver.core.domain.model.DailyInsights
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.LastNightState
import com.sleepsaver.core.domain.model.Phase
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.domain.model.SleepNeedEstimateResult
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.need.SleepNeedEstimator
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Everything the home screen shows. */
sealed interface SleepUiState {
    data object Loading : SleepUiState

    /** No nights at all for the active source: a fix-it screen chosen by [reason]. */
    data class Empty(
        val today: LocalDate,
        val reason: EmptyReason,
        val isSyncing: Boolean = false,
        val isRefreshing: Boolean = false,
        /** Garmin is the source: show the watch → Garmin Connect → Health Connect steps, and open it on "Sync now". */
        val syncOpensGarmin: Boolean = true,
        /** False in demo mode (the demo nights are what's missing, so offering them again makes no sense). */
        val canTryDemo: Boolean = true,
    ) : SleepUiState

    @Immutable
    data class Ready(
        val today: LocalDate,
        /** The insights' "now" (refreshed every minute). */
        val now: Instant,
        val zone: ZoneId,
        val banner: LastNightBanner?,
        val debt: Duration,
        val status: DebtStatus,
        /** Last night isn't in yet, so [debt] is yesterday's. */
        val debtIsFromYesterday: Boolean,
        val debtChange: Duration?,
        val streakDays: Int,
        /** Last night's main sleep; null until last night is in. */
        val lastNightSleep: Duration?,
        val source: SourceLabel,
        val lastSyncAt: Instant?,
        val lastSyncError: SyncError?,
        val isSyncing: Boolean,
        val isRefreshing: Boolean,
        /** "Sync now" opens Garmin Connect first (so the watch syncs), then syncs when the screen resumes. */
        val syncOpensGarmin: Boolean,
        val needSuggestion: NeedSuggestion?,
        /** Missing nights in the debt window, newest first, never today. */
        val missingNights: List<LocalDate>,
        val energyPotential: Int,
        val phases: List<PhaseItem>,
        /** Nothing is left today: [phases] are tonight's wind-down and melatonin window. */
        val phasesAreTonight: Boolean,
        val nextHabit: NextHabit?,
    ) : SleepUiState
}

enum class EmptyReason {
    INSTALL_HEALTH_CONNECT,
    UPDATE_HEALTH_CONNECT,
    NOT_SUPPORTED,
    PERMISSION_DENIED,
    NO_DATA,
}

enum class LastNightBanner {
    /** Expected but not synced yet. */
    WAITING,

    /** Gave up waiting: offer to add it by hand. */
    MISSING,

    /** Last night is in and its morning review hasn't been done. */
    REVIEW_READY,
}

/** What the data-source chip says. */
sealed interface SourceLabel {
    data object Garmin : SourceLabel
    data object AllSources : SourceLabel
    data object Demo : SourceLabel
    data class App(val name: String) : SourceLabel
}

data class NeedSuggestion(val need: Duration, val confidence: Confidence, val freeNights: Int)

data class PhaseItem(val phase: Phase, val isCurrent: Boolean)

data class NextHabit(
    val type: HabitType,
    val time: Instant,
    val windowEnd: Instant?,
    val reminderOn: Boolean,
)

/** Health Connect as last checked (on start and every resume). */
data class HealthConnectCheck(val status: HealthConnectStatus, val sleepGranted: Boolean)

internal val SyncState.isSyncing: Boolean
    get() = status is SyncStatus.Syncing || status is SyncStatus.Importing

/** Garmin is the source (alone or among all) and we're not in demo mode. */
internal fun AppSettings.syncOpensGarmin(): Boolean =
    !demoMode && when (val source = sleep.source) {
        SourceSelection.All -> true
        is SourceSelection.Single -> source.packageName == SleepConfig.GARMIN_CONNECT_PACKAGE
    }

internal fun emptyReason(demoMode: Boolean, check: HealthConnectCheck): EmptyReason = when {
    demoMode -> EmptyReason.NO_DATA
    check.status == HealthConnectStatus.NOT_INSTALLED -> EmptyReason.INSTALL_HEALTH_CONNECT
    check.status == HealthConnectStatus.UPDATE_REQUIRED -> EmptyReason.UPDATE_HEALTH_CONNECT
    check.status == HealthConnectStatus.NOT_SUPPORTED -> EmptyReason.NOT_SUPPORTED
    !check.sleepGranted -> EmptyReason.PERMISSION_DENIED
    else -> EmptyReason.NO_DATA
}

/** Builds the ready state from the repositories' current values. Pure, so screenshot tests can use it too. */
internal fun readyState(
    insights: DailyInsights,
    settings: AppSettings,
    sync: SyncState,
    habitSettings: List<HabitSettings>,
    pendingReview: LocalDate?,
    source: SourceLabel,
    isRefreshing: Boolean = false,
    needEstimator: SleepNeedEstimator = SleepNeedEstimator(),
): SleepUiState.Ready {
    val banner = when (insights.lastNightState) {
        LastNightState.WAITING -> LastNightBanner.WAITING
        LastNightState.MISSING -> LastNightBanner.MISSING
        LastNightState.AVAILABLE -> LastNightBanner.REVIEW_READY.takeIf { pendingReview == insights.today }
        LastNightState.NOT_EXPECTED_YET -> null
    }
    val suggestion = (insights.needEstimate as? SleepNeedEstimateResult.Available)?.estimate
        ?.takeIf {
            needEstimator.shouldOffer(
                estimate = it,
                currentNeed = settings.sleep.sleepNeed,
                lastDismissedValue = settings.needSuggestionDismissedValue,
                lastDismissedAt = settings.needSuggestionDismissedAt,
                now = insights.now,
            )
        }
        ?.let { NeedSuggestion(it.need, it.confidence, it.freeNightsUsed) }

    val current = insights.currentPhase
    val upcoming = insights.upcomingPhases.filter { it != current }
    val phasesAreTonight = current == null && upcoming.isEmpty()
    val phases = if (phasesAreTonight) {
        insights.schedule.phases
            .filter { it.type == PhaseType.WIND_DOWN || it.type == PhaseType.MELATONIN_WINDOW }
            .map { PhaseItem(it, isCurrent = false) }
    } else {
        listOfNotNull(current).map { PhaseItem(it, isCurrent = true) } + upcoming.map { PhaseItem(it, isCurrent = false) }
    }

    val nextHabit = insights.nextHabit?.let { habit ->
        NextHabit(
            type = habit.type,
            time = habit.time,
            windowEnd = habit.windowEnd,
            // The habit settings flow updates at once; the insights follow after a recompute.
            reminderOn = habitSettings.firstOrNull { it.type == habit.type }?.reminderEnabled ?: habit.reminderEnabled,
        )
    }

    return SleepUiState.Ready(
        today = insights.today,
        now = insights.now,
        zone = insights.zone,
        banner = banner,
        debt = insights.debt.debt,
        status = insights.status,
        debtIsFromYesterday = insights.lastNight == null,
        debtChange = insights.debtChange,
        streakDays = insights.streak.days,
        lastNightSleep = insights.lastNight?.mainSleep,
        source = source,
        lastSyncAt = sync.lastSuccessAt,
        lastSyncError = sync.lastError,
        isSyncing = sync.isSyncing,
        isRefreshing = isRefreshing,
        syncOpensGarmin = settings.syncOpensGarmin(),
        needSuggestion = suggestion,
        missingNights = insights.missingNights.filter { it != insights.today }.sortedDescending(),
        energyPotential = insights.energyPotential,
        phases = phases,
        phasesAreTonight = phasesAreTonight,
        nextHabit = nextHabit,
    )
}
