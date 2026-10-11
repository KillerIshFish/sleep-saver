package com.sleepsaver.feature.tonight.tonight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.hooks.NightState
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.DebtPreviewInputs
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.JournalRepository
import com.sleepsaver.core.data.repository.NightLogRepository
import com.sleepsaver.core.data.repository.NightStepsRepository
import com.sleepsaver.core.data.repository.PatternsRepository
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.domain.alarm.AlarmEvent
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.AnchorSource
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.TomorrowList
import com.sleepsaver.core.domain.nightsteps.NightStepsSession
import com.sleepsaver.core.domain.nightsteps.StepAdherence
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.feature.tonight.TonightWriter
import com.sleepsaver.feature.tonight.oneoff.OneOffContext
import com.sleepsaver.feature.tonight.oneoff.OneOffWrites
import com.sleepsaver.feature.tonight.safely
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

/** One-off messages for the Tonight tab's snackbar. */
internal sealed interface TonightEvent {
    /** "Anchor dropped" with Undo for `anchor.undoWindow`. */
    data class AnchorDropped(val anchorId: Long) : TonightEvent

    /** "Couldn't save that. Try again." */
    data object AnchorFailed : TonightEvent

    /** A catnap was refused: "A ring is already set or sounding". */
    data object RingRefused : TonightEvent
}

/**
 * The Tonight tab (and the Night screen, which shows its night part): insights, the week plan and planned alarms,
 * tonight's Night steps, marks and lists, the night state and the clock rolled into one [TonightUiState] by
 * [tonightUiState]. Plan and journal writes go through [TonightWriter]; the alarm machinery through
 * [NightController], whose calls return at once.
 */
