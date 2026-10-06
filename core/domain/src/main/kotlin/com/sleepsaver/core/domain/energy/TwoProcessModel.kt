package com.sleepsaver.core.domain.energy

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.TimeSpan
import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

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

    private val inertiaTauHours = hours(config.inertiaTau)

    /**
     * Process S at [at], starting from `sSeed` at [from] and integrating over [asleep] intervals
     * (sorted or not, may overlap [from]/[at] partially); time outside them is awake.
     */
    fun homeostaticPressure(from: Instant, at: Instant, asleep: List<TimeSpan>): Double {
        var s = config.sSeed
        if (!at.isAfter(from)) return s
        var cursor = from
        for (span in asleep.sortedBy { it.start }) {
            val start = maxOf(span.start, cursor)
            val end = minOf(span.end, at)
            if (!end.isAfter(start)) continue
            s = riseWhileAwake(s, hoursBetween(cursor, start))
            s = decayWhileAsleep(s, hoursBetween(start, end))
            cursor = end
        }
        return riseWhileAwake(s, hoursBetween(cursor, at))
    }

    /** Process C at [t] given typical wake [typicalWake]. */
    fun circadian(t: Instant, typicalWake: Instant): Double {
        val h = hoursBetween(typicalWake, t)
        return config.c24Amplitude * cos(2 * PI * (h - config.c24PeakHoursAfterWake) / 24) +
            config.c12Amplitude * cos(2 * PI * (h - config.c12PeakHoursAfterWake) / 12)
    }

    /** Inertia strength k for a given debt. */
    fun inertiaStrength(debtHours: Double): Double =
        min(config.inertiaBase * (1 + debtHours.coerceAtLeast(0.0) / config.inertiaDebtScaleHours), config.inertiaMax)

    /** I(t); 0 before [actualWake]. */
    fun inertia(t: Instant, actualWake: Instant, debtHours: Double): Double {
        if (t.isBefore(actualWake)) return 0.0
        return -inertiaStrength(debtHours) * exp(-hoursBetween(actualWake, t) / inertiaTauHours)
    }

    /** Minutes after waking until |I| < inertiaEpsilon, i.e. inertiaTau·ln(k/ε) (≥ 0). */
    fun inertiaDurationMinutes(debtHours: Double): Double =
        max(0.0, inertiaTauHours * 60 * ln(inertiaStrength(debtHours) / config.inertiaEpsilon))

    /** S after [hours] awake, starting from [s0]. */
    internal fun riseWhileAwake(s0: Double, hours: Double): Double = 1 - (1 - s0) * exp(-hours / config.sRiseTauHours)

    /** S after [hours] asleep, starting from [s0]. */
    internal fun decayWhileAsleep(s0: Double, hours: Double): Double = s0 * exp(-hours / config.sDecayTauHours)
}

internal fun hours(d: Duration): Double = d.toMillis() / 3_600_000.0

internal fun hoursBetween(from: Instant, to: Instant): Double = hours(Duration.between(from, to))
