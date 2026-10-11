package com.sleepsaver.core.domain.alarm

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.schedule.SnoozeSettings
import java.time.Instant

/** What the Snooze button offers right now. [lastOne] shows "Last ring" before the button hides. */
data class SnoozeOffer(val until: Instant?, val lastOne: Boolean) {
    val available: Boolean get() = until != null
}

/**
 * Snooze lengths capped by the wake time: TAPERING from 6:12 to a 6:30 wake time with a 10-min overrun gives 6:21,
 * 6:28, 6:30, 6:33, 6:35, then none. After an early ring a snooze never passes the wake time (§2.5).
 */
class SnoozeMath(private val config: SleepConfig = SleepConfig.DEFAULT) {
    /** [used] = snoozes already taken for this occurrence; [target] = the wake time (a catnap's ring time for naps). */
    fun offer(settings: SnoozeSettings, used: Int, now: Instant, target: Instant): SnoozeOffer =
        TODO("B1: SnoozeMath.offer (§2.5)")
}
