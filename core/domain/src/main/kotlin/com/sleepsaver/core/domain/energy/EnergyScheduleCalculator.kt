package com.sleepsaver.core.domain.energy

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.Night
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Builds a day's energy schedule (docs/PLAN.md §3.6–3.7).
 *
 * Inputs: all assembled nights (used for the anchor and for Process S history), the day [date] (a wake date),
 * the device [zone] for turning W into instants (DST-safe via ZonedDateTime), need, current debt.
 *
 *  - Anchor/W from CircadianAnchorEstimator using nights with wakeDate ≤ date.
 *  - W as ZonedDateTime = date at typicalWake in [zone] (ZonedDateTime.of resolves DST gaps forward).
 *  - Wa = effective wokeUp of the night with wakeDate == date, if present; else W.
 *  - Process S: seeded `sSeed` at Wa − sHistoryHours, integrated over every asleep interval (main/extra blocks minus their
 *    awake periods, naps) that overlaps the history, awake otherwise; then awake from Wa to B.
 *  - Curve: [TwoProcessModel] from Wa to B at curveStep, min-max normalised to 0..1.
 *  - Phases per the table in §3.7. B uses *tomorrow's* W: (date + 1) at typicalWake in [zone], minus need, minus latency.
 *  - Phases list order: GROGGINESS, MORNING_PEAK, AFTERNOON_DIP, EVENING_PEAK, WIND_DOWN, MELATONIN_WINDOW.
 *  - shiftVsYesterday is left null here; see [withShifts].
 */
class EnergyScheduleCalculator(private val config: SleepConfig = SleepConfig.DEFAULT) {

    fun schedule(
        date: LocalDate,
        zone: ZoneId,
        nights: List<Night>,
        need: Duration,
        debt: Duration,
        fallbackWake: LocalTime,
    ): EnergySchedule {
        TODO("M2: energy schedule")
    }

    /**
     * Fills Phase.shiftVsYesterday = (today's start local time of day) − (yesterday's start local time of day),
     * both in [today]'s zone, wrapped into (−12 h, 12 h]. Phases missing in [yesterday] stay null.
     */
    fun withShifts(today: EnergySchedule, yesterday: EnergySchedule?): EnergySchedule {
        TODO("M2: phase shifts")
    }
}
