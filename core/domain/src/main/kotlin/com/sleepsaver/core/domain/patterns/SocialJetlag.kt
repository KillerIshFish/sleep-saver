package com.sleepsaver.core.domain.patterns

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.WeekPlan
import java.time.Duration
import java.time.LocalDate

/** Signed: + = mid-sleep later on free days than alarm days. Example: +75 min after weekend lie-ins. */
data class SocialJetlag(val shift: Duration, val freeNights: Int, val workNights: Int, val freeDaysFromPlan: Boolean)

/**
 * Social jetlag over the last jetlagLookbackDays: circular mean mid-sleep on free days minus alarm days. Free days come
 * from the plan when it has alarms (no alarm that date), else Saturday and Sunday (§2.8).
 */
class SocialJetlagCalculator(private val config: SleepConfig = SleepConfig.DEFAULT) {
    fun compute(nights: List<Night>, week: WeekPlan, oneOffs: OneOffs, asOf: LocalDate): SocialJetlag? =
        TODO("B2: SocialJetlagCalculator.compute (§2.8)")
}
