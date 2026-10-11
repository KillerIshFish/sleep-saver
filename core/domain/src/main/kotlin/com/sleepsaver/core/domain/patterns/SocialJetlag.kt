package com.sleepsaver.core.domain.patterns

import com.sleepsaver.core.domain.circadian.isWeekend
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
 *
 *  - Nights: present, with fellAsleep and wokeUp, wakeDate in (asOf − jetlagLookbackDays, asOf].
 *  - Mid-sleep = fellAsleep + (wokeUp − fellAsleep) / 2, as a minute of day in the night's wake offset.
 *  - shift = wrapMinutes(circular mean of free nights − circular mean of alarm nights), rounded to the minute.
 *  - Null with fewer than jetlagMinFree free or jetlagMinWork alarm nights.
 *
 * Example: alarm days asleep 23:00–07:00 (mid 03:00), weekends 00:00–09:00 (mid 04:30) → +90 min.
 */
class SocialJetlagCalculator(private val config: SleepConfig = SleepConfig.DEFAULT) {
    fun compute(nights: List<Night>, week: WeekPlan, oneOffs: OneOffs, asOf: LocalDate): SocialJetlag? {
        val keel = config.keel
        val from = asOf.minusDays(keel.jetlagLookbackDays)
        val fromPlan = week.hasAlarms
        val free = ArrayList<Double>()
        val work = ArrayList<Double>()
        nights
            .filter { it.isPresent && it.wakeDate.isAfter(from) && !it.wakeDate.isAfter(asOf) }
            .associateBy { it.wakeDate }
            .values
            .forEach { night ->
                val mid = midSleepMinute(night) ?: return@forEach
                val isFree = if (fromPlan) !week.resolve(night.wakeDate, oneOffs).alarmOn else night.wakeDate.isWeekend()
                if (isFree) free += mid else work += mid
            }
        if (free.size < keel.jetlagMinFree || work.size < keel.jetlagMinWork) return null
        return SocialJetlag(
            shift = roundedMinutes(wrapMinutes(circularMeanMinutes(free) - circularMeanMinutes(work))),
            freeNights = free.size,
            workNights = work.size,
            freeDaysFromPlan = fromPlan,
        )
    }

    private fun midSleepMinute(night: Night): Double? {
        val start = night.fellAsleep ?: return null
        val end = night.wokeUp ?: return null
        val offset = night.wakeOffset() ?: return null
        val mid = start.plusMillis(Duration.between(start, end).toMillis() / 2)
        return minuteOfDay(mid.atOffset(offset).toLocalTime())
    }
}
