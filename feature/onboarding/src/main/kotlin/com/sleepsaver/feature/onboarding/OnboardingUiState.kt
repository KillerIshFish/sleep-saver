package com.sleepsaver.feature.onboarding

import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.domain.model.Confidence
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.feature.onboarding.connect.ConnectionUiState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class OnboardingStep { WELCOME, CONNECT, IMPORT, SLEEP_NEED, HABITS }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val connection: ConnectionUiState = ConnectionUiState(),
    val import: ImportUiState = ImportUiState.NotStarted,
    val sleepNeed: SleepNeedUiState = SleepNeedUiState(),
    val habits: HabitsUiState = HabitsUiState(),
    /** Switching demo mode or saving: buttons that would race with it are disabled. */
    val busy: Boolean = false,
    /** Everything is saved: the screen calls onFinished. */
    val finished: Boolean = false,
)

/** What the import step found. [recent] holds the last 14 wake dates, oldest first (null = no night). */
data class NightsSummary(
    val count: Int = 0,
    val first: LocalDate? = null,
    val last: LocalDate? = null,
    val recent: List<Duration?> = emptyList(),
)

sealed interface ImportUiState {
    data object NotStarted : ImportUiState

    /** [progress] 0..1 when Health Connect reports it. */
    data class Importing(val progress: Float?) : ImportUiState

    /** Real history is in. `nights.count` may be 0: nights then show up after the next sync. */
    data class Done(val nights: NightsSummary) : ImportUiState

    /** Demo mode: nothing to import. */
    data class Demo(val nights: NightsSummary) : ImportUiState

    data class Failed(val error: SyncError?) : ImportUiState
}

data class NeedSuggestion(val minutes: Int, val confidence: Confidence, val freeNights: Int)

data class SleepNeedUiState(
    val needMinutes: Int = DEFAULT_NEED_MINUTES,
    val suggestion: NeedSuggestion? = null,
    val wake: LocalTime = LocalTime.of(7, 0),
    /** True when [wake] was picked from the user's recent nights rather than the 7:00 default. */
    val wakeFromNights: Boolean = false,
    /** The day and zone the wake time is shown on (the scrubber works on instants). */
    val date: LocalDate = LocalDate.of(2026, 1, 1),
    val zone: ZoneId = ZoneId.of("UTC"),
) {
    companion object {
        const val MIN_NEED_MINUTES = 6 * 60
        const val MAX_NEED_MINUTES = 11 * 60
        const val NEED_STEP_MINUTES = 5
        const val DEFAULT_NEED_MINUTES = 8 * 60
    }
}

data class HabitOption(val type: HabitType, val selected: Boolean, val time: Instant? = null)

data class HabitsUiState(
    val options: List<HabitOption> = emptyList(),
    val remind: Boolean = true,
    val zone: ZoneId = ZoneId.of("UTC"),
) {
    /** Finishing should ask for the notification permission (Android 13+). */
    val wantsNotifications: Boolean get() = remind && options.any { it.selected }
}

/** Present nights → counts, range and the last 14 wake dates ending [today]. */
internal fun summarize(nights: List<Night>, today: LocalDate): NightsSummary {
    val present = nights.filter { it.isPresent }
    val byDate = present.associateBy { it.wakeDate }
    return NightsSummary(
        count = present.size,
        first = present.minOfOrNull { it.wakeDate },
        last = present.maxOfOrNull { it.wakeDate },
        recent = (13 downTo 0).map { byDate[today.minusDays(it.toLong())]?.totalSleep },
    )
}

/**
 * Median local wake time of the last [maxNights] present nights, or null without any. Times after midnight
 * wrap correctly only within one day, which is fine for wake times.
 */
internal fun medianWake(nights: List<Night>, maxNights: Int = 14): LocalTime? {
    val minutes = nights
        .filter { it.isPresent && it.wokeUp != null }
        .sortedByDescending { it.wakeDate }
        .take(maxNights)
        .mapNotNull { night ->
            val offset = night.wokeUpOffset ?: return@mapNotNull null
            val local = night.wokeUp!!.atOffset(offset).toLocalTime()
            local.hour * 60 + local.minute
        }
        .sorted()
    if (minutes.isEmpty()) return null
    val mid = minutes.size / 2
    val median = if (minutes.size % 2 == 1) minutes[mid] else (minutes[mid - 1] + minutes[mid]) / 2
    // Snap to the scrubber's 5-minute grid.
    val snapped = ((median + 2) / 5 * 5) % (24 * 60)
    return LocalTime.of(snapped / 60, snapped % 60)
}
