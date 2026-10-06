package com.sleepsaver.core.domain.debt

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.DebtChangeBreakdown
import com.sleepsaver.core.domain.model.DebtContribution
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepDebt
import com.sleepsaver.core.domain.model.Streak
import java.time.Duration
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.pow

/**
 * Rolling, recency-weighted sleep debt (docs/PLAN.md §3.3–3.4).
 *
 *   for i in 1..debtWindowNights: night_i has wakeDate = asOf − (i − 1)
 *   delta_i = need − total_i, floored at −maxSurplusCreditPerNight
 *   w_i = debtDecay^(i−1)
 *   debt = max(0, Σ_{present} w_i · delta_i)
 *
 * A night is present when `Night.isPresent`. Missing nights are skipped (weights are NOT renormalised)
 * and listed in [SleepDebt.missingNights].
 *
 * The weighted sum is accumulated in seconds as a Double and rounded to whole seconds once at the end.
 */
class SleepDebtCalculator(private val config: SleepConfig = SleepConfig.DEFAULT) {

    /** Contributions and [SleepDebt.missingNights] are ordered most recent first (i = 1 is last night). */
    fun debtAsOf(asOf: LocalDate, nightsByDate: Map<LocalDate, Night>, need: Duration): SleepDebt {
        val surplusFloor = config.maxSurplusCreditPerNight.negated()
        val contributions = ArrayList<DebtContribution>(config.debtWindowNights)
        val missing = ArrayList<LocalDate>()
        var sumSeconds = 0.0
        for (i in 1..config.debtWindowNights) {
            val date = asOf.minusDays(i - 1L)
            val weight = config.debtDecay.pow(i - 1)
            val night = nightsByDate[date]?.takeIf { it.isPresent }
            if (night == null) {
                missing += date
                contributions += DebtContribution(date, i, weight, totalSleep = null, delta = null, weighted = null)
                continue
            }
            val delta = maxOf(need.minus(night.totalSleep), surplusFloor)
            val weightedSeconds = weight * delta.toSecondsDouble()
            sumSeconds += weightedSeconds
            contributions += DebtContribution(date, i, weight, night.totalSleep, delta, roundedSeconds(weightedSeconds))
        }
        val raw = roundedSeconds(sumSeconds)
        return SleepDebt(
            asOf = asOf,
            debt = maxOf(raw, Duration.ZERO),
            rawWeightedSum = raw,
            contributions = contributions,
            missingNights = missing,
        )
    }

    /** debt(asOf) for every date in [from, to] inclusive, ascending. Empty when [from] is after [to]. */
    fun series(from: LocalDate, to: LocalDate, nightsByDate: Map<LocalDate, Night>, need: Duration): List<SleepDebt> =
        generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .map { debtAsOf(it, nightsByDate, need) }
            .toList()

    /** debt(asOf) − debt(asOf − 1). */
    fun changeSinceYesterday(asOf: LocalDate, nightsByDate: Map<LocalDate, Night>, need: Duration): Duration =
        debtAsOf(asOf, nightsByDate, need).debt.minus(debtAsOf(asOf.minusDays(1), nightsByDate, need).debt)

    /**
     * See [DebtChangeBreakdown]. When night [wakeDate] is missing, slept and lastNightDelta are zero and
     * the whole change shows up as fade.
     */
    fun breakdown(wakeDate: LocalDate, nightsByDate: Map<LocalDate, Night>, need: Duration): DebtChangeBreakdown {
        val before = debtAsOf(wakeDate.minusDays(1), nightsByDate, need).debt
        val current = debtAsOf(wakeDate, nightsByDate, need)
        val lastNightDelta = current.contributions.firstOrNull()?.delta ?: Duration.ZERO
        val after = current.debt
        return DebtChangeBreakdown(
            wakeDate = wakeDate,
            need = need,
            slept = nightsByDate[wakeDate]?.takeIf { it.isPresent }?.totalSleep ?: Duration.ZERO,
            before = before,
            lastNightDelta = lastNightDelta,
            fade = after.minus(before).minus(lastNightDelta),
            after = after,
        )
    }

    /** LOW < lowDebtMax ≤ MODERATE < highDebtMin ≤ HIGH. */
    fun status(debt: Duration, lowDebtMax: Duration = config.lowDebtMax, highDebtMin: Duration = config.highDebtMin): DebtStatus =
        when {
            debt < lowDebtMax -> DebtStatus.LOW
            debt < highDebtMin -> DebtStatus.MODERATE
            else -> DebtStatus.HIGH
        }

    /** clamp(100 − energyPotentialPerDebtHour · debtHours, floor, ceiling), rounded half-up. */
    fun energyPotential(debt: Duration): Int {
        val raw = 100.0 - config.energyPotentialPerDebtHour * debt.toSecondsDouble() / SECONDS_PER_HOUR
        val clamped = raw.coerceIn(config.energyPotentialFloor.toDouble(), config.energyPotentialCeiling.toDouble())
        return floor(clamped + 0.5).toInt()
    }

    /**
     * Count back from [asOf] while debt(d) < threshold. A day whose own night is missing is skipped
     * (doesn't count, doesn't break) unless more than `streakMaxBridgedMissingDays` missing days in a row,
     * which ends the streak. A day with debt ≥ threshold ends it. Looks back at most `streakLookbackDays`.
     */
    fun streak(asOf: LocalDate, nightsByDate: Map<LocalDate, Night>, need: Duration, threshold: Duration = config.streakThreshold): Streak {
        var days = 0
        var since: LocalDate? = null
        var missingRun = 0
        for (back in 0 until config.streakLookbackDays) {
            val day = asOf.minusDays(back.toLong())
            if (nightsByDate[day]?.isPresent == true) {
                if (debtAsOf(day, nightsByDate, need).debt >= threshold) break
                days++
                since = day
                missingRun = 0
            } else if (++missingRun > config.streakMaxBridgedMissingDays) {
                break
            }
        }
        return Streak(days, since)
    }

    private companion object {
        const val SECONDS_PER_HOUR = 3600.0
    }
}

private fun Duration.toSecondsDouble(): Double = seconds + nano / 1e9

private fun roundedSeconds(seconds: Double): Duration = Duration.ofSeconds(Math.round(seconds))
