package com.sleepsaver.feature.review.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SleepRepository
import com.sleepsaver.feature.review.common.loadEditor
import com.sleepsaver.feature.review.common.parseWakeDate
import com.sleepsaver.feature.review.common.today
import com.sleepsaver.feature.review.navigation.EditNightRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

data class EditNightUiState(
    val wakeDate: LocalDate,
    val today: LocalDate,
    /** null while the night loads (or when it couldn't be read, see [loadFailed]). */
    val editor: NightEditorState? = null,
    /** The night couldn't be read: the screen says so instead of offering an editor that might overwrite it. */
    val loadFailed: Boolean = false,
    val saving: Boolean = false,
    /** The last save or revert didn't go through: the editor stays open with a note. */
    val saveFailed: Boolean = false,
    /** Saved or reverted: the screen closes. */
    val finished: Boolean = false,
)

@HiltViewModel
class EditNightViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sleepRepository: SleepRepository,
    settingsRepository: SettingsRepository,
    insightsRepository: InsightsRepository,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val wakeDate: LocalDate = timeProvider.parseWakeDate(savedStateHandle.toRoute<EditNightRoute>().wakeDate)

    private val state = MutableStateFlow(EditNightUiState(wakeDate = wakeDate, today = timeProvider.today()))
    val uiState: StateFlow<EditNightUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val editor = runCatching {
                loadEditor(wakeDate, sleepRepository, settingsRepository, insightsRepository, timeProvider)
            }.getOrNull()
            state.update { it.copy(editor = editor, loadFailed = editor == null) }
        }
    }

    fun onFellAsleepChange(time: Instant) = edit { it.withFellAsleep(time) }

    fun onWokeUpChange(time: Instant) = edit { it.withWokeUp(time) }

    fun onAwakeChange(minutes: Int) = edit { it.withAwake(minutes) }

    fun onNapsChange(minutes: Int) = edit { it.withNaps(minutes) }

    /** Saves only what changed (or adds the missing night) and closes. Nothing to save just closes. */
    fun onDone() {
        val current = state.value
        val editor = current.editor ?: return
        if (current.saving || current.finished || editor.error != null) return
        state.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            val ok = runCatching { sleepRepository.apply(editor.toSave(timeProvider.now())) }.isSuccess
            state.update { it.copy(saving = false, saveFailed = !ok, finished = ok) }
        }
    }

    /** Drops the edits (or removes a manual night) and closes. */
    fun onRevert() {
        if (state.value.saving || state.value.finished) return
        state.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            val ok = runCatching { sleepRepository.revert(wakeDate) }.isSuccess
            state.update { it.copy(saving = false, saveFailed = !ok, finished = ok) }
        }
    }

    private fun edit(block: (NightEditorState) -> NightEditorState) =
        state.update { s -> if (s.saving || s.editor == null) s else s.copy(editor = block(s.editor), saveFailed = false) }
}
