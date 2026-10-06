package com.sleepsaver.core.domain.debt

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.DebtChangeBreakdown
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepDebt
import com.sleepsaver.core.domain.model.Streak
import java.time.Duration
import java.time.LocalDate

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
 */
class SleepDebtCalculator(private val config: SleepConfig = SleepConfig.DEFAULT) {

    fun debtAsOf(asOf: LocalDate, nightsByDate: Map<LocalDate, Night>, need: Duration): SleepDebt {
        TODO("M2: sleep debt")
    }

    /** debt(asOf) for every date in [from, to] inclusive, ascending. */
    fun series(from: LocalDate, to: LocalDate, nightsByDate: Map<LocalDate, Night>, need: Duration): List<SleepDebt> {
        TODO("M2: debt series")
    }

    /** debt(asOf) − debt(asOf − 1). */
    fun changeSinceYesterday(asOf: LocalDate, nightsByDate: Map<LocalDate, Night>, need: Duration): Duration {
        TODO("M2: debt change")
    }

    /** See [DebtChangeBreakdown]. */
    fun breakdown(wakeDate: LocalDate, nightsByDate: Map<LocalDate, Night>, need: Duration): DebtChangeBreakdown {
        TODO("M2: debt breakdown for the recalculation screen")
    }

    /** LOW < lowDebtMax ≤ MODERATE < highDebtMin ≤ HIGH. */
    fun status(debt: Duration, lowDebtMax: Duration = config.lowDebtMax, highDebtMin: Duration = config.highDebtMin): DebtStatus {
        TODO("M2: status")
    }

    /** clamp(100 − energyPotentialPerDebtHour · debtHours, floor, ceiling), rounded half-up. */
    fun energyPotential(debt: Duration): Int {
        TODO("M2: energy potential")
    }

    /**
     * Count back from [asOf] while debt(d) < threshold. A day whose own night is missing is skipped
     * (doesn't count, doesn't break) unless more than `streakMaxBridgedMissingDays` missing days in a row,
     * which ends the streak. A day with debt ≥ threshold ends it. Looks back at most `streakLookbackDays`.
     */
    fun streak(asOf: LocalDate, nightsByDate: Map<LocalDate, Night>, need: Duration, threshold: Duration = config.streakThreshold): Streak {
        TODO("M2: streak")
    }
}
