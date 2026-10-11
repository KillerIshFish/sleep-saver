package com.sleepsaver.core.domain.alarm

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.schedule.SnoozeSettings
import com.sleepsaver.core.domain.schedule.SnoozeStyle
import java.time.Duration
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
    fun offer(settings: SnoozeSettings, used: Int, now: Instant, target: Instant): SnoozeOffer {
        val until = until(settings, used, now, target) ?: return SnoozeOffer(until = null, lastOne = false)
        return SnoozeOffer(until = until, lastOne = until(settings, used + 1, until, target) == null)
    }

    /** min(now + base(used), cap); null when no snooze is left or it would be shorter than snoozeMinLength. */
    private fun until(settings: SnoozeSettings, used: Int, now: Instant, target: Instant): Instant? {
        val base = when (settings.style) {
            SnoozeStyle.TAPERING -> config.alarm.taperingSnoozes.getOrNull(used)
            SnoozeStyle.FIXED -> settings.fixedLength.takeIf { used < settings.fixedCount }
            SnoozeStyle.NONE -> null
        } ?: return null
        val cap = if (now.isBefore(target)) target else target + settings.overrun
        val until = minOf(now + base, cap)
        return until.takeIf { Duration.between(now, it) >= config.alarm.snoozeMinLength }
    }
}
