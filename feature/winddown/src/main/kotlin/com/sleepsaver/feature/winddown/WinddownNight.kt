package com.sleepsaver.feature.winddown

import android.util.Log
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
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.nightsteps.NightStepsSession
import com.sleepsaver.core.domain.schedule.FocusNight
import com.sleepsaver.core.domain.schedule.NightBounds
import com.sleepsaver.core.domain.schedule.NightRules
import com.sleepsaver.core.domain.schedule.ScheduleTimes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

/**
 * The night the Night steps screens are about, by [NightRules.focus] (the rule Tonight, Sleep home and Energy use):
 * last night stays in focus until it ends (this morning's alarm window, else its wake zone, or as soon as this
 * morning's planned alarm was stopped), then today's evening takes over. Example: bedtime 23:00 and a 6:45 alarm with a
 * 30-min window: at 00:20 Tuesday it is still Monday's evening (and night) although the energy schedule has already
 * moved on to Tuesday; from 6:15 it is Tuesday's.
 *
 * Each night's bounds come from the energy [schedule] and that morning's planned alarm ([NightRules.boundsFrom]).
 * Without a schedule (no nights yet) the bedtime comes from tonight's Night steps [session] (timed from the plan or
 * Settings), that morning's alarm or [ScheduleTimes] over [inputs], and the wake zone from [ScheduleTimes], as on
 * Tonight. [anchoredAt] (`NightController.state.night?.anchoredAt`) counts only for the night of its own wake date;
 * [endedAt] (`NightController.state.endedAt`, the last "I'm up") ends that night's no-clock time at once, even between
 * minute ticks ([NightRules.clockNow]), until the next anchor, as on Tonight, Sleep home and Energy.
 */
internal fun winddownFocus(
    now: Instant,
    zone: ZoneId,
    schedule: EnergySchedule?,
    session: NightStepsSession?,
    inputs: DebtPreviewInputs?,
    alarms: List<PlannedAlarm>,
    morningStop: Instant?,
    anchoredAt: Instant?,
    endedAt: Instant? = null,
    config: SleepConfig = SleepConfig.DEFAULT,
): FocusNight {
    val at = NightRules.clockNow(now, endedAt)
    val today = at.atZone(zone).toLocalDate()
    val times = ScheduleTimes(config)

    fun bounds(evening: LocalDate): NightBounds {
        val wakeDate = evening.plusDays(1)
        val alarm = alarms.firstOrNull { it.occurrence.date == wakeDate }
        if (schedule != null) return NightRules.boundsFrom(schedule, evening, alarm?.window?.start, alarm?.plannedBedtime)
        val bedtime = session?.takeIf { it.nightOf == evening }?.bedtime
            ?: alarm?.plannedBedtime
            ?: inputs?.let { times.bedtime(evening, zone, it.nights, it.settings.sleepNeed, it.settings.timing).first }
        val wakeZoneStart = inputs?.let {
            ZonedDateTime.of(wakeDate, times.wake(wakeDate, it.nights, it.settings.timing).first, zone).toInstant() - config.wakeZoneHalfWidth
        }
        return NightBounds(bedtime, alarm?.window?.start, wakeZoneStart)
    }

    // Night owls: the schedule (or, without nights, the Night steps evening) hasn't rolled over to today yet.
    val evening = schedule?.date ?: session?.nightOf
    return NightRules.focus(
        now = at,
        zone = zone,
        lastNight = bounds(today.minusDays(1)),
        tonight = bounds(today),
        morningStop = morningStop,
        anchoredAt = anchoredAt,
        endedAt = endedAt,
        eveningStillYesterday = evening != null && evening < today,
        config = config,
    )
}

/** The night in [focus] and its Night steps ([session]: null when they can't be computed). */
internal data class WinddownNight(val focus: FocusNight, val session: NightStepsSession?) {
    val nightOf: LocalDate get() = focus.nightOf
}

/**
 * [winddownFocus] over the app's own sources, for every Night steps screen: the checklist, the guided tools' check-off
 * and the Tomorrow list all write to the evening in focus, so a step ticked or a list written at 00:20 belongs to the
 * evening before, not to the one the schedule has moved on to.
 *
 * Emits once the insights have loaded, then whenever the night in focus or its session changes (night mode starts at
 * bedtime or Drop anchor and ends at the window). Optional inputs (the alarms, this morning's alarm, the timing
 * inputs) fall back to none when they fail, and steps that can't be read to a null [WinddownNight.session].
 */
internal class WinddownNightSource @Inject constructor(
    private val insights: InsightsRepository,
    private val schedule: ScheduleRepository,
    private val nightLog: NightLogRepository,
    private val patterns: PatternsRepository,
    private val nightSteps: NightStepsRepository,
    private val nightController: NightController,
    private val time: TimeProvider,
) {
    val night: Flow<WinddownNight> = run {
        val ticks = time.minuteTicks
        val morningStop = ticks.map { it.atZone(time.zone()).toLocalDate() }
            .distinctUntilChanged()
            .flatMapLatest { today -> nightLog.alarmEvent(today).map { NightRules.morningStop(it, today) } }
            .orNone(null, "This morning's alarm")
        val plan = combine(
            schedule.upcomingAlarms.orNone(emptyList(), "Upcoming alarms"),
            patterns.debtPreviewInputs.orNone(null, "Timing inputs"),
            morningStop,
            ::Triple,
        )
        val energySchedule = insights.state
            .filter { it !is InsightsState.Loading }
            .map { (it as? InsightsState.Ready)?.insights?.schedule }
        // The anchor and the last "I'm up", as Tonight, Sleep home and Energy read them.
        val anchorAndEnd = nightController.state.map { it.night?.anchoredAt to it.endedAt }.distinctUntilChanged()
        val tonight = nightSteps.tonight.orNone(null, "Tonight's steps")
        combine(energySchedule, tonight, plan, anchorAndEnd, ticks) { schedule, session, (alarms, inputs, stop), (anchored, ended), now ->
            winddownFocus(now, time.zone(), schedule, session, inputs, alarms, stop, anchored, ended) to session
        }
            .distinctUntilChanged()
            .flatMapLatest { (focus, tonight) ->
                // Tonight's session is the schedule's evening; after midnight that can be the next one already.
                val session = if (tonight != null && tonight.nightOf != focus.nightOf) {
                    nightSteps.session(focus.nightOf).orNone(null, "Steps of ${focus.nightOf}")
                } else {
                    flowOf(tonight)
                }
                session.map { WinddownNight(focus, it) }
            }
            .distinctUntilChanged()
    }

    private fun <T> Flow<T>.orNone(fallback: T, what: String): Flow<T> = catch {
        Log.w(LOG_TAG, "$what failed", it)
        emit(fallback)
    }
}
