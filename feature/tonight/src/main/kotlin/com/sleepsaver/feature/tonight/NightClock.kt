package com.sleepsaver.feature.tonight

import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.DebtPreviewInputs
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.NightLogRepository
import com.sleepsaver.core.data.repository.NightStepsRepository
import com.sleepsaver.core.data.repository.PatternsRepository
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.domain.alarm.AlarmEvent
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.domain.nightsteps.NightStepsSession
import com.sleepsaver.core.domain.schedule.NightBounds
import com.sleepsaver.core.domain.schedule.NightRules
import com.sleepsaver.core.domain.schedule.ScheduleTimes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

/**
 * Where the Tonight tab is in the day (SPEC_V2 §5.2):
 *  - NIGHT: the no-clock night ([NightRules.isNight]): from Drop anchor or the target bedtime until the surfacing
 *    window opens (or the wake zone starts when there is no alarm).
 *  - MORNING: from the last wake (the alarm's stop, the watch's wake or the planned wake time) until
 *    `week.morningPhaseEnd` (noon), and not night.
 *  - EVENING: from the Night steps window start until the night starts (normally the bedtime), and not night.
 *  - DAY: the rest.
 */
internal enum class TonightPhase { MORNING, DAY, EVENING, NIGHT }

/**
 * Now, and where the night in focus sits. [nightOf] is the evening whose night is coming or under way: today, or
 * yesterday in the small hours (until last night ends at the surfacing window or wake zone). [wakeDate] is the
 * morning after it. [stepsFrom] / [bedtime] / [end] are that night's Night steps window start, target bedtime and
 * no-clock end (null when unknown).
 */
internal data class NightClock(
    val now: Instant,
    val zone: ZoneId,
    val today: LocalDate,
    val nightOf: LocalDate,
    val stepsFrom: Instant?,
    val bedtime: Instant?,
    val end: Instant?,
    val isNight: Boolean,
    val phase: TonightPhase,
) {
    val wakeDate: LocalDate get() = nightOf.plusDays(1)
}

/**
 * Works out the [NightClock] for [now]. Example: bedtime 23:00, alarm 6:45 with a 30-min window: at 22:00 it is
 * EVENING (Night steps from 21:30) of today, at 02:00 NIGHT of yesterday's evening (wake date today), at 7:00
 * MORNING of today (wake date tomorrow).
 *
 * Times come from the energy [schedule] for its own date (its bedtime, wind-down start and wake zones); without a
 * schedule (no nights yet) from tonight's Night steps [session] (timed from the plan or Settings) and from
 * [ScheduleTimes] over [inputs]. The night ends at the [alarms]' window start for the wake date, else at its wake
 * zone start; [todayEvent], today's planned alarm once stopped, ends last night and starts the morning at its stop.
 * Whether it is night is [NightRules.focus], shared with Sleep home and Energy.
 *
 * [anchoredAt] is `NightController.state.night?.anchoredAt` (§5.0). [endedAt] is `NightController.state.endedAt`, when
 * the user last tapped "I'm up" (Sleep home and Energy read it too): a night ended that way no longer counts its
 * bedtime, so the clock comes back until they drop anchor again.
 */
