package com.sleepsaver.feature.tonight.tonight

import androidx.compose.runtime.Immutable
import com.sleepsaver.core.data.hooks.ActiveCatnap
import com.sleepsaver.core.data.hooks.NightState
import com.sleepsaver.core.data.hooks.SensingMode
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.DebtPreviewInputs
import com.sleepsaver.core.data.repository.HushSettings
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.domain.alarm.AlarmEvent
import com.sleepsaver.core.domain.alarm.AlarmSource
import com.sleepsaver.core.domain.alarm.CatnapPlan
import com.sleepsaver.core.domain.alarm.CatnapPlanner
import com.sleepsaver.core.domain.alarm.EndedAlarms
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.TomorrowList
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.nightsteps.NightStepsSession
import com.sleepsaver.core.domain.nightsteps.StepAdherence
import com.sleepsaver.core.domain.patterns.DebtPreview
import com.sleepsaver.core.domain.patterns.DebtProjector
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.ResolvedDay
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.ui.components.HushControlsState
import com.sleepsaver.feature.tonight.TonightPhase
import com.sleepsaver.feature.tonight.night.MovementLog
import com.sleepsaver.feature.tonight.night.NightAlarm
import com.sleepsaver.feature.tonight.night.NightModel
import com.sleepsaver.feature.tonight.nightClock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** What the Tonight tab shows. Built by [tonightUiState]; Loading until the insights are read. */
internal sealed interface TonightUiState {
    data object Loading : TonightUiState

    /** The plan couldn't be read. */
    data object Unavailable : TonightUiState

    /**
     * The tab in [phase]. [wakeDate] is the next morning (today in the small hours); [nightOf] the evening before it.
     * [alarmCard] and [week] start from [wakeDate], or from today while today's alarm is still to ring ([cardDate]).
     * [night] is set while the no-clock night (or a dropped anchor) is on. [armed] = some alarm will ring, so a
     * blocker is worth a warning. [noNights] = no Garmin nights yet: times come from Settings.
     */
    @Immutable
    data class Ready(
        val phase: TonightPhase,
        val now: Instant,
        val zone: ZoneId,
        val today: LocalDate,
        val nightOf: LocalDate,
        val wakeDate: LocalDate,
        val alarmCard: AlarmCardModel,
        val armed: Boolean,
        val debtPreview: DebtPreview?,
        val steps: NightStepsSession?,
        val adherence: StepAdherence?,
        val tomorrowList: TomorrowList?,
        val tonightMarks: Set<Mark>,
        val hush: HushControlsState,
        val catnap: CatnapModel?,
        val week: List<WeekStripDay>,
        val night: NightModel?,
        val demo: Boolean,
        val noNights: Boolean,
    ) : TonightUiState
}

/** An alarm after the card's date: "Next alarm: Wed 6:45 AM". */
@Immutable
internal data class NextAlarm(val date: LocalDate, val time: LocalTime)

/** The alarm card for [wakeDate] (SPEC_V2 §5.2 table). */
@Immutable
internal sealed interface AlarmCardModel {
    val wakeDate: LocalDate

    /**
     * An alarm rings on [wakeDate] at [time]: SURFACING from [windowStart] (widened on [debtAware] nights) or FIXED
     * at [time]. [justOnce] = a one-off made it; [thisMorning] = the wake date is today. Built from the planned alarm
     * the scheduler arms, so the card never promises a ring that isn't set.
     */
    data class On(
        override val wakeDate: LocalDate,
        val time: LocalTime,
        val mode: AlarmMode,
        val windowStart: LocalTime,
        val debtAware: Boolean,
        val justOnce: Boolean,
        val thisMorning: Boolean,
    ) : AlarmCardModel

    /** "No alarm Tue · Day off" with the alarm after it. */
    data class DayOff(override val wakeDate: LocalDate, val next: NextAlarm?) : AlarmCardModel

    /** "Alarms paused · Until Sun, Oct 12" with the first alarm after the pause. */
    data class Paused(override val wakeDate: LocalDate, val until: LocalDate, val next: NextAlarm?) : AlarmCardModel

