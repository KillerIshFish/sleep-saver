package com.sleepsaver.feature.tonight.day

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.DebtPreviewInputs
import com.sleepsaver.core.data.repository.NightStepsRepository
import com.sleepsaver.core.data.repository.PatternsRepository
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.domain.model.TimingSource
import com.sleepsaver.core.domain.nightsteps.routineFor
import com.sleepsaver.core.domain.patterns.DebtPreview
import com.sleepsaver.core.domain.patterns.DebtProjector
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.AlarmSettings
import com.sleepsaver.core.domain.schedule.AlarmTone
import com.sleepsaver.core.domain.schedule.DayPart
import com.sleepsaver.core.domain.schedule.DayPlan
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.ScheduleTimes
import com.sleepsaver.core.domain.schedule.SnoozeStyle
import com.sleepsaver.core.domain.schedule.WakeStyle
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.feature.tonight.TonightWriter
import com.sleepsaver.feature.tonight.navigation.DayPlanRoute
import com.sleepsaver.feature.tonight.roundTo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/** What a day's plan shows. */
internal sealed interface DayPlanUiState {
    data object Loading : DayPlanUiState

    data object Unavailable : DayPlanUiState

    /**
     * [day]'s [plan] (with scrubs not yet saved applied), the days with an alarm (dots on the tabs), the usual wake
     * and where it comes from ([usualSource] null while loading), the Debt previews for [morningDate] (the next
     * [day]) and for the morning after [eveningDate] (the next [day] evening, tonight included), the Settings-rule
     * bedtime of that evening, and its step count. [initialPart] is where the screen opened.
     */
    @Immutable
    data class Ready(
        val day: DayOfWeek,
        val initialPart: DayPart,
        val today: LocalDate,
        val zone: ZoneId,
        val plan: DayPlan,
        val alarmDays: Set<DayOfWeek>,
        val usualWake: LocalTime?,
        val usualSource: TimingSource?,
        val morningDate: LocalDate,
        val morningPreview: DebtPreview?,
        val eveningDate: LocalDate,
        val ruleBedtime: LocalTime?,
        val eveningPreview: DebtPreview?,
        val stepsCount: Int,
    ) : DayPlanUiState {
        val alarmOn: Boolean get() = plan.morning.alarm.mode != AlarmMode.OFF

        /** The wake time the window and snooze captions talk about: the set time, else the usual one. */
        val shownWake: LocalTime? get() = plan.morning.wakeTime ?: usualWake

        /** The evening's bedtime: the set one, else the Settings rule's. */
        val shownBedtime: LocalTime? get() = plan.evening.bedtime ?: ruleBedtime

        /** Whether copying [parts] onto [to] makes a day ring that didn't: this day's alarm onto a day without one. */
        fun copyArms(to: Set<DayOfWeek>, parts: Set<DayPart>): Boolean =
            DayPart.MORNING in parts && alarmOn && (to - day).any { it !in alarmDays }
    }
}

/** Scrubbed values not saved yet (they save once the scrubber settles). */
internal data class PendingScrubs(
    val day: DayOfWeek? = null,
    val wake: LocalTime? = null,
    val bedtime: LocalTime? = null,
    val volume: Float? = null,
) {
    fun applyTo(plan: DayPlan): DayPlan {
        if (plan.day != day) return plan
        val alarm = plan.morning.alarm.let { if (volume != null) it.copy(volume = volume) else it }
        return plan.copy(
            morning = plan.morning.copy(wakeTime = wake ?: plan.morning.wakeTime, alarm = alarm),
            evening = plan.evening.copy(bedtime = bedtime ?: plan.evening.bedtime),
        )
    }
}

