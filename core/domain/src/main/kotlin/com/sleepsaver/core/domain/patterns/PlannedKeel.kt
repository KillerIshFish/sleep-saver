package com.sleepsaver.core.domain.patterns

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.WeekPlan
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/** The Week plan editor's plan check: the Planned keel and the social-jetlag notice. */
data class PlanCheck(
    val plannedKeel: Int?,
    val plannedBand: KeelBand?,
    /** circular mean W(free) − W(alarm days), wrapped; null without both kinds. */
    val freeDayWakeShift: Duration?,
    /** planned social jetlag (signed). */
    val plannedSocialJetlag: Duration?,
    val warnFreeDayShift: Boolean,
    val warnSocialJetlag: Boolean,
) {
    val warn: Boolean get() = warnFreeDayShift || warnSocialJetlag
}

/**
 * Keel of the plan itself over the next plannedDays wake dates: asleep [B + latency, W] each night, scored like
 * [KeelCalculator.score], plus the free-day wake shift and planned social jetlag (§2.8).
 */
class PlannedKeelCalculator(private val config: SleepConfig = SleepConfig.DEFAULT) {
    /** [from] = the first wake date (tomorrow). Uses ScheduleTimes for W and B. */
    fun check(
        week: WeekPlan,
        oneOffs: OneOffs,
        nights: List<Night>,
        settings: UserSleepSettings,
        from: LocalDate,
        zone: ZoneId,
    ): PlanCheck = TODO("B2: PlannedKeelCalculator.check (§2.8)")
}