    /** "No alarm tomorrow", with the next alarm when there is one. */
    data class None(override val wakeDate: LocalDate, val next: NextAlarm?) : AlarmCardModel

    /**
     * The plan has an alarm on [wakeDate], but that morning's alarm already ended, so it won't ring again: "No alarm
     * Mon · Mon's alarm already rang" ([rang]; false = it ended without ringing) with the alarm after it. Happens when
     * the clock or the time zone moves back a day after a ring.
     */
    data class Done(override val wakeDate: LocalDate, val rang: Boolean, val next: NextAlarm?) : AlarmCardModel
}

/** The Catnap card: the [running] catnap, or the picked [length] with its [plan] (timing hint, grogginess, coffee). */
@Immutable
internal data class CatnapModel(
    val running: ActiveCatnap?,
    val length: Duration,
    val lengths: List<Duration>,
    val plan: CatnapPlan,
)

/** One row of "This week": the wake date's set or one-off [time] (null = usual) and its alarm [mode]. */
@Immutable
internal data class WeekStripDay(val date: LocalDate, val time: LocalTime?, val mode: AlarmMode, val alarmOn: Boolean)

/**
 * Screen choices: the Hush pick before it plays (shared with every screen that has Hush), the catnap length, and when
 * "I'm up" was tapped.
 */
internal data class TonightLocal(
    val hush: HushSettings,
    val catnapLength: Duration,
    val endedAt: Instant?,
)

/** Everything [tonightUiState] reads, in one snapshot. */
internal data class TonightInputs(
    val insights: InsightsState,
    val debtInputs: DebtPreviewInputs?,
    val settings: AppSettings,
    val night: NightState,
    val now: Instant,
    val zone: ZoneId,
    val week: WeekPlan,
    val oneOffs: OneOffs,
    val alarms: List<PlannedAlarm>,
    val session: NightStepsSession?,
    val adherence: StepAdherence?,
    val marks: Map<LocalDate, Set<Mark>>,
    /** Last night's Tomorrow list (the evening before today). */
    val lastNightList: TomorrowList?,
    /** Today's planned alarm event, if it rang. */
    val todayEvent: AlarmEvent?,
    /** Tomorrow's planned alarm event: there is one only if it already rang (the clock or the zone moved back). */
    val tomorrowEvent: AlarmEvent?,
    /** The planned alarms that already ended, which the planned [alarms] leave out (ScheduleRepository.endedAlarms). */
    val ended: EndedAlarms,
    val local: TonightLocal,
)

