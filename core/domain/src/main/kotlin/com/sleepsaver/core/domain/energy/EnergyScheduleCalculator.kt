package com.sleepsaver.core.domain.energy

import com.sleepsaver.core.domain.circadian.CircadianAnchorEstimator
import com.sleepsaver.core.domain.config.PhaseGuardrail
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.EnergyCurve
import com.sleepsaver.core.domain.model.EnergyPoint
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.Phase
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.domain.model.SleepBlock
import com.sleepsaver.core.domain.model.SleepTiming
import com.sleepsaver.core.domain.model.TimeSpan
import com.sleepsaver.core.domain.model.TimingSource
import com.sleepsaver.core.domain.schedule.ScheduleTimes
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToLong

/**
 * Builds a day's energy schedule (docs/PLAN.md §3.6–3.7).
 *
 * Inputs: all assembled nights (used for the anchor and for Process S history), the day [date] (a wake date),
 * the device [zone] for turning W into instants (DST-safe via ZonedDateTime), need, current debt.
 *
 *  - W and B come from [ScheduleTimes] (PLAN §3.11). W: the day's own wake time in the week plan (a set time or
 *    "Just this once") → WEEK_PLAN; otherwise per [SleepTiming.wakeTimeMode]: FROM_DATA → learned from recent wake
 *    times (SleepTimingEstimator, weekdays and weekends apart), falling back to the user's own wake time while there
 *    aren't enough nights; FIXED → the user's own wake time. A day off or a pause sleeps to that usual time.
 *  - W as ZonedDateTime = date at that time in [zone] (ZonedDateTime.of resolves DST gaps forward).
 *  - Wa = effective wokeUp of the night with wakeDate == date, if present; else W.
 *  - Process S: seeded `sSeed` at Wa − sHistoryHours, integrated over every asleep interval (main/extra blocks minus their
 *    awake periods, naps) that overlaps the history, awake otherwise; then awake from Wa to B.
 *    A wake date d in the history without main sleep (not synced yet, not recorded, nap-only) is assumed to be a
 *    typical night: a nominal asleep span [W_d − need, W_d] with W_d = d's own (plan-aware) W in [zone].
 *    Otherwise the WAITING morning (last night not in yet) would read as a night awake: phases would slide to their
 *    limits and snap back once the data arrives. A brand-new user therefore gets the nominal phases.
 *  - Curve: [TwoProcessModel] from Wa to B at curveStep (B is always the last point), min-max normalised to 0..1.
 *  - B (target bedtime, the evening of [date]): the evening plan's set bedtime → WEEK_PLAN; otherwise per
 *    [SleepTiming.bedtimeMode]: FROM_DATA → when the user usually falls asleep before a day like tomorrow, minus the
 *    latency (the user's own bedtime while there aren't enough nights); SLEEP_NEED → tomorrow's plan-aware W − need −
 *    latency; FIXED → the user's own bedtime. latency = [SleepTiming.latency] ?: `sleepLatency`. A clock time before
 *    noon means after midnight (the next calendar day). [EnergySchedule.lastNightBedtime] is B of the evening before
 *    [date] by the same rules (that evening's plan), so night mode after midnight knows when last night began.
 *  - Phases per the table in §3.7. The wind-down lasts the evening plan's windDownLength clamped to
 *    [week.windDownMinLength, week.windDownMaxLength]; [EnergySchedule.nightStepsOn] is the evening plan's switch.
 *    Morning peak, afternoon dip and evening peak never start before grogginess ends (matters after a very late wake).
 *  - Phases list order: GROGGINESS, MORNING_PEAK, AFTERNOON_DIP, EVENING_PEAK, WIND_DOWN, MELATONIN_WINDOW.
 *  - shiftVsYesterday is left null here; see [withShifts].
 */
class EnergyScheduleCalculator(private val config: SleepConfig = SleepConfig.DEFAULT) {

    private val anchorEstimator = CircadianAnchorEstimator(config)
    private val scheduleTimes = ScheduleTimes(config)
    private val model = TwoProcessModel(config)

    init {
        require(config.curveStep > Duration.ZERO) { "curveStep must be positive" }
    }