internal fun nightClock(
    now: Instant,
    zone: ZoneId,
    schedule: EnergySchedule?,
    session: NightStepsSession?,
    inputs: DebtPreviewInputs?,
    alarms: List<PlannedAlarm>,
    todayEvent: AlarmEvent?,
    anchoredAt: Instant?,
    endedAt: Instant?,
    config: SleepConfig = SleepConfig.DEFAULT,
): NightClock {
    val times = ScheduleTimes(config)
    val today = now.atZone(zone).toLocalDate()
    val yesterday = today.minusDays(1)

    fun alarmStart(wakeDate: LocalDate): Instant? = alarms.firstOrNull { it.occurrence.date == wakeDate }?.window?.start

    fun plannedWake(date: LocalDate): Instant? = when {
        schedule?.date == date -> schedule.typicalWake.toInstant()
        inputs != null -> ZonedDateTime.of(date, times.wake(date, inputs.nights, inputs.settings.timing).first, zone).toInstant()
        else -> null
    }

    fun wakeZoneStart(date: LocalDate): Instant? = when {
        schedule?.date == date -> schedule.wakeZone.start
        schedule?.date == date.minusDays(1) -> schedule.tomorrowWakeZone.start
        else -> plannedWake(date)?.minus(config.wakeZoneHalfWidth)
    }

    fun bedtimeOf(evening: LocalDate): Instant? = when {
        schedule?.date == evening -> schedule.targetBedtime
        session?.nightOf == evening -> session.bedtime
        inputs != null -> times.bedtime(evening, zone, inputs.nights, inputs.settings.sleepNeed, inputs.settings.timing).first
        // The schedule only moves on to today once yesterday's bedtime has passed: any time before now will do.
        schedule != null -> minOf(now, schedule.targetBedtime.minus(Duration.ofDays(1)))
        else -> null
    }

    fun stepsFromOf(evening: LocalDate): Instant? = when {
        schedule?.date == evening -> schedule.phase(PhaseType.WIND_DOWN)?.start
        session?.nightOf == evening -> session.windowStart
        else -> null
    }

    // Today's planned alarm, once stopped (or timed out), ended last night there and then.
    val stoppedToday = NightRules.morningStop(todayEvent, today)
    // The night decision itself is the one Sleep home and Energy use (NightRules.focus).
    val focus = NightRules.focus(
        now = now,
        zone = zone,
        lastNight = NightBounds(bedtimeOf(yesterday), alarmStart(today), wakeZoneStart(today)),
        tonight = NightBounds(bedtimeOf(today), alarmStart(today.plusDays(1)), wakeZoneStart(today.plusDays(1))),
        morningStop = stoppedToday,
        anchoredAt = anchoredAt,
        endedAt = endedAt,
        // Night owls: the schedule (or, without nights, the Night steps evening) is still yesterday's.
        eveningStillYesterday = schedule?.date == yesterday || (schedule == null && session?.nightOf == yesterday),
        config = config,
    )
    val nightOf = focus.nightOf
    val bedtime = focus.bedtime
    val end = focus.end
    val isNight = focus.isNight
    val stepsFrom = stepsFromOf(nightOf)

    val morningStart = if (nightOf == today) {
        listOfNotNull(
            stoppedToday,
            schedule?.takeIf { it.date == today }?.actualWake,
            plannedWake(today),
        ).minOrNull()
    } else {
        null
    }
    val phase = when {
        isNight -> TonightPhase.NIGHT
        morningStart != null && now >= morningStart && now.atZone(zone).toLocalTime() < config.week.morningPhaseEnd ->
            TonightPhase.MORNING
        stepsFrom != null && now >= stepsFrom && now < (end ?: bedtime ?: stepsFrom) -> TonightPhase.EVENING
        else -> TonightPhase.DAY
    }
    return NightClock(now, zone, today, nightOf, stepsFrom, bedtime, end, isNight, phase)
}

/**
 * The moment a night-mode screen is built for: the last minute [tick], or the "I'm up" tap ([endedAt]) when it came
 * after it. Ticks arrive just after each minute boundary, so a night ended at 02:13:45 would otherwise still be
 * judged at 02:13:00 (before the tap, so not ended) and stay night until the 02:14 tick.
 */
internal fun clockNow(tick: Instant, endedAt: Instant?): Instant = if (endedAt != null && endedAt > tick) endedAt else tick

/**
 * The [NightClock] for screens that only need night mode (the Week plan subtitle, the test-ring message), from the
 * same sources the Tonight tab uses.
 */
internal class NightClockSource @Inject constructor(
    private val insights: InsightsRepository,
    private val schedule: ScheduleRepository,
    private val nightSteps: NightStepsRepository,
    private val nightLog: NightLogRepository,
    private val patterns: PatternsRepository,
    private val nightController: NightController,
    private val time: TimeProvider,
) {
    val clock: Flow<NightClock> = run {
        val ticks = time.minuteTicks
        val todayEvent = ticks.map { it.atZone(time.zone()).toLocalDate() }
            .distinctUntilChanged()
            .flatMapLatest { nightLog.alarmEvent(it) }
        val anchoredAt = nightController.state.map { it.night?.anchoredAt }.distinctUntilChanged()
        val endedAt = nightController.state.map { it.endedAt }.distinctUntilChanged()
        val plan = combine(schedule.upcomingAlarms, nightSteps.tonight, patterns.debtPreviewInputs, ::Triple)
        combine(insights.state, plan, todayEvent, anchoredAt, endedAt) { state, (alarms, session, inputs), event, anchored, ended ->
            ClockInputs(state, alarms, session, inputs, event, anchored, ended)
        }.combine(ticks) { inputs, tick ->
            nightClock(
                now = clockNow(tick, inputs.endedAt),
                zone = time.zone(),
                schedule = (inputs.insights as? InsightsState.Ready)?.insights?.schedule,
                session = inputs.session,
                inputs = inputs.debtInputs,
                alarms = inputs.alarms,
                todayEvent = inputs.todayEvent,
                anchoredAt = inputs.anchoredAt,
                endedAt = inputs.endedAt,
            )
        }
    }

    private data class ClockInputs(
        val insights: InsightsState,
        val alarms: List<PlannedAlarm>,
        val session: NightStepsSession?,
        val debtInputs: DebtPreviewInputs?,
        val todayEvent: AlarmEvent?,
        val anchoredAt: Instant?,
        val endedAt: Instant?,
    )
}
