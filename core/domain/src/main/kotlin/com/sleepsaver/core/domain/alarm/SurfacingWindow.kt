package com.sleepsaver.core.domain.alarm

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.schedule.AlarmMode
import java.time.Duration
import java.time.Instant

/**
 * The surfacing window as armed tonight: base window, widened on debt-aware nights. Zero length for FIXED.
 * Example: a 30-min window for 06:30 on a debt-aware night starts at 05:50.
 */
data class SurfacingWindow(val start: Instant, val target: Instant, val debtAware: Boolean) {
    val length: Duration get() = Duration.between(start, target)
}

/** Tonight's effective window for an occurrence (DECISIONS A1: debt-aware nights widen it by 10 min, cap 45). */
object SurfacingWindows {
    /**
     * debtAware = (opportunity != null && opportunity < shortNightOpportunity) || status in {MODERATE, HIGH};
     * length = base window (+ debtAwareWidening when debtAware), capped at windowMax; FIXED → (target, target, debtAware).
     */
    fun effective(
        occurrence: AlarmOccurrence,
        plannedOpportunity: Duration?,
        status: DebtStatus?,
        config: SleepConfig = SleepConfig.DEFAULT,
    ): SurfacingWindow {
        val alarm = config.alarm
        val debtAware = (plannedOpportunity != null && plannedOpportunity < alarm.shortNightOpportunity) ||
            status == DebtStatus.MODERATE || status == DebtStatus.HIGH
        val target = occurrence.target
        if (occurrence.settings.mode != AlarmMode.SURFACING) return SurfacingWindow(target, target, debtAware)
        val widened = if (debtAware) occurrence.window + alarm.debtAwareWidening else occurrence.window
        val length = minOf(widened, alarm.windowMax)
        return SurfacingWindow(target - length, target, debtAware)
    }
}
