package com.sleepsaver.feature.tonight.tonight

import androidx.compose.runtime.Immutable
import com.sleepsaver.core.data.hooks.ActiveCatnap
import com.sleepsaver.core.data.hooks.NightState
import com.sleepsaver.core.data.hooks.SensingMode
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.DebtPreviewInputs
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.domain.alarm.AlarmEvent
import com.sleepsaver.core.domain.alarm.CatnapPlan
import com.sleepsaver.core.domain.alarm.CatnapPlanner
import com.sleepsaver.core.domain.alarm.HushColor
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
     * at [time]. [justOnce] = a one-off made it; [thisMorning] = the wake date is today.
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

/** Screen choices kept by the ViewModel: Hush before it plays, the catnap length, and when "I'm up" was tapped. */
internal data class TonightLocal(
    val hushColor: HushColor,
    val hushTimer: Duration?,
    val hushVolume: Float,
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
    val card = alarmCard(wakeDate, clock.today, i.week, i.oneOffs, i.alarms, i.zone)

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

    val playing = i.night.hush
    val hush = HushControlsState(
        color = playing?.color ?: i.local.hushColor,
        timer = if (playing != null) playing.timer else i.local.hushTimer,
        volume = playing?.volume ?: i.local.hushVolume,
        playing = playing != null,
        untilWindowAvailable = card is AlarmCardModel.On,
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
            val date = wakeDate.plusDays(offset)
            val resolved = i.week.resolve(date, i.oneOffs)
            WeekStripDay(date, resolved.wakeTime, resolved.alarmMode, resolved.alarmOn)
        },
        night = night,
        demo = i.settings.demoMode,
        noNights = insights == null,
    )
}

/**
 * The card for [wakeDate], from the week plan and one-offs (the source of truth for what rings) and the planned
 * [alarms] (tonight's effective window, debt-aware widening, and the alarm after this one).
 */
internal fun alarmCard(
    wakeDate: LocalDate,
    today: LocalDate,
    week: WeekPlan,
    oneOffs: OneOffs,
    alarms: List<PlannedAlarm>,
    zone: ZoneId,
): AlarmCardModel {
    val resolved = week.resolve(wakeDate, oneOffs)
    val next = alarms.firstOrNull { it.occurrence.date.isAfter(wakeDate) }
        ?.let { NextAlarm(it.occurrence.date, it.occurrence.localTime) }
    val time = resolved.wakeTime
    val pause = oneOffs.pause
    return when {
        resolved.alarmMode != AlarmMode.OFF && time != null -> {
            val planned = alarms.firstOrNull { it.occurrence.date == wakeDate }
            val windowStart = planned?.window?.start?.atZone(zone)?.toLocalTime()
                ?: if (resolved.alarmMode == AlarmMode.SURFACING) time.minus(resolved.alarm.window) else time
            AlarmCardModel.On(
                wakeDate = wakeDate,
                time = time,
                mode = resolved.alarmMode,
                windowStart = windowStart,
                debtAware = planned?.window?.debtAware == true,
                justOnce = resolved.isJustOnce,
                thisMorning = wakeDate == today,
            )
        }
        resolved.isDayOff -> AlarmCardModel.DayOff(wakeDate, next)
        resolved.paused && pause != null -> AlarmCardModel.Paused(wakeDate, pause.until, next)
        else -> AlarmCardModel.None(wakeDate, next)
    }
}

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
