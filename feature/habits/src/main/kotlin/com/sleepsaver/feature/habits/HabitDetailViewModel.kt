package com.sleepsaver.feature.habits

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.domain.habits.HabitScheduler
import com.sleepsaver.core.domain.model.HabitAnchor
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.feature.habits.navigation.HabitDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

internal sealed interface HabitDetailUiState {
    data object Loading : HabitDetailUiState

    /** The route named a habit type this version doesn't know. */
    data object NotFound : HabitDetailUiState

    /** The habit settings couldn't be read. */
    data object Unavailable : HabitDetailUiState

    data class Ready(
        val type: HabitType,
        val enabled: Boolean,
        val reminderEnabled: Boolean,
        /** The stored signed offset from [anchor]. */
        val offset: Duration,
        val anchor: HabitAnchor,
        val timing: TimingSpec,
        /** Stepper value for [offset], inside the timing range. */
        val value: Int,
        val defaultValue: Int,
        /** Today's time and window end; null without a schedule. */
        val time: Instant? = null,
        val windowEnd: Instant? = null,
        /** Today's instant of [anchor] ("your 10:45 PM target bedtime"). */
        val anchorTime: Instant? = null,
        val zone: ZoneId? = null,
        val times: TimesStatus = TimesStatus.READY,
        /** The evening routine's picked activities (empty for every other habit). */
        val activities: List<RoutineActivity> = emptyList(),
    ) : HabitDetailUiState {
        val isDefaultTiming: Boolean get() = value == defaultValue
    }
}

@HiltViewModel
internal class HabitDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    habits: HabitsRepository,
    insights: InsightsRepository,
    private val writer: HabitSettingsWriter,
) : ViewModel() {

    private val type: HabitType? = runCatching {
        HabitType.valueOf(savedStateHandle.toRoute<HabitDetailRoute>().type)
    }.getOrNull()

    val uiState: StateFlow<HabitDetailUiState> = if (type == null) {
        MutableStateFlow(HabitDetailUiState.NotFound)
    } else {
        combine(habits.habitSettings, habits.routineActivities, insights.state) { settings, activities, state ->
            habitDetailUiState(type, settings, activities, state)
        }
            .catch<HabitDetailUiState> {
                Log.w(LOG_TAG, "Loading habit $type failed", it)
                emit(HabitDetailUiState.Unavailable)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitDetailUiState.Loading)
    }

    fun setEnabled(enabled: Boolean) = edit { writer.setEnabled(it, enabled) }

    fun setReminder(enabled: Boolean) = edit { writer.setReminder(it, enabled) }

    /** [value] is a stepper value (e.g. minutes before bedtime); it's clamped to the habit's range. */
    fun setTimingValue(value: Int) {
        val ready = uiState.value as? HabitDetailUiState.Ready ?: return
        val offset = ready.timing.offsetOf(value)
        if (offset == ready.offset) return
        edit { writer.setOffset(it, offset) }
    }

    fun resetTiming() = edit { writer.resetOffset(it) }

    private fun edit(block: suspend (HabitType) -> Unit) {
        val type = type ?: return
        viewModelScope.launch { block(type) }
    }
}

internal fun habitDetailUiState(
    type: HabitType,
    settings: List<HabitSettings>,
    activities: List<RoutineActivity>,
    insights: InsightsState,
    scheduler: HabitScheduler = HabitScheduler(habitCatalog),
): HabitDetailUiState.Ready {
    val complete = completeSettings(settings)
    val setting = complete.first { it.type == type }
    val definition = habitCatalog.definition(type)
    val timing = timingSpec(type)
    val schedule = (insights as? InsightsState.Ready)?.insights?.schedule
    val habit = schedule?.let { scheduler.schedule(it, complete).firstOrNull { habit -> habit.type == type } }
    return HabitDetailUiState.Ready(
        type = type,
        enabled = setting.enabled,
        reminderEnabled = setting.reminderEnabled,
        offset = setting.offset,
        anchor = definition.anchor,
        timing = timing,
        value = timing.valueOf(setting.offset),
        defaultValue = timing.valueOf(definition.defaultOffset),
        time = habit?.time,
        windowEnd = habit?.windowEnd,
        anchorTime = schedule?.let { anchorTime(it, definition.anchor) },
        zone = schedule?.zone,
        times = insights.timesStatus(),
        activities = if (type == HabitType.EVENING_ROUTINE) activities.filter { it.selected } else emptyList(),
    )
}
