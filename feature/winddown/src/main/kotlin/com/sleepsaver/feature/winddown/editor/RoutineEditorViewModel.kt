package com.sleepsaver.feature.winddown.editor

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.sleepsaver.core.data.hooks.ReminderPlanner
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.data.repository.NightStepsRepository
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.habits.HabitCatalog
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.domain.nightsteps.StepTiming
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.feature.winddown.LOG_TAG
import com.sleepsaver.feature.winddown.WinddownWriter
import com.sleepsaver.feature.winddown.navigation.RoutineEditorRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import javax.inject.Inject

/** One activity in the editor: [included] in the list being edited. */
internal data class EditorRow(val activity: RoutineActivity, val included: Boolean)

internal sealed interface RoutineEditorUiState {
    data object Loading : RoutineEditorUiState

    data object Unavailable : RoutineEditorUiState

    data class Ready(
        /** null = every evening; else that weekday's evening. */
        val day: DayOfWeek?,
        /** The day's own list (ids in order); null = it follows the every-evening list. Always null for every evening. */
        val ownList: List<Long>?,
        /** Every activity in its stored order, included or not. */
        val rows: List<EditorRow>,
        /** The EVENING_ROUTINE habit is on with its reminder. */
        val reminderOn: Boolean,
        val leadMaxMinutes: Int = SleepConfig.DEFAULT.steps.leadMax.toMinutes().toInt(),
        val leadStepMinutes: Int = SleepConfig.DEFAULT.steps.leadStep.toMinutes().toInt(),
        val durationMaxMinutes: Int = SleepConfig.DEFAULT.steps.durationMax.toMinutes().toInt(),
        val durationStepMinutes: Int = SleepConfig.DEFAULT.steps.durationStep.toMinutes().toInt(),
    ) : RoutineEditorUiState {
        /** Changes go to the every-evening list (routine_activities.selected), not a day's own list. */
        val editsEveryEvening: Boolean get() = ownList == null
    }
}

/**
 * The editor's state. Every evening: rows included = selected. A day: "Same as every evening" when its evening plan
 * has no own list, else rows included = in that list. [pendingTiming] and [pendingOwn] are the latest picks not yet
 * echoed back by storage, so quick taps build on each other.
 */
internal fun routineEditorUiState(
    day: DayOfWeek?,
    week: WeekPlan,
    activities: List<RoutineActivity>,
    habits: List<HabitSettings>,
    pendingTiming: Map<Long, StepTiming> = emptyMap(),
    pendingOwn: PendingOwn? = null,
    config: SleepConfig = SleepConfig.DEFAULT,
): RoutineEditorUiState.Ready {
    val own = when {
        day == null -> null
        pendingOwn != null -> pendingOwn.ids
        else -> week.day(day).evening.routine
    }
    val shown = activities.map { activity ->
        val timing = pendingTiming[activity.id]
        if (timing == null) activity else activity.copy(lead = timing.lead, duration = timing.duration)
    }
    val routine = habits.firstOrNull { it.type == HabitType.EVENING_ROUTINE }
        ?: HabitCatalog(config).defaultSettings().first { it.type == HabitType.EVENING_ROUTINE }
    return RoutineEditorUiState.Ready(
        day = day,
        ownList = own,
        rows = shown.map { EditorRow(it, included = if (own == null) it.selected else it.id in own) },
        reminderOn = routine.enabled && routine.reminderEnabled,
        leadMaxMinutes = config.steps.leadMax.toMinutes().toInt(),
        leadStepMinutes = config.steps.leadStep.toMinutes().toInt(),
        durationMaxMinutes = config.steps.durationMax.toMinutes().toInt(),
        durationStepMinutes = config.steps.durationStep.toMinutes().toInt(),
    )
}

/** A day-list change not yet echoed back: [ids] null = back to the every-evening list. */
internal data class PendingOwn(val ids: List<Long>?)

/**
 * The routine editor, the only one in the app (feature:habits' "Edit Night steps" opens it too). Every evening edits
 * the selected activities and each activity's lead and length; a day can follow that list or keep its own
 * (`ScheduleRepository.setDayRoutine`). Lead and length belong to the activity, so they are the same on every evening.
 */