    fun schedule(
        date: LocalDate,
        zone: ZoneId,
        nights: List<Night>,
        need: Duration,
        debt: Duration,
        timing: SleepTiming,
    ): EnergySchedule {
        val history = nights.filter { !it.wakeDate.isAfter(date) }
        val (wakeTime, wakeSource) = scheduleTimes.wake(date, history, timing)
        val (tomorrowWakeTime, _) = scheduleTimes.wake(date.plusDays(1), history, timing)
        val anchor = anchorEstimator.estimate(history, date, need, timing.wakeTime)
        val typicalWake = ZonedDateTime.of(date, wakeTime, zone)
        val tomorrowWake = ZonedDateTime.of(date.plusDays(1), tomorrowWakeTime, zone).toInstant()
        val w = typicalWake.toInstant()
        val actualWake = history.firstOrNull { it.wakeDate == date && it.isPresent }?.let { it.wokeUp ?: it.main?.end }
        val wa = actualWake ?: w
        val (bedtime, bedtimeSource) = scheduleTimes.bedtime(date, zone, history, need, timing)
        val (lastNightBedtime, _) = scheduleTimes.bedtime(date.minusDays(1), zone, history, need, timing)
        val evening = timing.week.resolve(date, timing.oneOffs).evening
        val debtHours = hours(debt)
        val historyStart = wa - Duration.ofHours(config.sHistoryHours)
        val asleep = asleepIntervals(history) + nominalNights(history, historyStart, date, timing, zone, need)
        val curve = curve(wa, historyStart, w, bedtime, debtHours, asleep)

        val grogginess = Phase(PhaseType.GROGGINESS, wa, wa + grogginessLength(debtHours))
        fun Phase.afterGrogginess() = copy(start = maxOf(start, grogginess.end))
        val morningPeak = guardedPhase(PhaseType.MORNING_PEAK, config.morningPeak, w, curve, peak = true).afterGrogginess()
        val afternoonDip = guardedPhase(PhaseType.AFTERNOON_DIP, config.afternoonDip, w, curve, peak = false).afterGrogginess()
        val eveningPeak = guardedPhase(PhaseType.EVENING_PEAK, config.eveningPeak, w, curve, peak = true).afterGrogginess()
        val melatoninStart = bedtime - config.melatoninHalfWidth
        val windDownLength = evening.windDownLength.coerceIn(config.week.windDownMinLength, config.week.windDownMaxLength)
        val windDown = Phase(PhaseType.WIND_DOWN, melatoninStart - windDownLength, melatoninStart)
        val melatonin = Phase(PhaseType.MELATONIN_WINDOW, melatoninStart, bedtime + config.melatoninHalfWidth)

        return EnergySchedule(
            date = date,
            zone = zone,
            typicalWake = typicalWake,
            actualWake = actualWake,
            wakeZone = around(w, config.wakeZoneHalfWidth),
            tomorrowWakeZone = around(tomorrowWake, config.wakeZoneHalfWidth),
            targetBedtime = bedtime,
            lastNightBedtime = lastNightBedtime,
            phases = listOf(grogginess, morningPeak, afternoonDip, eveningPeak, windDown, melatonin)
                .map { it.atLeast(config.minPhaseLength) },
            curve = curve,
            anchor = anchor.copy(
                typicalWake = wakeTime,
                isFallback = wakeSource == TimingSource.NOT_ENOUGH_DATA,
                wakeSource = wakeSource,
                bedtimeSource = bedtimeSource,
            ),
            nightStepsOn = evening.nightStepsOn,
        )
    }

    /**
     * Fills Phase.shiftVsYesterday = (today's start local time of day) − (yesterday's start local time of day),
     * both in [today]'s zone, wrapped into (−12 h, 12 h]. Phases missing in [yesterday] stay null.
     */
    fun withShifts(today: EnergySchedule, yesterday: EnergySchedule?): EnergySchedule =
        today.copy(
            phases = today.phases.map { phase ->
                val previous = yesterday?.phase(phase.type)
                phase.copy(
                    shiftVsYesterday = previous?.let {
                        wrapShift(secondOfDay(phase.start, today.zone) - secondOfDay(it.start, today.zone))
                    },
                )
            },
        )

