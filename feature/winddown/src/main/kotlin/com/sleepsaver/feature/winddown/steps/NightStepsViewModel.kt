package com.sleepsaver.feature.winddown.steps

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.hooks.NightState
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.JournalRepository
import com.sleepsaver.core.data.repository.NightStepsRepository
import com.sleepsaver.core.data.repository.PatternsRepository
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.AnchorSource
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.nightsteps.NightStepsSession
import com.sleepsaver.core.domain.nightsteps.StepAdherence
import com.sleepsaver.core.domain.nightsteps.StepSuggestion
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.domain.patterns.MarkEffect
import com.sleepsaver.core.domain.patterns.PatternLabel
import com.sleepsaver.core.domain.schedule.FocusNight
import com.sleepsaver.core.domain.schedule.NightRules
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.ui.components.HushControlsActions
import com.sleepsaver.core.ui.components.HushControlsState
import com.sleepsaver.feature.winddown.HushChoice
import com.sleepsaver.feature.winddown.HushPicker
import com.sleepsaver.feature.winddown.LOG_TAG
import com.sleepsaver.feature.winddown.WinddownNight
import com.sleepsaver.feature.winddown.WinddownNightSource
import com.sleepsaver.feature.winddown.WinddownWriter
import com.sleepsaver.feature.winddown.hushControlsState
import com.sleepsaver.feature.winddown.library.addToSteps
import com.sleepsaver.feature.winddown.untilWindowAvailable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** What "How your Night steps evenings compare" can say. */
internal enum class CompareStatus {
    /** At least one Clear or Possible pattern to show. */
    ROWS,

    /** Fewer than marksMinPerSide evenings with or without steps. */
    NOT_ENOUGH,

    /** Enough evenings on both sides, but no difference stands out. */
    NO_PATTERN,
}

internal sealed interface NightStepsUiState {
    data object Loading : NightStepsUiState

    /** Tonight's steps couldn't be read (or there is no evening to time them from). */
    data object Unavailable : NightStepsUiState

    data class Ready(
        /** The evening in focus ([com.sleepsaver.feature.winddown.winddownFocus]): last night's until it ends. */
        val session: NightStepsSession,
        val zone: ZoneId,
        /** No-clock night (§5.0): no time of day anywhere on the screen. */
        val night: Boolean,
        /** The anchor is dropped for this night (its wake date is [wakeDate]). */
        val anchored: Boolean,
        val adherence: StepAdherence,
        /** At most `steps.suggestionMax`. */
        val suggestions: List<StepSuggestion>,
        /** Tonight's marks (wake date = nightOf + 1). */
        val marks: Set<Mark>,
        /** The NIGHT_STEPS_DONE patterns worth showing (Clear and Possible), strongest first. */
        val compare: List<MarkEffect>,
        val compareStatus: CompareStatus,
        /** Tonight's evening has its own list (else the every-evening list). */
        val ownList: Boolean,
        val hush: HushControlsState,
        /** The quiet streak shows from this many evenings. */
        val streakShowFrom: Int = SleepConfig.DEFAULT.steps.streakShowFrom,
    ) : NightStepsUiState {
        val day: DayOfWeek get() = session.nightOf.dayOfWeek
        val wakeDate: LocalDate get() = session.nightOf.plusDays(1)
    }
}

/** One-off outcomes the screen reacts to. */
internal enum class NightStepsEvent { ANCHOR_DROPPED, ANCHOR_FAILED }

/**
 * Builds the Night steps screen for the [session] of the night in [focus] ([com.sleepsaver.feature.winddown.winddownFocus]).
 * Night mode (§5.0) is that night's no-clock time, the rule Tonight, Sleep home and Energy use. [nightState]'s anchor
 * shows as dropped only when it was dropped for that night, so last night's anchor never darkens the day. Hush can run
 * "Until my window" only when [nextAlarm] (the one armed) is that night's.
 */
