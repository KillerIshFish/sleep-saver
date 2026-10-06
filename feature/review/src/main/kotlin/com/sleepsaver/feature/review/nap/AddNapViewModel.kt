package com.sleepsaver.feature.review.nap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.SleepRepository
import com.sleepsaver.core.domain.model.SleepBlock
import com.sleepsaver.feature.review.common.today
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

enum class NapDay { TODAY, YESTERDAY }

data class AddNapUiState(
    val today: LocalDate,
    val zone: ZoneId,
    val now: Instant,
    val day: NapDay,
    /** When the nap started (fell asleep). */
    val start: Instant,
    val minutes: Int,
    /** Naps already on the chosen day. */
    val naps: List<SleepBlock> = emptyList(),
    val saving: Boolean = false,
    /** The last save didn't go through: the form stays open with a note. */
    val saveFailed: Boolean = false,
    /** Saved: the screen closes. */
    val finished: Boolean = false,
) {
    val date: LocalDate get() = if (day == NapDay.TODAY) today else today.minusDays(1)
    val end: Instant get() = start.plus(Duration.ofMinutes(minutes.toLong()))

    /** The start scrubber stays on the chosen day and can't start a nap that would end after now. */
    val startMin: Instant get() = date.atStartOfDay(zone).toInstant()
    val startMax: Instant
        get() = minOf(
            date.plusDays(1).atStartOfDay(zone).toInstant().minus(Duration.ofMinutes(STEP.toLong())),
            now.minus(Duration.ofMinutes(MIN_MINUTES.toLong())),
        )

    val endsInFuture: Boolean get() = end.isAfter(now.plusSeconds(60))
    val canSave: Boolean get() = !endsInFuture && !saving && !finished

    companion object {
        const val MIN_MINUTES = 10
        const val MAX_MINUTES = 180
        const val DEFAULT_MINUTES = 20
        const val STEP = 5
    }
}

@HiltViewModel
class AddNapViewModel @Inject constructor(
    private val sleepRepository: SleepRepository,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val form = MutableStateFlow(initialForm())

    private val naps = form
        .map { it.date }
        .distinctUntilChanged()
        .flatMapLatest { date -> sleepRepository.night(date) }
        .map { night -> night?.naps.orEmpty() }
        .catch { emit(emptyList()) }

    val uiState: StateFlow<AddNapUiState> = combine(form, naps) { f, n -> f.copy(naps = n) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, form.value)

    init {
        // Keep "now" fresh while the form is open, so "ends after now" and the scrubber's limit stay right.
        viewModelScope.launch {
            runCatching { timeProvider.minuteTicks.collect { tick -> form.update { it.copy(now = tick) } } }
        }
    }

    /** Today/yesterday: keeps the clock time, moves the date. */
    fun onDayChange(day: NapDay) = form.update { f ->
        if (f.day == day || f.saving) return@update f
        val time = f.start.atZone(f.zone).toLocalTime()
        val moved = f.copy(day = day)
        moved.copy(start = moved.date.atTime(time).atZone(f.zone).toInstant().clampTo(moved))
    }

    fun onStartChange(start: Instant) = form.update { if (it.saving) it else it.copy(start = start, saveFailed = false) }

    fun onMinutesChange(minutes: Int) = form.update {
        if (it.saving) return@update it
        val snapped = minutes / AddNapUiState.STEP * AddNapUiState.STEP
        it.copy(minutes = snapped.coerceIn(AddNapUiState.MIN_MINUTES, AddNapUiState.MAX_MINUTES), saveFailed = false)
    }

    /** Saves the nap in the offset the device had when it started, then closes. A failed save keeps the form. */
    fun onSave() {
        val f = form.updateAndGet { it.copy(now = maxOf(it.now, timeProvider.now())) }
        if (!f.canSave) return
        form.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            val ok = runCatching { sleepRepository.addNap(f.start, f.end, f.zone.rules.getOffset(f.start)) }.isSuccess
            form.update { it.copy(saving = false, saveFailed = !ok, finished = ok) }
        }
    }

    fun onDeleteNap(id: Long) {
        viewModelScope.launch { runCatching { sleepRepository.deleteNap(id) } }
    }

    private fun initialForm(): AddNapUiState {
        val now = timeProvider.now()
        val zone = timeProvider.zone()
        val base = AddNapUiState(
            today = timeProvider.today(),
            zone = zone,
            now = now,
            day = NapDay.TODAY,
            start = now,
            minutes = AddNapUiState.DEFAULT_MINUTES,
        )
        // Most people log a nap right after it: default to one that just ended, on a 5-minute mark.
        val justEnded = now.minus(Duration.ofMinutes(AddNapUiState.DEFAULT_MINUTES.toLong()))
        val snapped = Instant.ofEpochSecond(Math.floorDiv(justEnded.epochSecond, 300L) * 300L)
        return base.copy(start = snapped.clampTo(base))
    }
}

private fun Instant.clampTo(state: AddNapUiState): Instant {
    val min = state.startMin
    val max = maxOf(state.startMax, min)
    return when {
        isBefore(min) -> min
        isAfter(max) -> max
        else -> this
    }
}
