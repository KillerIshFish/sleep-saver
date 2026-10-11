package com.sleepsaver.feature.tonight.check

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.NightLogRepository
import com.sleepsaver.core.domain.alarm.AlarmEvent
import com.sleepsaver.core.domain.alarm.AlarmTuning
import com.sleepsaver.core.domain.alarm.TuningReason
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.feature.tonight.NightClock
import com.sleepsaver.feature.tonight.NightClockSource
import com.sleepsaver.feature.tonight.TestRingEvent
import com.sleepsaver.feature.tonight.TonightWriter
import com.sleepsaver.feature.tonight.requestTestRing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** What Alarm check shows beyond the phone's checks (those are read on screen, see AlarmAccess). */
internal sealed interface AlarmCheckUiState {
    data object Loading : AlarmCheckUiState

    /**
     * The [lastTest] ring ("Last test: rang at 9:14 PM on Oct 6") and the restless trigger's [tuning] (a RESET row
     * or none reads "Standard").
     */
    @Immutable
    data class Ready(val lastTest: AlarmEvent?, val tuning: AlarmTuning?, val today: LocalDate, val zone: ZoneId) : AlarmCheckUiState {
        /** Tuned up or down (not reset): show it and offer "Reset to default". */
        val tuned: Boolean get() = tuning != null && tuning.reason != TuningReason.RESET
    }
}

/** Alarm check: the last test ring, the restless trigger's tuning, the test ring itself and the tuning reset. */
@HiltViewModel
internal class AlarmCheckViewModel @Inject constructor(
    private val nightLog: NightLogRepository,
    private val nightController: NightController,
    nightClock: NightClockSource,
    private val time: TimeProvider,
    private val writer: TonightWriter,
) : ViewModel() {

    private val config = SleepConfig.DEFAULT
    private val eventChannel = Channel<TestRingEvent>(Channel.BUFFERED)

    /** Test-ring answers for the snackbar. */
    val events: Flow<TestRingEvent> = eventChannel.receiveAsFlow()

    /** Latest night clock, so the test-ring message knows whether it may show a time. */
    private var clock: NightClock? = null

    val uiState: StateFlow<AlarmCheckUiState> = combine(
        nightLog.lastTestRing.onStart<AlarmEvent?> { emit(null) }.catch { emit(null) },
        nightLog.tuning.onStart<AlarmTuning?> { emit(null) }.catch { emit(null) },
        nightClock.clock.onStart<NightClock?> { emit(null) }.catch { emit(null) },
    ) { lastTest, tuning, clockNow ->
        clock = clockNow
        AlarmCheckUiState.Ready(lastTest, tuning, clockNow?.today ?: time.now().atZone(time.zone()).toLocalDate(), time.zone())
            as AlarmCheckUiState
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlarmCheckUiState.Loading)

    /** "Test ring in 1 minute" (after the notification check). */
    fun onTestRing() {
        eventChannel.trySend(nightController.requestTestRing(time.now(), time.zone(), night = clock?.isNight == true, config))
    }

    /** "Reset to default": the restless trigger back to standard (stored as a RESET row). */
    fun onResetTuning() {
        viewModelScope.launch { writer.write("Resetting the restless trigger") { nightLog.resetTuning() } }
    }
}