/** Builds the Tonight tab's state; pure, so the phases and cards can be tested without a ViewModel. */
internal fun tonightUiState(i: TonightInputs, config: SleepConfig = SleepConfig.DEFAULT): TonightUiState {
    if (i.insights == InsightsState.Loading) return TonightUiState.Loading
    val insights = (i.insights as? InsightsState.Ready)?.insights
    val schedule = insights?.schedule
    val clock = nightClock(
        now = i.now,
        zone = i.zone,
        schedule = schedule,
        session = i.session,
        inputs = i.debtInputs,
        alarms = i.alarms,
        todayEvent = i.todayEvent,
        anchoredAt = i.night.night?.anchoredAt,
        endedAt = i.local.endedAt,
        config = config,
    )
    val wakeDate = clock.wakeDate
    val cardDate = cardDate(clock.today, wakeDate, i.now, i.alarms)
    // The card's date is today or tomorrow, so one of these is its planned event, if any.
    val cardEvent = listOfNotNull(i.todayEvent, i.tomorrowEvent).firstOrNull { it.wakeDate == cardDate }
    val card = alarmCard(cardDate, clock.today, i.week, i.oneOffs, i.alarms, i.zone, i.ended, cardEvent)

    val debtPreview = if (clock.phase == TonightPhase.EVENING) {
        i.debtInputs?.let { inputs ->
            DebtProjector(config).project(
                nights = inputs.nights,
                settings = inputs.settings.copy(week = i.week, oneOffs = i.oneOffs),
                now = i.now,
                zone = inputs.zone,
                wakeDate = wakeDate,
            )
        }
    } else {
        null
    }

    // Hush's own settings while it plays, else the shared pick; "Until my window" (a pick from a night with an alarm)
    // falls back to the default timer on a night without one, as Night steps and Night reset show it.
    val playing = i.night.hush
    val untilWindow = card is AlarmCardModel.On
    val hushTimer = if (playing != null) playing.timer else i.local.hush.timer
    val hush = HushControlsState(
        color = playing?.color ?: i.local.hush.color,
        timer = if (hushTimer == null && !untilWindow) config.hush.defaultTimer else hushTimer,
        volume = playing?.volume ?: i.local.hush.volume,
        playing = playing != null,
        untilWindowAvailable = untilWindow,
        mediaVolumeHigh = false,
    )

    val localTime = i.now.atZone(i.zone).toLocalTime()
    val running = i.night.catnap
    val catnap = if (clock.phase == TonightPhase.DAY &&
        (running != null || (localTime >= config.catnap.cardFrom && localTime < config.catnap.cardUntil))
    ) {
        val caffeineCutoff = insights?.habits?.firstOrNull { it.type == HabitType.CAFFEINE_CUTOFF }?.time
        CatnapModel(
            running = running,
            length = i.local.catnapLength,
            lengths = config.catnap.lengths,
            plan = CatnapPlanner(config).plan(i.now, i.local.catnapLength, schedule, caffeineCutoff),
        )
    } else {
        null
    }

    val night = if (clock.phase == TonightPhase.NIGHT || i.night.night != null) {
        NightModel(alarm = card.nightAlarm(), log = movementLog(i.night))
    } else {
        null
    }

    return TonightUiState.Ready(
        phase = clock.phase,
        now = i.now,
        zone = i.zone,
        today = clock.today,
        nightOf = clock.nightOf,
        wakeDate = wakeDate,
        alarmCard = card,
        armed = card is AlarmCardModel.On || i.alarms.isNotEmpty(),
        debtPreview = debtPreview,
        steps = i.session,
        adherence = i.adherence,
        tomorrowList = i.lastNightList?.takeIf { it.items.isNotEmpty() },
        tonightMarks = i.marks[wakeDate].orEmpty(),
        hush = hush,
        catnap = catnap,
        week = (0L until DAYS_IN_STRIP).map { offset ->
            val date = cardDate.plusDays(offset)
            val resolved = i.week.resolve(date, i.oneOffs)
            // A morning whose alarm already ended reads like one without an alarm, as its card does.
            if (resolved.alarmOn && i.ended.leavesOut(resolved)) {
                WeekStripDay(date, resolved.wakeTime, AlarmMode.OFF, alarmOn = false)
            } else {
                WeekStripDay(date, resolved.wakeTime, resolved.alarmMode, resolved.alarmOn)
            }
        },
        night = night,
        demo = i.settings.demoMode,
        noNights = insights == null,
    )
}

/**
 * The date the alarm card and "This week" start from: the [wakeDate], except while today's planned alarm is still to
 * ring after its window opened. The night has moved on to tonight by then (NightRules.focus), but that alarm is the
 * one about to ring, to see, skip or change. Example: a 6:45 alarm with a 30-min window: at 6:20 the card shows this
 * morning's 6:45; once it has rung (it leaves the planned [alarms]), tomorrow's.
 */
internal fun cardDate(today: LocalDate, wakeDate: LocalDate, now: Instant, alarms: List<PlannedAlarm>): LocalDate {
    val todayAlarm = alarms.firstOrNull { it.occurrence.date == today }
    return if (todayAlarm != null && now >= todayAlarm.window.start) today else wakeDate
}

