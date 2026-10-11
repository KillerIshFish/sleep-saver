package com.sleepsaver.feature.review.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.JournalRepository
import com.sleepsaver.core.data.repository.MovementTrace
import com.sleepsaver.core.data.repository.NightLogRepository
import com.sleepsaver.core.data.repository.PatternsRepository
import com.sleepsaver.core.data.repository.SleepRepository
import com.sleepsaver.core.domain.alarm.WakeLanding
import com.sleepsaver.core.domain.journal.FeelSource
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.journal.markEditable
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.patterns.AnchorNightStats
import com.sleepsaver.feature.review.common.JournalWindow
import com.sleepsaver.feature.review.common.NightSource
import com.sleepsaver.feature.review.common.journalWindow
import com.sleepsaver.feature.review.common.parseWakeDate
import com.sleepsaver.feature.review.common.today
import com.sleepsaver.feature.review.navigation.NightDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * One night in full. Besides the night itself: its marks and Morning feel (editable while [marksEditable] /
 * [feelEditable]), the Drop anchor stats, the Wake landing and the phone's movement around the alarm.
 */
data class NightDetailUiState(
    val wakeDate: LocalDate,
    val today: LocalDate,
    val loading: Boolean = true,
    /** null (or not present) = nothing recorded for this night. */
    val night: Night? = null,
    val source: NightSource? = null,
    val marks: Set<Mark> = emptySet(),
    val feel: MorningFeel? = null,
    /** `feelEditable`: until the wake time of the day after [wakeDate]. */
    val feelEditable: Boolean = false,
    /** "Anchor dropped 10:58 PM · fell asleep about 15 min later · …" (null = no anchor that night). */
    val anchor: AnchorNightStats? = null,
    /** Where the planned alarm landed (null = no planned alarm that morning). */
    val landing: WakeLanding? = null,
    /** The phone's movement logs for this wake date (whole-night log and surfacing window run). */
    val movement: List<MovementTrace> = emptyList(),
) {
    val isMissing: Boolean get() = !loading && night?.isPresent != true

    /** Marks can still be set or cleared for this night (`markEditable`: the last 7 wake dates and tonight). */
    val marksEditable: Boolean get() = markEditable(wakeDate, today)
}

@HiltViewModel
class NightDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sleepRepository: SleepRepository,
    private val timeProvider: TimeProvider,
    private val journalRepository: JournalRepository,
    nightLogRepository: NightLogRepository,
    patternsRepository: PatternsRepository,
) : ViewModel() {

    private val wakeDate: LocalDate = timeProvider.parseWakeDate(savedStateHandle.toRoute<NightDetailRoute>().wakeDate)
    private val initial = NightDetailUiState(wakeDate = wakeDate, today = timeProvider.today())

    private data class Journal(
        val marks: Set<Mark>,
        val feel: MorningFeel?,
        val anchor: AnchorNightStats?,
        val landing: WakeLanding?,
        val movement: List<MovementTrace>,
    )

    private val journal: Flow<Journal> = combine(
        journalRepository.marks(wakeDate).orDefault(emptySet()),
        journalRepository.feel(wakeDate).orDefault(null),
        patternsRepository.anchorStats(wakeDate).orDefault(null),
        nightLogRepository.landing(wakeDate).orDefault(null),
        nightLogRepository.movement(wakeDate).orDefault(emptyList()),
    ) { marks, feel, anchor, landing, movement -> Journal(marks, feel?.feel, anchor, landing, movement) }

    private val window: StateFlow<JournalWindow?> = journalWindow(wakeDate, timeProvider, patternsRepository)
        .catch { emit(JournalWindow(timeProvider.today(), timeProvider.now(), feelEditable = false, marksEditable = false)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uiState: StateFlow<NightDetailUiState> = combine(sleepRepository.night(wakeDate), journal, window) { night, journal, window ->
        initial.copy(
            today = window?.today ?: timeProvider.today(),
            loading = false,
            night = night,
            source = night?.let { NightSource.of(it, timeProvider.zone(), timeProvider.now()) },
            marks = journal.marks,
            feel = journal.feel,
            feelEditable = window?.feelEditable ?: false,
            anchor = journal.anchor,
            landing = journal.landing,
            movement = journal.movement,
        )
    }
        .catch { emit(initial.copy(loading = false)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    /** Deletes a nap the user added (manual nap blocks carry "manual-nap:<id>"). */
    fun onDeleteNap(id: Long) {
        viewModelScope.launch { runCatching { sleepRepository.deleteNap(id) } }
    }

    /** Back to what the data source recorded (removes a manual night). */
    fun onRevert() {
        viewModelScope.launch { runCatching { sleepRepository.revert(wakeDate) } }
    }

    /** Ticks or unticks a mark for this night, while marks can still change. */
    fun onMarkToggle(mark: Mark) {
        val state = uiState.value
        if (mark.derived || !state.marksEditable) return
        val on = mark !in state.marks
        viewModelScope.launch { runCatching { journalRepository.setMark(wakeDate, mark, on) } }
    }

    /** Morning feel for this night's morning, saved over any earlier answer while it can still change. */
    fun onFeel(feel: MorningFeel) {
        if (window.value?.feelEditable != true) return
        viewModelScope.launch { runCatching { journalRepository.setFeel(wakeDate, feel, FeelSource.REVIEW) } }
    }
}

/** Starts with [default] and falls back to it on failure, so one broken extra never hides the night. */
private fun <T> Flow<T>.orDefault(default: T): Flow<T> = onStart { emit(default) }.catch { emit(default) }
