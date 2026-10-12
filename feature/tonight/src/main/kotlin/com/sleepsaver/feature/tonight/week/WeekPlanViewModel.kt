package com.sleepsaver.feature.tonight.week

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.DebtPreviewInputs
import com.sleepsaver.core.data.repository.NightStepsRepository
import com.sleepsaver.core.data.repository.PatternsRepository
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.domain.nightsteps.routineFor
import com.sleepsaver.core.domain.patterns.KeelResult
import com.sleepsaver.core.domain.patterns.PlanCheck
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.DayPlan
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.ScheduleTimes
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.domain.schedule.WeekPreset
import com.sleepsaver.feature.tonight.NightClock
import com.sleepsaver.feature.tonight.NightClockSource
import com.sleepsaver.feature.tonight.TestRingEvent
import com.sleepsaver.feature.tonight.TonightWriter
import com.sleepsaver.feature.tonight.oneoff.OneOffContext
import com.sleepsaver.feature.tonight.oneoff.OneOffWrites
import com.sleepsaver.feature.tonight.requestTestRing
import com.sleepsaver.feature.tonight.roundTo
import com.sleepsaver.feature.tonight.tonight.cardDate
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
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/** What the week plan shows. */
internal sealed interface WeekPlanUiState {
    data object Loading : WeekPlanUiState

    data object Unavailable : WeekPlanUiState

    /**
     * The plan: the [next] alarm for the subtitle ([night] = no-clock night, so no "Next"), the one-offs, seven
     * [rows], the [planCheck] (planned keel and its notices, unless [noticeDismissed]) with the measured [keel], and
     * the quick-setup time [presetWake] (Monday's usual wake). [firstWakeDate] is the first morning a one-off can
     * still change, the date Tonight's alarm card is on: today until this morning's alarm has rung (or, without one,
     * until last night ends), then tomorrow.
     */
    @Immutable
    data class Ready(
        val today: LocalDate,
        val zone: ZoneId,
        val next: PlannedAlarm?,
        val night: Boolean,
        val firstWakeDate: LocalDate,
        val oneOffs: OneOffs,
        val rows: List<WeekDayRow>,
        val planCheck: PlanCheck?,
        val keel: KeelResult?,
        val noticeDismissed: Boolean,
        val presetWake: LocalTime,
    ) : WeekPlanUiState
}

/**
 * One weekday: its [plan], its [usualWake] (shown when the morning has no set time), when its next evening's Night
 * steps start ([stepsFrom], null until the timing inputs load) and how many steps that evening has.
 */
@Immutable
internal data class WeekDayRow(
    val day: DayOfWeek,
    val plan: DayPlan,
    val usualWake: LocalTime?,
    val stepsFrom: Instant?,
    val steps: Int,
) {
    val alarmOn: Boolean get() = plan.morning.alarm.mode != AlarmMode.OFF
}

/** Builds the week plan's rows: per weekday, its next evening's Night steps start and step count. */
internal fun weekRows(
    week: WeekPlan,
    oneOffs: OneOffs,
    usualWake: Map<DayOfWeek, LocalTime>,
    activities: List<RoutineActivity>,
    inputs: DebtPreviewInputs?,
    today: LocalDate,
    config: SleepConfig = SleepConfig.DEFAULT,
): List<WeekDayRow> {
    val times = ScheduleTimes(config)
    val timing = inputs?.settings?.copy(week = week, oneOffs = oneOffs)?.timing
    return week.days.map { plan ->
        val stepsFrom = if (inputs != null && timing != null) {
            // This weekday's next evening, tonight included.
            val evening = (0L..6L).map { today.plusDays(it) }.first { it.dayOfWeek == plan.day }
            val bedtime = times.bedtime(evening, inputs.zone, inputs.nights, inputs.settings.sleepNeed, timing).first
            times.windDownStart(bedtime, plan.evening)
        } else {
            null
        }
        WeekDayRow(
            day = plan.day,
            plan = plan,
            usualWake = usualWake[plan.day],
            stepsFrom = stepsFrom,
            steps = routineFor(plan.evening, activities).size,
        )
    }
}

/**
 * The week plan: seven days with their alarm switches, one-offs, the planned keel and quick setup. Every write goes
 * through [TonightWriter]; the test ring through [NightController].
 */