internal fun nightStepsUiState(
    session: NightStepsSession?,
    focus: FocusNight,
    adherence: StepAdherence,
    suggestions: List<StepSuggestion>,
    effects: List<MarkEffect>,
    marks: Set<Mark>,
    week: WeekPlan,
    nextAlarm: PlannedAlarm?,
    nightState: NightState,
    hush: HushChoice,
    zone: ZoneId,
    config: SleepConfig = SleepConfig.DEFAULT,
): NightStepsUiState {
    if (session == null) return NightStepsUiState.Unavailable
    val night = focus.isNight
    val wakeDate = session.nightOf.plusDays(1)
    val anchored = nightState.night?.let { NightRules.wakeDateOf(it.anchoredAt, it.offset, config) == wakeDate } == true
    val stepsEffects = effects.filter { it.mark == Mark.NIGHT_STEPS_DONE }
    val shown = stepsEffects.filter { it.label == PatternLabel.CLEAR || it.label == PatternLabel.POSSIBLE }
    val status = when {
        shown.isNotEmpty() -> CompareStatus.ROWS
        stepsEffects.all { it.label == PatternLabel.NOT_ENOUGH } -> CompareStatus.NOT_ENOUGH
        else -> CompareStatus.NO_PATTERN
    }
    return NightStepsUiState.Ready(
        session = session,
        zone = zone,
        night = night,
        anchored = anchored,
        adherence = adherence,
        suggestions = suggestions.take(config.steps.suggestionMax),
        marks = marks,
        compare = shown,
        compareStatus = status,
        ownList = week.day(session.nightOf.dayOfWeek).evening.routine != null,
        hush = hushControlsState(hush, nightState.hush, untilWindowAvailable(nextAlarm, wakeDate), night = night, config = config),
        streakShowFrom = config.steps.streakShowFrom,
    )
}

/**
 * Night steps: the checklist of the evening in focus ([WinddownNightSource]: tonight's, or last night's until it ends)
 * with tracking, suggestions, marks, the Night steps patterns and Drop anchor. Optional inputs (tracking, suggestions,
 * patterns, marks, the week plan, the alarm) fall back to empty when they fail, so only the steps themselves can make
 * the screen Unavailable.
 */