    private fun curve(
        wa: Instant,
        historyStart: Instant,
        w: Instant,
        bedtime: Instant,
        debtHours: Double,
        asleep: List<TimeSpan>,
    ): EnergyCurve {
        val sAtWake = model.homeostaticPressure(historyStart, wa, asleep)
        val times = if (bedtime.isAfter(wa)) {
            generateSequence(wa) { it + config.curveStep }.takeWhile { it.isBefore(bedtime) }.toList() + bedtime
        } else {
            listOf(wa)
        }
        val raw = times.map { t ->
            model.circadian(t, w) - model.riseWhileAwake(sAtWake, hoursBetween(wa, t)) + model.inertia(t, wa, debtHours)
        }
        val min = raw.min()
        val max = raw.max()
        return EnergyCurve(
            times.zip(raw) { t, e -> EnergyPoint(t, if (max > min) ((e - min) / (max - min)).toFloat() else 0.5f) },
        )
    }

    /**
     * [W_d − need, W_d] for every wake date d from [historyStart]'s date through [date] that has no main sleep in
     * [history], with W_d = d's own W (each day uses its own plan). Spans reaching outside the history are clipped by
     * the model.
     */
    private fun nominalNights(
        history: List<Night>,
        historyStart: Instant,
        date: LocalDate,
        timing: SleepTiming,
        zone: ZoneId,
        need: Duration,
    ): List<TimeSpan> {
        val recorded = history.filter { it.main != null }.mapTo(HashSet()) { it.wakeDate }
        return generateSequence(historyStart.atZone(zone).toLocalDate()) { it.plusDays(1) }
            .takeWhile { !it.isAfter(date) }
            .filterNot { it in recorded }
            .map { day ->
                val wake = ZonedDateTime.of(day, scheduleTimes.wake(day, history, timing).first, zone).toInstant()
                TimeSpan(wake - need, wake)
            }
            .toList()
    }

    /** Main, extra and nap blocks minus their awake periods. */
    private fun asleepIntervals(nights: List<Night>): List<TimeSpan> = nights.flatMap { night ->
        (listOfNotNull(night.main) + night.extraSegments + night.naps).flatMap { it.asleepSpans() }
    }

    private fun SleepBlock.asleepSpans(): List<TimeSpan> {
        val spans = mutableListOf<TimeSpan>()
        var cursor = start
        for (awake in awakePeriods.sortedBy { it.start }) {
            val awakeStart = maxOf(awake.start, start)
            val awakeEnd = minOf(awake.end, end)
            if (!awakeEnd.isAfter(awakeStart)) continue
            if (awakeStart.isAfter(cursor)) spans += TimeSpan(cursor, awakeStart)
            cursor = maxOf(cursor, awakeEnd)
        }
        if (end.isAfter(cursor)) spans += TimeSpan(cursor, end)
        return spans
    }

    private fun grogginessLength(debtHours: Double): Duration {
        val length = Duration.ofSeconds((model.inertiaDurationMinutes(debtHours) * 60).roundToLong())
        return length.coerceIn(config.grogginessMin, config.grogginessMax)
    }

    /** The guardrail's nominal window, slid toward the curve's extreme in its search range by ≤ `phaseMaxSlide`. */
    private fun guardedPhase(type: PhaseType, guardrail: PhaseGuardrail, w: Instant, curve: EnergyCurve, peak: Boolean): Phase {
        val searchFrom = w + guardrail.searchStart
        val searchTo = w + guardrail.searchEnd
        val candidates = curve.points.filter { !it.time.isBefore(searchFrom) && !it.time.isAfter(searchTo) }
        val extreme = if (peak) candidates.maxByOrNull { it.value } else candidates.minByOrNull { it.value }
        val slide = extreme
            ?.let { Duration.between(w + guardrail.nominalCenter, it.time) }
            ?.coerceIn(config.phaseMaxSlide.negated(), config.phaseMaxSlide)
            ?: Duration.ZERO
        return Phase(type, w + guardrail.nominalStart + slide, w + guardrail.nominalEnd + slide)
    }

    private fun Phase.atLeast(length: Duration): Phase =
        if (Duration.between(start, end) < length) copy(end = start + length) else this

    private fun around(t: Instant, halfWidth: Duration) = TimeSpan(t - halfWidth, t + halfWidth)

    private fun secondOfDay(t: Instant, zone: ZoneId): Long = t.atZone(zone).toLocalTime().toSecondOfDay().toLong()

    private fun wrapShift(seconds: Long): Duration {
        val wrapped = Math.floorMod(seconds, SECONDS_PER_DAY)
        return Duration.ofSeconds(if (wrapped > SECONDS_PER_DAY / 2) wrapped - SECONDS_PER_DAY else wrapped)
    }

    private companion object {
        const val SECONDS_PER_DAY = 86_400L
    }
}