@HiltViewModel
internal class WeekPlanViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val schedule: ScheduleRepository,
    nightSteps: NightStepsRepository,
    patterns: PatternsRepository,
    private val nightController: NightController,
    nightClock: NightClockSource,
    private val time: TimeProvider,
    private val writer: TonightWriter,
) : ViewModel() {

    private val config = SleepConfig.DEFAULT

    private val eventChannel = Channel<TestRingEvent>(Channel.BUFFERED)

    /** Test-ring answers for the snackbar. */
    val events: Flow<TestRingEvent> = eventChannel.receiveAsFlow()

    private val oneOffWrites = OneOffWrites(schedule, writer, viewModelScope) { today() }

    /** Latest night clock, so the test-ring message knows whether it may show a time. */
    private var clock: NightClock? = null

    val uiState: StateFlow<WeekPlanUiState> = run {
        val plan = combine(schedule.weekPlan, schedule.oneOffs, schedule.usualWake, schedule.upcomingAlarms, ::PlanPart)
        val checks = combine(
            patterns.planCheck,
            patterns.keel.catchToNull(),
            nightSteps.activities,
            patterns.debtPreviewInputs,
            savedStateHandle.getStateFlow(KEY_NOTICE_DISMISSED, false),
            ::CheckPart,
        )
        val night = nightClock.clock.catchToNull()
        combine(plan, checks, night) { p, c, clockNow ->
            clock = clockNow
            val today = clockNow?.today ?: today()
            WeekPlanUiState.Ready(
                today = today,
                zone = time.zone(),
                next = p.alarms.firstOrNull(),
                night = clockNow?.isNight == true,
                // Until the night clock is in, tomorrow: never a morning whose wake time may have passed.
                firstWakeDate = clockNow?.let { cardDate(it.today, it.wakeDate, it.now, p.alarms) } ?: today.plusDays(1),
                oneOffs = p.oneOffs,
                rows = weekRows(p.week, p.oneOffs, p.usualWake, c.activities, c.inputs, today, config),
                planCheck = c.planCheck,
                keel = c.keel,
                noticeDismissed = c.noticeDismissed,
                presetWake = (p.usualWake[DayOfWeek.MONDAY] ?: DEFAULT_PRESET_WAKE).roundTo(config.week.timeRounding),
            ) as WeekPlanUiState
        }
            .catch { emit(WeekPlanUiState.Unavailable) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeekPlanUiState.Loading)
    }

    val oneOffContext: StateFlow<OneOffContext?> = OneOffContext.flow(schedule, time)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The row's alarm switch. On uses the default mode; the repository fills the wake time with the usual one. */
    fun onAlarmSwitch(day: DayOfWeek, on: Boolean) {
        viewModelScope.launch {
            writer.write("Switching $day's alarm") {
                schedule.updateDay(day) { plan ->
                    val mode = if (on) config.alarm.defaultMode else AlarmMode.OFF
                    plan.copy(morning = plan.morning.copy(alarm = plan.morning.alarm.copy(mode = mode)))
                }
            }
        }
    }

    fun onApplyPreset(preset: WeekPreset, wake: LocalTime) {
        viewModelScope.launch { writer.write("Applying $preset") { schedule.applyPreset(preset, wake) } }
    }

    /** "Got it" on the plan notice: hidden for this editing session. */
    fun onDismissNotice() {
        savedStateHandle[KEY_NOTICE_DISMISSED] = true
    }

    /** "Test ring in 1 minute" (after the notification check). */
    fun onTestRing() {
        eventChannel.trySend(nightController.requestTestRing(time.now(), time.zone(), night = clock?.isNight == true, config))
    }

    fun onDayOff(date: LocalDate) = oneOffWrites.dayOff(date)

    fun onJustOnce(date: LocalDate, time: LocalTime, mode: AlarmMode) = oneOffWrites.justOnce(date, time, mode)

    fun onPause(until: LocalDate) = oneOffWrites.pause(until)

    fun onClearOverride(date: LocalDate) = oneOffWrites.clear(date)

    /** Whether undoing [date]'s one-off brings an alarm back (unknown counts as yes: the gate only asks). */
    fun undoArms(date: LocalDate): Boolean = oneOffContext.value?.clearingArms(date) ?: true

    fun onResumeAlarms() = oneOffWrites.resume()

    private fun today(): LocalDate = time.now().atZone(time.zone()).toLocalDate()

    private data class PlanPart(
        val week: WeekPlan,
        val oneOffs: OneOffs,
        val usualWake: Map<DayOfWeek, LocalTime>,
        val alarms: List<PlannedAlarm>,
    )

    private data class CheckPart(
        val planCheck: PlanCheck?,
        val keel: KeelResult?,
        val activities: List<RoutineActivity>,
        val inputs: DebtPreviewInputs?,
        val noticeDismissed: Boolean,
    )

    companion object {
        /** SavedStateHandle key: the plan notice was dismissed in this editing session. */
        const val KEY_NOTICE_DISMISSED = "tonight.planNoticeDismissed"

        /** Only if the usual wake times haven't loaded (the repository never fails them). */
        private val DEFAULT_PRESET_WAKE: LocalTime = LocalTime.of(7, 0)
    }
}

/** A secondary input that may be slow or fail: starts as null and stays null on failure, so the rest still shows. */
private fun <T> Flow<T>.catchToNull(): Flow<T?> = onStart<T?> { emit(null) }.catch { emit(null) }