@HiltViewModel
internal class TonightViewModel @Inject constructor(
    insights: InsightsRepository,
    private val schedule: ScheduleRepository,
    nightSteps: NightStepsRepository,
    private val journal: JournalRepository,
    nightLog: NightLogRepository,
    patterns: PatternsRepository,
    private val nightController: NightController,
    settings: SettingsRepository,
    private val time: TimeProvider,
    private val writer: TonightWriter,
) : ViewModel() {

    private val config = SleepConfig.DEFAULT

    private val local = MutableStateFlow(
        TonightLocal(
            hushColor = DEFAULT_HUSH_COLOR,
            hushTimer = config.hush.defaultTimer,
            hushVolume = config.hush.defaultVolume,
            catnapLength = config.napIdealLength.takeIf { it in config.catnap.lengths } ?: config.catnap.lengths.first(),
            endedAt = null,
        ),
    )

    private val eventChannel = Channel<TonightEvent>(Channel.BUFFERED)
    val events: Flow<TonightEvent> = eventChannel.receiveAsFlow()

    private val oneOffs = OneOffWrites(schedule, writer, viewModelScope) { today() }

    val uiState: StateFlow<TonightUiState> = run {
        val ticks = time.minuteTicks
        val today = ticks.map { it.atZone(time.zone()).toLocalDate() }.distinctUntilChanged()
        val todayEvent = today.flatMapLatest { nightLog.alarmEvent(it) }
        val lastNightList = today.flatMapLatest { journal.tomorrowList(it.minusDays(1)) }
        val plan = combine(schedule.weekPlan, schedule.oneOffs, schedule.upcomingAlarms, ::Triple)
        val journalPart = combine(nightSteps.tonight, nightSteps.adherence, journal.marksByDate, lastNightList, todayEvent, ::JournalPart)
        val base = combine(insights.state, patterns.debtPreviewInputs, settings.settings, nightController.state, ticks, ::BasePart)
        val screen = combine(local, nightController.state.map { it.endedAt }.distinctUntilChanged()) { l, endedAt -> l.copy(endedAt = endedAt) }
        combine(base, plan, journalPart, screen) { b, (week, oneOffs, alarms), j, l ->
            tonightUiState(
                TonightInputs(
                    insights = b.insights,
                    debtInputs = b.debtInputs,
                    settings = b.settings,
                    night = b.night,
                    now = b.now,
                    zone = time.zone(),
                    week = week,
                    oneOffs = oneOffs,
                    alarms = alarms,
                    session = j.session,
                    adherence = j.adherence,
                    marks = j.marks,
                    lastNightList = j.lastNightList,
                    todayEvent = j.todayEvent,
                    local = l,
                ),
                config,
            )
        }
            .catch { emit(TonightUiState.Unavailable) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TonightUiState.Loading)
    }

    /** What the one-off sheet needs to pre-fill itself. */
    val oneOffContext: StateFlow<OneOffContext?> = OneOffContext.flow(schedule, time)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---------------------------------------------------------------- Alarm card and one-offs

    fun onDayOff(date: LocalDate) = oneOffs.dayOff(date)

    fun onClearOverride(date: LocalDate) = oneOffs.clear(date)

    fun onJustOnce(date: LocalDate, time: LocalTime, mode: AlarmMode) = oneOffs.justOnce(date, time, mode)

    fun onPause(until: LocalDate) = oneOffs.pause(until)

    fun onResumeAlarms() = oneOffs.resume()

    // ---------------------------------------------------------------- Evening

    fun onMark(mark: Mark, on: Boolean) {
        val wakeDate = (uiState.value as? TonightUiState.Ready)?.wakeDate ?: return
        viewModelScope.launch { writer.write("Saving mark $mark") { journal.setMark(wakeDate, mark, on) } }
    }

    /** Drop anchor from the "Settling in" sheet; the snackbar offers Undo, or says it couldn't be saved. */
    fun onDropAnchor(wholeNightLog: Boolean) {
        viewModelScope.launch {
            var anchorId: Long? = null
            safely("Dropping anchor") { anchorId = nightController.dropAnchor(AnchorSource.TONIGHT, wholeNightLog) }
            eventChannel.send(anchorId?.let { TonightEvent.AnchorDropped(it) } ?: TonightEvent.AnchorFailed)
        }
    }

    fun onUndoAnchor(anchorId: Long) {
        viewModelScope.launch { safely("Undoing anchor") { nightController.undoAnchor(anchorId) } }
    }

    // ---------------------------------------------------------------- Night

    /** "I'm up", confirmed: ends the night state and the movement log; the alarm stays set. */
    fun onEndNight() {
        nightController.endNight()
    }

    fun onResumeLog() = nightController.resumeMovementLog()

    // ---------------------------------------------------------------- Hush

    fun onHushColor(color: HushColor) = changeHush { it.copy(hushColor = color) }

    fun onHushTimer(timer: Duration?) = changeHush { it.copy(hushTimer = timer) }

    fun onHushVolume(volume: Float) {
        local.update { it.copy(hushVolume = volume) }
        if (nightController.state.value.hush != null) nightController.setHushVolume(volume)
    }

    fun onHushPlay() {
        val choice = local.value
        val untilWindow = (uiState.value as? TonightUiState.Ready)?.hush?.untilWindowAvailable == true
        // "Until my window" with no alarm to wait for falls back to the default timer.
        val timer = choice.hushTimer ?: if (untilWindow) null else config.hush.defaultTimer
        nightController.startHush(choice.hushColor, timer, choice.hushVolume)
    }

    fun onHushStop() = nightController.stopHush()

    /** Changes a Hush choice; while Hush plays, starts it again with the change (from what is playing). */
    private fun changeHush(change: (TonightLocal) -> TonightLocal) {
        val playing = nightController.state.value.hush
        val base = local.value.let { current ->
            if (playing == null) current else current.copy(hushColor = playing.color, hushTimer = playing.timer, hushVolume = playing.volume)
        }
        val next = change(base)
        local.value = next
        if (playing != null) nightController.startHush(next.hushColor, next.hushTimer, next.hushVolume)
    }

    // ---------------------------------------------------------------- Catnap

    fun onCatnapLength(length: Duration) = local.update { it.copy(catnapLength = length) }

    fun onStartCatnap() {
        if (!nightController.startCatnap(local.value.catnapLength)) eventChannel.trySend(TonightEvent.RingRefused)
    }

    fun onCancelCatnap() = nightController.cancelCatnap()

    private fun today(): LocalDate = time.now().atZone(time.zone()).toLocalDate()

    private data class BasePart(
        val insights: InsightsState,
        val debtInputs: DebtPreviewInputs?,
        val settings: AppSettings,
        val night: NightState,
        val now: Instant,
    )

    private data class JournalPart(
        val session: NightStepsSession?,
        val adherence: StepAdherence?,
        val marks: Map<LocalDate, Set<Mark>>,
        val lastNightList: TomorrowList?,
        val todayEvent: AlarmEvent?,
    )

    private companion object {
        /** Hush starts on pink ("Softer, like steady rain") until the user picks another colour. */
        val DEFAULT_HUSH_COLOR = HushColor.PINK
    }
}