@HiltViewModel
internal class NightStepsViewModel @Inject constructor(
    private val nightSteps: NightStepsRepository,
    private val journal: JournalRepository,
    patterns: PatternsRepository,
    private val schedule: ScheduleRepository,
    nightSource: WinddownNightSource,
    private val nightController: NightController,
    private val time: TimeProvider,
    private val writer: WinddownWriter,
) : ViewModel() {

    private val config = SleepConfig.DEFAULT

    private val hushPicker = HushPicker(
        nightController = nightController,
        untilWindowAvailable = { (uiState.value as? NightStepsUiState.Ready)?.hush?.untilWindowAvailable == true },
        config = config,
        onPlay = { checkOff(Technique.HUSH) },
    )

    /** Hush's controls on this screen (the step's "Start" sheet). */
    val hushActions: HushControlsActions get() = hushPicker.actions

    private val events = Channel<NightStepsEvent>(Channel.BUFFERED)

    /** One-off outcomes: anchor dropped (→ Tonight) or not saved (→ a snackbar). */
    val eventFlow: Flow<NightStepsEvent> = events.receiveAsFlow()

    private val local = combine(
        nightSteps.adherence.orElse(NoAdherence, "Adherence"),
        nightSteps.suggestions.orElse(emptyList(), "Suggestions"),
        patterns.markEffects.orElse(emptyList(), "Mark effects"),
        journal.marksByDate.orElse(emptyMap(), "Marks"),
    ) { adherence, suggestions, effects, marks -> Local(adherence, suggestions, effects, marks) }

    private val plan = combine(
        schedule.weekPlan.orElse(WeekPlan.DEFAULT, "Week plan"),
        schedule.nextAlarm.orElse(null, "Next alarm"),
    ) { week, nextAlarm -> Plan(week, nextAlarm) }

    private val moment = combine(nightController.state, hushPicker.choice) { night, hush -> Moment(night, hush) }

    // Tracking and suggestions are built from tonight's evening, so without one they may never arrive: a missing
    // session shows Unavailable at once instead of waiting for them. Until they arrive with a session, the screen keeps
    // what it shows (Loading, or the last state when it comes back to this screen).
    val uiState: StateFlow<NightStepsUiState> = combineTransform<WinddownNight, Local?, Plan, Moment, NightStepsUiState>(
        nightSource.night,
        local.onStart<Local?> { emit(null) },
        plan,
        moment,
    ) { night, local, plan, moment ->
        val session = night.session
        if (session == null) {
            emit(NightStepsUiState.Unavailable)
        } else if (local != null) {
            emit(
                nightStepsUiState(
                    session = session,
                    focus = night.focus,
                    adherence = local.adherence,
                    suggestions = local.suggestions,
                    effects = local.effects,
                    marks = local.marks[session.nightOf.plusDays(1)].orEmpty(),
                    week = plan.week,
                    nextAlarm = plan.nextAlarm,
                    nightState = moment.night,
                    hush = moment.hush,
                    zone = time.zone(),
                    config = config,
                ),
            )
        }
    }
        .catch<NightStepsUiState> {
            Log.w(LOG_TAG, "Loading Night steps failed", it)
            emit(NightStepsUiState.Unavailable)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NightStepsUiState.Loading)

    private val ready: NightStepsUiState.Ready? get() = uiState.value as? NightStepsUiState.Ready

    fun setDone(activityId: Long, done: Boolean) {
        val nightOf = ready?.session?.nightOf ?: return
        viewModelScope.launch { writer.save("Checking step $activityId") { nightSteps.setDone(nightOf, activityId, done) } }
    }

    /**
     * "Worth a try" Add. [label] is the technique's title, resolved in composition (never inside the click lambda). On
     * an evening with its own list the step goes on that list, the one tonight's checklist is built from ([addToSteps]).
     */
    fun addTechnique(technique: Technique, label: String) {
        val day = ready?.day
        viewModelScope.launch { writer.save("Adding $technique") { addToSteps(technique, label, day, nightSteps, schedule) } }
    }

    fun toggleMark(mark: Mark) {
        val state = ready ?: return
        val on = mark !in state.marks
        viewModelScope.launch { writer.save("Marking $mark") { journal.setMark(state.wakeDate, mark, on) } }
    }

    /** Through the writer (app scope): leaving the screen at that instant can't cancel the anchor half-way. */
    fun dropAnchor(wholeNightLog: Boolean) {
        viewModelScope.launch {
            val id = writer.saveFor("Dropping anchor") {
                Result.success(nightController.dropAnchor(AnchorSource.NIGHT_STEPS, wholeNightLog))
            }?.getOrNull()
            events.send(if (id != null) NightStepsEvent.ANCHOR_DROPPED else NightStepsEvent.ANCHOR_FAILED)
        }
    }

    /** Ticks [technique]'s step for tonight (Hush started from its step). */
    private fun checkOff(technique: Technique) {
        val state = ready ?: return
        val step = state.session.steps.firstOrNull { it.activity.technique == technique && !it.done } ?: return
        setDone(step.activity.id, true)
    }

    private fun <T> Flow<T>.orElse(fallback: T, what: String): Flow<T> = catch {
        Log.w(LOG_TAG, "$what failed", it)
        emit(fallback)
    }

    private data class Local(
        val adherence: StepAdherence,
        val suggestions: List<StepSuggestion>,
        val effects: List<MarkEffect>,
        /** Stored marks by wake date. */
        val marks: Map<LocalDate, Set<Mark>>,
    )

    private data class Plan(val week: WeekPlan, val nextAlarm: PlannedAlarm?)

    private data class Moment(val night: NightState, val hush: HushChoice)

    private companion object {
        /** Tracking that couldn't be read: no evenings, so the tracking card stays hidden. */
        val NoAdherence = StepAdherence(evenings = emptyList(), doneRecent = 0, countedRecent = 0, quietStreak = 0)
    }
}
