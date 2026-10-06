package com.sleepsaver.feature.habits

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.RoutineActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

internal sealed interface EveningRoutineUiState {
    data object Loading : EveningRoutineUiState

    /** The activities couldn't be read. */
    data object Unavailable : EveningRoutineUiState

    data class Ready(
        val activities: List<RoutineActivity>,
        /** The evening routine is already in the schedule (the button then just saves). */
        val routineEnabled: Boolean,
        /** "Add to schedule" finished: the screen closes itself. */
        val done: Boolean = false,
    ) : EveningRoutineUiState {
        val canSave: Boolean get() = activities.any { it.selected }
        val hasCustom: Boolean get() = activities.any { it.isCustom }
    }
}

@HiltViewModel
internal class EveningRoutineViewModel @Inject constructor(
    private val habits: HabitsRepository,
    private val writer: HabitSettingsWriter,
) : ViewModel() {

    private val done = MutableStateFlow(false)

    val uiState: StateFlow<EveningRoutineUiState> =
        combine(habits.routineActivities, habits.habitSettings, done) { activities, settings, done ->
            val routine = completeSettings(settings).first { it.type == HabitType.EVENING_ROUTINE }
            EveningRoutineUiState.Ready(activities, routineEnabled = routine.enabled, done = done)
        }
            .catch<EveningRoutineUiState> {
                Log.w(LOG_TAG, "Loading routine activities failed", it)
                emit(EveningRoutineUiState.Unavailable)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EveningRoutineUiState.Loading)

    fun setSelected(id: Long, selected: Boolean) {
        viewModelScope.launch { safely("Selecting activity $id") { habits.setActivitySelected(id, selected) } }
    }

    /** Blank labels are ignored; a blank emoji gets a default sparkle. Labels are capped at [MAX_LABEL_LENGTH]. */
    fun addCustom(label: String, emoji: String) {
        val cleanLabel = label.trim().take(MAX_LABEL_LENGTH).trimEnd()
        if (cleanLabel.isEmpty()) return
        val cleanEmoji = emoji.trim().ifEmpty { DEFAULT_CUSTOM_EMOJI }
        viewModelScope.launch { safely("Adding activity") { habits.addCustomActivity(cleanLabel, cleanEmoji) } }
    }

    /** Only custom activities can be removed. */
    fun delete(id: Long) {
        val ready = uiState.value as? EveningRoutineUiState.Ready ?: return
        if (ready.activities.none { it.id == id && it.isCustom }) return
        viewModelScope.launch { safely("Removing activity $id") { habits.deleteActivity(id) } }
    }

    /**
     * Puts the evening routine in the schedule (re-arming reminders), then flags
     * [EveningRoutineUiState.Ready.done]. Stays open if saving failed.
     */
    fun addToSchedule() {
        val ready = uiState.value as? EveningRoutineUiState.Ready ?: return
        if (!ready.canSave || ready.done) return
        viewModelScope.launch {
            if (writer.setEnabled(HabitType.EVENING_ROUTINE, true)) done.value = true
        }
    }

    companion object {
        const val MAX_LABEL_LENGTH = 24
        const val DEFAULT_CUSTOM_EMOJI = "✨"
    }
}