/**
 * The card for [wakeDate]. The week plan and one-offs say whether that morning has an alarm at all (Day off, paused,
 * none); the planned [alarms], the same ones the scheduler arms, say what rings: an On card takes its time, mode,
 * tonight's effective window and debt-aware widening from the planned alarm, and the alarm after this one comes from
 * them too.
 *
 * When that morning's alarm is one the planner leaves out because it already ended ([ended]: that occurrence ended,
 * or a week-plan alarm on a morning whose alarm ended), the card is [AlarmCardModel.Done], with [event] (that
 * morning's latest planned event) saying whether it rang. Example: Monday's 6:50 rang, then the clock went back to
 * Sunday evening: "No alarm Mon · Mon's alarm already rang · Next alarm: Mon, Oct 19, 6:50 AM", never a 6:50 that
 * isn't armed.
 *
 * Otherwise, until the planned alarms (recomputed a moment after every write) match the plan's time, mode and source,
 * the card reads that morning's own alarm from the plan, rather than flash the old time or "already rang": "Turn back
 * on", or "Just this once" at 7:15 over a planned 6:45.
 */
internal fun alarmCard(
    wakeDate: LocalDate,
    today: LocalDate,
    week: WeekPlan,
    oneOffs: OneOffs,
    alarms: List<PlannedAlarm>,
    zone: ZoneId,
    ended: EndedAlarms = EndedAlarms(),
    event: AlarmEvent? = null,
): AlarmCardModel {
    val resolved = week.resolve(wakeDate, oneOffs)
    val next = alarms.firstOrNull { it.occurrence.date.isAfter(wakeDate) }
        ?.let { NextAlarm(it.occurrence.date, it.occurrence.localTime) }
    val time = resolved.wakeTime
    val pause = oneOffs.pause
    val planned = alarms.firstOrNull { it.occurrence.date == wakeDate }?.takeIf { it.isFor(resolved) }
    return when {
        !resolved.alarmOn || time == null -> when {
            resolved.isDayOff -> AlarmCardModel.DayOff(wakeDate, next)
            resolved.paused && pause != null -> AlarmCardModel.Paused(wakeDate, pause.until, next)
            else -> AlarmCardModel.None(wakeDate, next)
        }
        ended.leavesOut(resolved) -> AlarmCardModel.Done(wakeDate, rang = event?.rang == true, next = next)
        planned != null -> {
            val occurrence = planned.occurrence
            AlarmCardModel.On(
                wakeDate = wakeDate,
                time = occurrence.localTime,
                mode = occurrence.settings.mode,
                windowStart = planned.window.start.atZone(zone).toLocalTime(),
                debtAware = planned.window.debtAware,
                justOnce = occurrence.source == AlarmSource.JUST_ONCE,
                thisMorning = wakeDate == today,
            )
        }
        else -> AlarmCardModel.On(
            wakeDate = wakeDate,
            time = time,
            mode = resolved.alarmMode,
            windowStart = if (resolved.alarmMode == AlarmMode.SURFACING) time.minus(resolved.alarm.window) else time,
            debtAware = false,
            justOnce = resolved.isJustOnce,
            thisMorning = wakeDate == today,
        )
    }
}

/** Whether the planner leaves out [day]'s alarm (it has one: alarmOn) because it already ended. */
private fun EndedAlarms.leavesOut(day: ResolvedDay): Boolean {
    val time = day.wakeTime ?: return false
    return leavesOut(day.date, time, justOnce = day.isJustOnce)
}

/** This planned alarm is [day]'s as the plan reads now: same time, mode and source (not one from before a write). */
private fun PlannedAlarm.isFor(day: ResolvedDay): Boolean =
    occurrence.localTime == day.wakeTime &&
        occurrence.settings.mode == day.alarmMode &&
        (occurrence.source == AlarmSource.JUST_ONCE) == day.isJustOnce

/** The night's alarm line for this card. */
internal fun AlarmCardModel.nightAlarm(): NightAlarm = when (this) {
    is AlarmCardModel.On -> if (mode == AlarmMode.SURFACING) NightAlarm.NeverLater(time) else NightAlarm.At(time)
    else -> NightAlarm.None
}

/** The whole-night log's status: null when it wasn't asked for tonight. */
private fun movementLog(state: NightState): MovementLog? {
    if (state.night?.wholeNightLog != true) return null
    val sensing = state.sensing
    return if (sensing != null && sensing.mode == SensingMode.WHOLE_NIGHT && sensing.running) MovementLog.LOGGING else MovementLog.STOPPED
}

/** "This week": seven wake dates from the next one. */
private const val DAYS_IN_STRIP = 7L
