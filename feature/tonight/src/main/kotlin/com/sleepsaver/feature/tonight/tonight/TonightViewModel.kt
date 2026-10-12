package com.sleepsaver.feature.tonight.tonight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.hooks.NightState
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.DebtPreviewInputs
import com.sleepsaver.core.data.repository.HushSettings
import com.sleepsaver.core.data.repository.HushSettingsRepository
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
import com.sleepsaver.core.domain.schedule.NightRules
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
 * [NightController], whose calls return at once. The Hush pick is the one every screen shares
 * ([HushSettingsRepository]).
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
    private val hushSettings: HushSettingsRepository,
    settings: SettingsRepository,
    private val time: TimeProvider,
    private val writer: TonightWriter,
) : ViewModel() {

    private val config = SleepConfig.DEFAULT

    private val local = MutableStateFlow(
        TonightLocal(
            // Replaced by the shared pick in uiState.
            hush = HushSettings(),
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
        val endedAt = nightController.state.map { it.endedAt }.distinctUntilChanged()
        val screen = combine(local, endedAt, hushSettings.settings) { l, endedAt, hush -> l.copy(hush = hush, endedAt = endedAt) }
        combine(base, plan, journalPart, screen) { b, (week, oneOffs, alarms), j, l ->
            tonightUiState(
                TonightInputs(
                    insights = b.insights,
                    debtInputs = b.debtInputs,
                    settings = b.settings,
                    night = b.night,
                    now = NightRules.clockNow(b.now, l.endedAt),
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

    fun onHushColor(color: HushColor) = changeHush { it.copy(color = color) }

    fun onHushTimer(timer: Duration?) = changeHush { it.copy(timer = timer) }

    fun onHushVolume(volume: Float) {
        val playing = nightController.state.value.hush
        if (playing == null) {
            hushSettings.update { it.copy(volume = volume) }
        } else {
            hushSettings.update { HushSettings(playing.color, playing.timer, volume) }
            nightController.setHushVolume(volume)
        }
    }

    /** Plays what the controls show: the pick, "Until my window" already back to the default timer without an alarm. */
    fun onHushPlay() {
        val hush = (uiState.value as? TonightUiState.Ready)?.hush ?: return
        nightController.startHush(hush.color, hush.timer, hush.volume)
    }

    fun onHushStop() = nightController.stopHush()

    /** Saves a Hush change for every screen; while Hush plays, starts it again with the change (from what is playing). */
    private fun changeHush(change: (HushSettings) -> HushSettings) {
        val playing = nightController.state.value.hush
        if (playing == null) {
            hushSettings.update(change)
        } else {
            val next = change(HushSettings(playing.color, playing.timer, playing.volume))
            hushSettings.update { next }
            nightController.startHush(next.color, next.timer, next.volume)
        }
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
}
