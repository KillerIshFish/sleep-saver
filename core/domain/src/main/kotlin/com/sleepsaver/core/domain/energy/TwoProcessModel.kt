package com.sleepsaver.core.domain.energy

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.TimeSpan
import java.time.Instant

/**
 * Borbély-style two-process model (docs/PLAN.md §3.6). Hours are the unit for every constant.
 *
 *   S awake:  S(t) = 1 − (1 − S0)·e^(−t/sRiseTauHours)
 *   S asleep: S(t) = S0·e^(−t/sDecayTauHours)
 *   C(h)    = c24Amplitude·cos(2π(h − (W + c24PeakHoursAfterWake))/24) + c12Amplitude·cos(2π(h − (W + c12PeakHoursAfterWake))/12)
 *             where h − W is measured in hours from typical wake W.
 *   I(t)    = −k·e^(−(t − Wa)/inertiaTau) for t ≥ Wa, k = min(inertiaBase·(1 + debtHours/inertiaDebtScaleHours), inertiaMax)
 *   E       = C − S + I (raw; normalisation happens in the schedule calculator)
 */
class TwoProcessModel(private val config: SleepConfig = SleepConfig.DEFAULT) {

    /**
     * Process S at [at], starting from `sSeed` at [from] and integrating over [asleep] intervals
     * (sorted or not, may overlap [from]/[at] partially); time outside them is awake.
     */
    fun homeostaticPressure(from: Instant, at: Instant, asleep: List<TimeSpan>): Double {
        TODO("M2: process S")
    }

    /** Process C at [t] given typical wake [typicalWake]. */
    fun circadian(t: Instant, typicalWake: Instant): Double {
        TODO("M2: process C")
    }

    /** Inertia strength k for a given debt. */
    fun inertiaStrength(debtHours: Double): Double {
        TODO("M2: inertia k")
    }

    /** I(t); 0 before [actualWake]. */
    fun inertia(t: Instant, actualWake: Instant, debtHours: Double): Double {
        TODO("M2: inertia")
    }

    /** Minutes after waking until |I| < inertiaEpsilon, i.e. inertiaTau·ln(k/ε) (≥ 0). */
    fun inertiaDurationMinutes(debtHours: Double): Double {
        TODO("M2: grogginess length")
    }
}
