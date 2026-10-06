package com.sleepsaver.feature.habits

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.domain.habits.HabitScheduler
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.ScheduledHabit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/** One habit as a list row. [time]/[windowEnd] are today's, null when there's no schedule yet. */
internal data class HabitRowState(
    val type: HabitType,
    val enabled: Boolean,
    val reminderEnabled: Boolean,
    val time: Instant? = null,
    val windowEnd: Instant? = null,
)

internal sealed interface HabitsUiState {
    data object Loading : HabitsUiState

    /** The habit settings couldn't be read. */
    data object Unavailable : HabitsUiState

    data class Ready(
        /** Enabled habits, in time order when times are known. */
        val scheduled: List<HabitRowState>,
        /** Disabled habits. */
        val available: List<HabitRowState>,
        /** Zone to show times in; null without a schedule. */
        val zone: ZoneId?,
        val times: TimesStatus,
    ) : HabitsUiState {
        /** At least one reminder would fire, so blocked notifications are worth mentioning. */
        val anyReminderOn: Boolean get() = scheduled.any { it.reminderEnabled }
    }
}

@HiltViewModel
internal class HabitsViewModel @Inject constructor(
    habits: HabitsRepository,
    insights: InsightsRepository,
    private val writer: HabitSettingsWriter,
) : ViewModel() {

    val uiState: StateFlow<HabitsUiState> =
        combine(habits.habitSettings, insights.state) { settings, state -> habitsUiState(settings, state) }
            .catch<HabitsUiState> {
                Log.w(LOG_TAG, "Loading habits failed", it)
                emit(HabitsUiState.Unavailable)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitsUiState.Loading)

    fun setEnabled(type: HabitType, enabled: Boolean) {
        viewModelScope.launch { writer.setEnabled(type, enabled) }
    }

    fun setReminder(type: HabitType, enabled: Boolean) {
        viewModelScope.launch { writer.setReminder(type, enabled) }
    }
}

/**
 * Builds the list from the stored settings (types missing from [settings] get their catalog defaults) and
 * today's schedule. Times are recomputed from the live settings, so they match the toggles even before
 * the insights catch up.
 */
internal fun habitsUiState(
    settings: List<HabitSettings>,
    insights: InsightsState,
    scheduler: HabitScheduler = HabitScheduler(habitCatalog),
): HabitsUiState.Ready {
    val complete = completeSettings(settings)
    val ready = insights as? InsightsState.Ready
    val scheduled: Map<HabitType, ScheduledHabit> =
        ready?.let { scheduler.schedule(it.insights.schedule, complete).associateBy { habit -> habit.type } }.orEmpty()
    val rows = complete.map { setting ->
        val habit = scheduled[setting.type]
        HabitRowState(setting.type, setting.enabled, setting.reminderEnabled, habit?.time, habit?.windowEnd)
    }
    // Stored order is the fallback; with a schedule, rows follow the day. Untimed rows go last.
    val ordered = rows.withIndex()
        .sortedWith(compareBy({ it.value.time == null }, { it.value.time }, { it.index }))
        .map { it.value }
    return HabitsUiState.Ready(
        scheduled = ordered.filter { it.enabled },
        available = ordered.filterNot { it.enabled },
        zone = ready?.insights?.schedule?.zone,
        times = insights.timesStatus(),
    )
}

/** [settings] in their stored order, plus catalog defaults for any type that has no row. */
internal fun completeSettings(settings: List<HabitSettings>): List<HabitSettings> {
    val present = settings.distinctBy { it.type }
    val missing = habitCatalog.defaultSettings().filter { default -> present.none { it.type == default.type } }
    return present + missing
}