/** Builds the day plan's state; pure. */
internal fun dayPlanState(
    day: DayOfWeek,
    initialPart: DayPart,
    week: WeekPlan,
    oneOffs: OneOffs,
    usualWake: Map<DayOfWeek, LocalTime>,
    activities: List<RoutineActivity>,
    inputs: DebtPreviewInputs?,
    pending: PendingScrubs,
    now: Instant,
    zone: ZoneId,
    config: SleepConfig = SleepConfig.DEFAULT,
): DayPlanUiState.Ready {
    val today = now.atZone(zone).toLocalDate()
    val plan = pending.applyTo(week.day(day))
    val shownWeek = week.with(plan)
    val morningDate = (1L..7L).map { today.plusDays(it) }.first { it.dayOfWeek == day }
    val eveningDate = (0L..6L).map { today.plusDays(it) }.first { it.dayOfWeek == day }
    val times = ScheduleTimes(config)
    val projector = DebtProjector(config)
    val settings = inputs?.settings?.copy(week = shownWeek, oneOffs = oneOffs)
    val ruleBedtime = if (inputs != null && settings != null) {
        val withoutSetBedtime = settings.copy(week = shownWeek.with(plan.copy(evening = plan.evening.copy(bedtime = null))))
        times.bedtime(eveningDate, inputs.zone, inputs.nights, settings.sleepNeed, withoutSetBedtime.timing).first
            .atZone(inputs.zone).toLocalTime()
    } else {
        null
    }
    return DayPlanUiState.Ready(
        day = day,
        initialPart = initialPart,
        today = today,
        zone = zone,
        plan = plan,
        alarmDays = shownWeek.days.filter { it.morning.alarm.mode != AlarmMode.OFF }.mapTo(LinkedHashSet()) { it.day },
        usualWake = usualWake[day],
        usualSource = inputs?.let { times.usualWake(morningDate, it.nights, it.settings.timing).second },
        morningDate = morningDate,
        morningPreview = if (inputs != null && settings != null) {
            projector.project(inputs.nights, settings, now, inputs.zone, morningDate, wakeOverride = pending.wake?.takeIf { pending.day == day })
        } else {
            null
        },
        eveningDate = eveningDate,
        ruleBedtime = ruleBedtime,
        eveningPreview = if (inputs != null && settings != null) {
            projector.project(
                inputs.nights,
                settings,
                now,
                inputs.zone,
                eveningDate.plusDays(1),
                bedtimeOverride = pending.bedtime?.takeIf { pending.day == day },
            )
        } else {
            null
        },
        stepsCount = routineFor(plan.evening, activities).size,
    )
}

/**
 * One day's plan, morning and evening. Each change is one `ScheduleRepository.updateDay(day) { … }` through the
 * serial [TonightWriter]; scrubbers (wake time, bedtime, volume) save once they settle ([SCRUB_SETTLE]), and a value
 * still pending is saved when the screen goes away or another day is picked.
 */
