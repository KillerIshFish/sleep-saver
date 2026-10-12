package com.sleepsaver.feature.habits

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.domain.habits.HabitScheduler
import com.sleepsaver.core.domain.model.HabitAnchor
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.feature.habits.navigation.HabitDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
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
        /** The signed offset from [anchor] the habit is timed with (Bedtime's is always zero, whatever is stored). */
        val offset: Duration,
        val anchor: HabitAnchor,
        /** The timing stepper; null when the habit's time can't be moved (Bedtime: changed in Settings or the week plan). */
        val timing: TimingSpec?,
        /** Stepper value for [offset], inside the timing range (0 without a stepper). */
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
        /** Evenings the week plan switches Night steps off for (the evening routine only), e.g. Fri and Sat. */
        val stepsOffEvenings: Set<DayOfWeek> = emptySet(),
    ) : HabitDetailUiState {
        val isDefaultTiming: Boolean get() = value == defaultValue
    }
}

/**
 * One habit's detail. For the evening routine (Night steps) it also reads the week plan, to say which evenings have
 * Night steps switched off.
 */
@HiltViewModel
internal class HabitDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    habits: HabitsRepository,
    insights: InsightsRepository,
    private val writer: HabitSettingsWriter,
    schedule: ScheduleRepository,
) : ViewModel() {

    private val type: HabitType? = runCatching {
        HabitType.valueOf(savedStateHandle.toRoute<HabitDetailRoute>().type)
    }.getOrNull()

    /**
     * The offset the user last picked, shown before the database has caught up, so quick taps on the
     * stepper each move it one step instead of re-sending the same value. null = show what's stored.
     */
    private val pendingOffset = MutableStateFlow<Duration?>(null)

    val uiState: StateFlow<HabitDetailUiState> = if (type == null) {
        MutableStateFlow(HabitDetailUiState.NotFound)
    } else {
        // Only the evening routine shows the week plan, and a plan that can't be read just leaves its line out.
        val week = if (type == HabitType.EVENING_ROUTINE) {
            schedule.weekPlan.catch<WeekPlan?> { emit(null) }
        } else {
            flowOf(null)
        }
        combine(
            habits.habitSettings,
            habits.routineActivities,
            insights.state,
            pendingOffset,
            week,
        ) { settings, activities, state, pending, plan ->
            val shown = if (pending == null) settings else withOffset(settings, type, pending)
            habitDetailUiState(type, shown, activities, state, week = plan)
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
        val offset = ready.timing?.offsetOf(value) ?: return
        if (offset == ready.offset) return
        showThenSave(offset) { writer.setOffset(it, offset) }
    }

    fun resetTiming() {
        val type = type ?: return
        if (timingSpec(type) == null) return
        showThenSave(habitCatalog.definition(type).defaultOffset) { writer.resetOffset(it) }
    }

    /** Shows [offset] right away; if saving fails, falls back to whatever is stored. */
    private fun showThenSave(offset: Duration, save: suspend (HabitType) -> Boolean) {
        val type = type ?: return
        pendingOffset.value = offset
        viewModelScope.launch {
            if (!save(type)) pendingOffset.compareAndSet(offset, null)
        }
    }

    private fun edit(block: suspend (HabitType) -> Unit) {
        val type = type ?: return
        viewModelScope.launch { block(type) }
    }
}

/** [settings] with [type]'s offset replaced (added with its other defaults if it has no row yet). */
private fun withOffset(settings: List<HabitSettings>, type: HabitType, offset: Duration): List<HabitSettings> =
    completeSettings(settings).map { if (it.type == type) it.copy(offset = offset) else it }

/** [week] is the week plan (for the evening routine's "Off on Fri, Sat" line); null leaves the line out. */
internal fun habitDetailUiState(
    type: HabitType,
    settings: List<HabitSettings>,
    activities: List<RoutineActivity>,
    insights: InsightsState,
    scheduler: HabitScheduler = HabitScheduler(habitCatalog),
    week: WeekPlan? = null,
): HabitDetailUiState.Ready {
    val complete = completeSettings(settings)
    val setting = complete.first { it.type == type }
    val definition = habitCatalog.definition(type)
    val timing = timingSpec(type)
    val schedule = (insights as? InsightsState.Ready)?.insights?.schedule
    val habit = schedule?.let { scheduler.schedule(it, complete).firstOrNull { habit -> habit.type == type } }
    val offset = habitCatalog.effectiveOffset(setting)
    return HabitDetailUiState.Ready(
        type = type,
        enabled = setting.enabled,
        reminderEnabled = setting.reminderEnabled,
        offset = offset,
        anchor = definition.anchor,
        timing = timing,
        value = timing?.valueOf(offset) ?: 0,
        defaultValue = timing?.valueOf(definition.defaultOffset) ?: 0,
        time = habit?.time,
        windowEnd = habit?.windowEnd,
        anchorTime = schedule?.let { anchorTime(it, definition.anchor) },
        zone = schedule?.zone,
        times = insights.timesStatus(),
        activities = if (type == HabitType.EVENING_ROUTINE) activities.filter { it.selected } else emptyList(),
        stepsOffEvenings = if (type == HabitType.EVENING_ROUTINE && week != null) {
            week.days.filter { !it.evening.nightStepsOn }.mapTo(LinkedHashSet()) { it.day }
        } else {
            emptySet()
        },
    )
}
