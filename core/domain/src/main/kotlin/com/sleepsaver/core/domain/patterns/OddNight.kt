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
 */
class OddNightDetector(private val config: SleepConfig = SleepConfig.DEFAULT) {
    fun check(nights: List<Night>, wakeDate: LocalDate): OddNight? = TODO("B2: OddNightDetector.check (§2.8)")
}