@HiltViewModel
internal class RoutineEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val nightSteps: NightStepsRepository,
    private val schedule: ScheduleRepository,
    private val habits: HabitsRepository,
    private val planner: ReminderPlanner,
    private val writer: WinddownWriter,
) : ViewModel() {

    private val config = SleepConfig.DEFAULT

    /** RoutineEditorRoute.day: DayOfWeek.value, 0 (or anything else) = every evening. */
    private val day: DayOfWeek? = runCatching { savedStateHandle.toRoute<RoutineEditorRoute>().day }
        .getOrDefault(0)
        .takeIf { it in 1..7 }
        ?.let { DayOfWeek.of(it) }

    private val pendingTiming = MutableStateFlow<Map<Long, StepTiming>>(emptyMap())
    private val pendingOwn = MutableStateFlow<PendingOwn?>(null)

    // Picks are dropped once storage shows them, so the screen goes back to following storage.
    private val activities = nightSteps.activities.onEach { stored ->
        pendingTiming.update { pending ->
            pending.filterNot { (id, timing) -> stored.any { it.id == id && it.lead == timing.lead && it.duration == timing.duration } }
        }
    }
    private val week = schedule.weekPlan.onEach { stored ->
        val day = day ?: return@onEach
        pendingOwn.update { pending -> pending?.takeIf { it.ids != stored.day(day).evening.routine } }
    }

    val uiState: StateFlow<RoutineEditorUiState> = combine(
        week,
        activities,
        habits.habitSettings,
        pendingTiming,
        pendingOwn,
    ) { week, activities, habitSettings, timing, own ->
        routineEditorUiState(day, week, activities, habitSettings, timing, own, config)
    }
        .catch<RoutineEditorUiState> {
            Log.w(LOG_TAG, "Loading the routine editor failed", it)
            emit(RoutineEditorUiState.Unavailable)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutineEditorUiState.Loading)

    private val ready: RoutineEditorUiState.Ready? get() = uiState.value as? RoutineEditorUiState.Ready

    /** Day mode: on = follow the every-evening list; off = an own list, pre-filled with the current routine. */
    fun setSameAsEveryEvening(same: Boolean) {
        val day = day ?: return
        val state = ready ?: return
        if (same == state.editsEveryEvening) return
        val ids = if (same) null else state.rows.filter { it.included }.map { it.activity.id }
        saveOwn(day, ids)
    }

    fun setIncluded(activityId: Long, included: Boolean) {
        val state = ready ?: return
        val own = state.ownList
        if (own == null || day == null) {
            viewModelScope.launch { writer.save("Including activity $activityId") { nightSteps.setIncluded(activityId, included) } }
        } else {
            saveOwn(day, if (included) own.filterNot { it == activityId } + activityId else own.filterNot { it == activityId })
        }
    }

    /** [minutes] before bedtime, clamped to 0..leadMax. */
    fun setLead(activityId: Long, minutes: Int) = saveTiming(activityId) { timing, state ->
        timing.copy(lead = Duration.ofMinutes(minutes.coerceIn(0, state.leadMaxMinutes).toLong()))
    }

    /** [minutes] long, clamped to 0..durationMax (0 = a checklist item). */
    fun setDuration(activityId: Long, minutes: Int) = saveTiming(activityId) { timing, state ->
        timing.copy(duration = Duration.ofMinutes(minutes.coerceIn(0, state.durationMaxMinutes).toLong()))
    }

    /**
     * "Add your own": a custom activity at `steps.customLead` / `customDuration`. Every evening: selected for every
     * evening. A day with its own list: on that list only (addCustom selects it for every evening, so that is undone in
     * the same write). Blank labels add nothing.
     */
    fun addCustom(label: String, emoji: String) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        val icon = emoji.trim().ifEmpty { DEFAULT_CUSTOM_EMOJI }
        val day = day
        val ownList = day?.let { ready?.ownList }
        viewModelScope.launch {
            val id = writer.saveFor("Adding $trimmed") {
                nightSteps.addCustom(trimmed, icon, config.steps.customLead, config.steps.customDuration).also { id ->
                    if (ownList != null) nightSteps.setIncluded(id, false)
                }
            } ?: return@launch
            if (day == null || ownList == null) return@launch
            val own = ready?.ownList ?: ownList
            if (id !in own) saveOwn(day, own + id)
        }
    }

    /** Custom activities only; their past check-offs stay. */
    fun remove(activityId: Long) {
        if (ready?.rows?.firstOrNull { it.activity.id == activityId }?.activity?.isCustom != true) return
        viewModelScope.launch { writer.save("Removing activity $activityId") { nightSteps.deleteCustom(activityId) } }
    }

    /** "Reminder when Night steps start": on = the EVENING_ROUTINE habit on with its reminder; off = reminder off. */
    fun setReminder(on: Boolean) {
        viewModelScope.launch {
            val saved = writer.save("Saving the Night steps reminder") {
                if (on) habits.setEnabled(HabitType.EVENING_ROUTINE, true)
                habits.setReminder(HabitType.EVENING_ROUTINE, on)
            }
            if (saved) writer.save("Replanning reminders") { planner.replan() }
        }
    }

    private fun saveTiming(activityId: Long, change: (StepTiming, RoutineEditorUiState.Ready) -> StepTiming) {
        val state = ready ?: return
        val row = state.rows.firstOrNull { it.activity.id == activityId } ?: return
        val current = StepTiming(row.activity.lead, row.activity.duration)
        val next = change(current, state)
        if (next == current) return
        pendingTiming.update { it + (activityId to next) }
        viewModelScope.launch {
            val saved = writer.save("Timing activity $activityId") { nightSteps.setTiming(activityId, next.lead, next.duration) }
            if (!saved) pendingTiming.update { pending -> if (pending[activityId] == next) pending - activityId else pending }
        }
    }

    private fun saveOwn(day: DayOfWeek, ids: List<Long>?) {
        val pending = PendingOwn(ids)
        pendingOwn.value = pending
        viewModelScope.launch {
            if (!writer.save("Saving $day's steps") { schedule.setDayRoutine(day, ids) }) pendingOwn.compareAndSet(pending, null)
        }
    }

    companion object {
        /** A custom activity added without an emoji. */
        const val DEFAULT_CUSTOM_EMOJI = "✨"
    }
}