@HiltViewModel
internal class DayPlanViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val schedule: ScheduleRepository,
    nightSteps: NightStepsRepository,
    patterns: PatternsRepository,
    private val nightController: NightController,
    private val time: TimeProvider,
    private val writer: TonightWriter,
) : ViewModel() {

    private val config = SleepConfig.DEFAULT
    private val route = savedStateHandle.toRoute<DayPlanRoute>()
    private val initialPart = DayPart.entries.firstOrNull { it.name == route.part } ?: DayPart.MORNING
    private val selectedDay = savedStateHandle.getStateFlow(KEY_DAY, route.day.coerceIn(1, 7))
    private val pending = MutableStateFlow(PendingScrubs())
    private val scrubJobs = mutableMapOf<String, Job>()

    val uiState: StateFlow<DayPlanUiState> = combine(
        combine(schedule.weekPlan, schedule.oneOffs, schedule.usualWake, ::Triple),
        nightSteps.activities,
        patterns.debtPreviewInputs,
        selectedDay.map { DayOfWeek.of(it) },
        combine(pending, time.minuteTicks, ::Pair),
    ) { (week, oneOffs, usual), activities, inputs, day, (scrubs, now) ->
        dayPlanState(day, initialPart, week, oneOffs, usual, activities, inputs, scrubs, now, time.zone(), config) as DayPlanUiState
    }
        .catch { emit(DayPlanUiState.Unavailable) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayPlanUiState.Loading)

    private fun day(): DayOfWeek = DayOfWeek.of(selectedDay.value)

    private fun ready(): DayPlanUiState.Ready? = uiState.value as? DayPlanUiState.Ready

    fun onSelectDay(day: DayOfWeek) {
        flushScrubs()
        savedStateHandle[KEY_DAY] = day.value
    }

    // ---------------------------------------------------------------- Morning

    /** "Usual wake time" (only offered while the alarm is off). */
    fun onWakeUsual() = update("wake time") { it.copy(morning = it.morning.copy(wakeTime = null)) }

    /** "Set a time": starts from the usual wake, rounded. */
    fun onWakeSet() {
        val start = (ready()?.usualWake ?: DEFAULT_WAKE).roundTo(config.week.timeRounding)
        update("wake time") { plan -> plan.copy(morning = plan.morning.copy(wakeTime = plan.morning.wakeTime ?: start)) }
    }

    fun onWakeTime(time: LocalTime) = scrub(
        key = "wake",
        show = { it.copy(wake = time) },
        settled = { if (it.wake == time) it.copy(wake = null) else it },
    ) { plan -> plan.copy(morning = plan.morning.copy(wakeTime = time)) }

    /** The alarm mode; switching it on gets a set time from the repository (the usual wake, rounded). */
    fun onAlarmMode(mode: AlarmMode) = update("alarm mode") { it.copy(morning = it.morning.copy(alarm = it.morning.alarm.copy(mode = mode))) }

    fun onWindow(window: Duration) = updateAlarm("window") { it.copy(window = window) }

    fun onStyle(style: WakeStyle) = updateAlarm("wake-up style") { it.copy(style = style) }

    fun onTone(tone: AlarmTone) = updateAlarm("sound") { it.copy(tone = tone) }

    /** ▶ next to a sound: plays a few seconds of it in the day's style and volume. */
    fun onPreviewTone(tone: AlarmTone) {
        val alarm = ready()?.plan?.morning?.alarm ?: return
        nightController.previewTone(tone, alarm.style, alarm.volume)
    }

    fun stopPreview() = nightController.stopPreview()

    fun onVolume(volume: Float) {
        val day = day()
        pending.update { (if (it.day == day) it else PendingScrubs(day = day)).copy(volume = volume) }
    }

    /** The volume slider was let go: save what it shows. */
    fun onVolumeDone() {
        val scrubs = pending.value
        val volume = scrubs.volume ?: return
        val day = scrubs.day ?: return
        viewModelScope.launch {
            writer.write("Saving $day volume") { schedule.updateDay(day) { it.withAlarm { a -> a.copy(volume = volume) } } }
            pending.update { if (it.volume == volume) it.copy(volume = null) else it }
        }
    }

    fun onSnoozeStyle(style: SnoozeStyle) = updateAlarm("snooze") { it.copy(snooze = it.snooze.copy(style = style)) }

    fun onFixedLength(length: Duration) = updateAlarm("snooze length") { it.copy(snooze = it.snooze.copy(fixedLength = length)) }

    fun onFixedCount(count: Int) = updateAlarm("snooze count") { it.copy(snooze = it.snooze.copy(fixedCount = count)) }

    fun onOverrun(overrun: Duration) = updateAlarm("snooze overrun") { it.copy(snooze = it.snooze.copy(overrun = overrun)) }

    // ---------------------------------------------------------------- Evening

    fun onBedtimeRule() = update("bedtime") { it.copy(evening = it.evening.copy(bedtime = null)) }

    /** "Set a time": starts from the Settings rule's bedtime, rounded. */
    fun onBedtimeSet() {
        val start = (ready()?.ruleBedtime ?: DEFAULT_BEDTIME).roundTo(config.week.timeRounding)
        update("bedtime") { plan -> plan.copy(evening = plan.evening.copy(bedtime = plan.evening.bedtime ?: start)) }
    }

    fun onBedtime(time: LocalTime) = scrub(
        key = "bedtime",
        show = { it.copy(bedtime = time) },
        settled = { if (it.bedtime == time) it.copy(bedtime = null) else it },
    ) { plan -> plan.copy(evening = plan.evening.copy(bedtime = time)) }

    fun onNightSteps(on: Boolean) = update("Night steps") { it.copy(evening = it.evening.copy(nightStepsOn = on)) }

    /** "Night steps start" N minutes before bedtime = the wind-down length + the melatonin half-width. */
    fun onStepsStart(minutesBeforeBedtime: Int) {
        val length = Duration.ofMinutes(minutesBeforeBedtime.toLong()) - config.melatoninHalfWidth
        update("Night steps start") { it.copy(evening = it.evening.copy(windDownLength = length)) }
    }

    fun onCopy(to: Set<DayOfWeek>, parts: Set<DayPart>) {
        val from = day()
        val targets = to - from
        if (targets.isEmpty() || parts.isEmpty()) return
        flushScrubs()
        viewModelScope.launch { writer.write("Copying $from") { schedule.copyDay(from, targets, parts) } }
    }

    override fun onCleared() {
        flushScrubs()
        super.onCleared()
    }

    // ---------------------------------------------------------------- Writes

    private fun update(what: String, change: (DayPlan) -> DayPlan) {
        val day = day()
        viewModelScope.launch { writer.write("Saving $day $what") { schedule.updateDay(day, change) } }
    }

    private fun updateAlarm(what: String, change: (AlarmSettings) -> AlarmSettings) = update(what) { it.withAlarm(change) }

    /**
     * Shows [show] at once and saves [change] once the scrubber has been still for [SCRUB_SETTLE]; then [settled]
     * drops the pending value, unless the scrubber moved on meanwhile.
     */
    private fun scrub(
        key: String,
        show: (PendingScrubs) -> PendingScrubs,
        settled: (PendingScrubs) -> PendingScrubs,
        change: (DayPlan) -> DayPlan,
    ) {
        val day = day()
        pending.update { show(if (it.day == day) it else PendingScrubs(day = day)) }
        scrubJobs.remove(key)?.cancel()
        scrubJobs[key] = viewModelScope.launch {
            delay(SCRUB_SETTLE)
            scrubJobs.remove(key)
            writer.write("Saving $day $key") { schedule.updateDay(day, change) }
            pending.update { if (it.day == day) settled(it) else it }
        }
    }

    /** Saves whatever is still pending now (leaving, or switching days). */
    private fun flushScrubs() {
        scrubJobs.values.forEach { it.cancel() }
        scrubJobs.clear()
        val scrubs = pending.value
        val day = scrubs.day ?: return
        pending.value = PendingScrubs()
        if (scrubs.wake == null && scrubs.bedtime == null && scrubs.volume == null) return
        writer.launch("Saving $day scrubs") { schedule.updateDay(day) { scrubs.applyTo(it) } }
    }

    companion object {
        /** SavedStateHandle key: the day shown (DayOfWeek.value), changed by the day tabs. */
        const val KEY_DAY = "tonight.dayPlanDay"

        /** Scrubbers save once they have been still this long. */
        const val SCRUB_SETTLE = 400L

        private val DEFAULT_WAKE: LocalTime = LocalTime.of(7, 0)
        private val DEFAULT_BEDTIME: LocalTime = LocalTime.of(23, 0)
    }
}

private fun DayPlan.withAlarm(change: (AlarmSettings) -> AlarmSettings): DayPlan =
    copy(morning = morning.copy(alarm = change(morning.alarm)))
