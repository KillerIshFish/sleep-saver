package com.sleepsaver.core.domain.patterns

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night
import java.time.LocalDate

/** How a night differed from the personal median. */
enum class OddWay { MORE_SLEEP, LESS_SLEEP, MORE_AWAKE, EARLIER_ONSET, LATER_ONSET }

/** A night unlike the last 28 days, good or bad ("Anything different last night?"). */
data class OddNight(val wakeDate: LocalDate, val ways: Set<OddWay>)

/**
 * The odd-night prompt: last night vs the median of the previous oddLookbackDays (≥ oddMinNights) on total sleep
 * (± oddTotal), awake time (+ oddAwake) and onset (± oddOnset) (§2.8).
 *
 *  - The night itself must be present; the baseline is the present nights with wakeDate in
 *    [wakeDate − oddLookbackDays, wakeDate − 1].
 *  - Type-7 medians of total = main + extra, awake = awakeInMain and onset = noon-shifted local fell-asleep minutes
 *    (so 23:30 and 00:45 are 75 min apart, not 22 h).
 *  - Δtotal ≥ +oddTotal → MORE_SLEEP, ≤ −oddTotal → LESS_SLEEP; Δawake ≥ +oddAwake → MORE_AWAKE;
 *    Δonset ≤ −oddOnset → EARLIER_ONSET, ≥ +oddOnset → LATER_ONSET. Null when nothing applies.
 *
 * Example: a median of 7 h asleep from 23:15, then 8 h 20 m from 22:00 → MORE_SLEEP and EARLIER_ONSET (good nights ask
 * too).
 */
class OddNightDetector(private val config: SleepConfig = SleepConfig.DEFAULT) {
    fun check(nights: List<Night>, wakeDate: LocalDate): OddNight? {
        val journal = config.journal
        val night = nights.lastOrNull { it.wakeDate == wakeDate && it.isPresent } ?: return null
        val from = wakeDate.minusDays(journal.oddLookbackDays)
        val baseline = nights
            .filter { it.isPresent && !it.wakeDate.isBefore(from) && it.wakeDate.isBefore(wakeDate) }
            .associateBy { it.wakeDate }
            .values
        if (baseline.size < journal.oddMinNights) return null

        val ways = LinkedHashSet<OddWay>()
        val totalDelta = totalMinutes(night) - median(baseline.map(::totalMinutes))
        if (totalDelta >= journal.oddTotal.minutesExact()) ways += OddWay.MORE_SLEEP
        if (totalDelta <= -journal.oddTotal.minutesExact()) ways += OddWay.LESS_SLEEP

        val awakeDelta = night.awakeInMain.minutesExact() - median(baseline.map { it.awakeInMain.minutesExact() })
        if (awakeDelta >= journal.oddAwake.minutesExact()) ways += OddWay.MORE_AWAKE

        val onset = night.localFellAsleep()
        val baselineOnsets = baseline.mapNotNull { it.localFellAsleep() }
        if (onset != null && baselineOnsets.isNotEmpty()) {
            val onsetDelta = noonShiftedMinutes(onset) - median(baselineOnsets.map { noonShiftedMinutes(it).toDouble() })
            if (onsetDelta <= -journal.oddOnset.minutesExact()) ways += OddWay.EARLIER_ONSET
            if (onsetDelta >= journal.oddOnset.minutesExact()) ways += OddWay.LATER_ONSET
        }
        return if (ways.isEmpty()) null else OddNight(wakeDate, ways)
    }

    private fun totalMinutes(night: Night): Double = (night.mainSleep + night.extraSleep).minutesExact()
}
