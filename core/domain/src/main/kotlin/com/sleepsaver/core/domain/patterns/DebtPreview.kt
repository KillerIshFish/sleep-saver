package com.sleepsaver.core.domain.patterns

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.debt.SleepDebtCalculator
import com.sleepsaver.core.domain.model.BlockKind
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepBlock
import com.sleepsaver.core.domain.model.SleepTiming
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.OverrideKind
import com.sleepsaver.core.domain.schedule.ScheduleTimes
import com.sleepsaver.core.domain.schedule.sleepOpportunity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The Debt preview chip: "Asleep by 11:10 PM → debt tomorrow about 3.1 h (−0.4 h)". Increases are grey, never red.
 */
data class DebtPreview(
    val wakeDate: LocalDate,
    /** Planned bedtime + latency. */
    val asleepBy: Instant,
    val projectedDebt: Duration,
    /** projectedDebt − the debt displayed now (negative = better). */
    val change: Duration,
    val enoughHistory: Boolean,
    /** Present nights with wakeDate in (today − debtPreviewHistoryDays, today]. */
    val presentNights: Int,
)

/**
 * Projects the sleep debt if the plan is followed (DECISIONS A3/A6).
 *
 *  - today = now in [zone]; latency = settings.personalLatency ?: sleepLatency; medianAwake = median awakeInMain of the
 *    last debtPreviewAwakeNights present nights up to today (zero without any).
 *  - For each wake date x from today (or today + 1 once night today is in) through wakeDate: W(x) = ScheduleTimes.wake(x),
 *    B(x − 1) = ScheduleTimes.bedtime(x − 1), predicted = max(0, sleepOpportunity(B, W, latency) − medianAwake), and a
 *    synthetic present night [B + latency, W] asleep `predicted`, in [zone]'s offsets.
 *  - The scrubbed [wakeOverride] acts as a "Just this once" time for wakeDate, so a SLEEP_NEED bedtime follows it the
 *    way it will once saved; [bedtimeOverride] replaces B on the evening before wakeDate.
 *  - projectedDebt = debtAsOf(wakeDate) over real + synthetic nights; change = projectedDebt − the debt displayed now
 *    (debt of today once night today is in, else of yesterday).
 *
 * Example: debt 3.5 h, 8 h need, a plan of 23:00 → 07:00 with 15 min to fall asleep and no awake time: tomorrow's debt
 * is 3.5 h · 0.85 + 15 min.
 */
class DebtProjector(private val config: SleepConfig = SleepConfig.DEFAULT) {

    private val times = ScheduleTimes(config)
    private val debtCalculator = SleepDebtCalculator(config)

    /**
     * Debt on the morning of [wakeDate] if the plan is followed. [wakeOverride] / [bedtimeOverride] (the evening
     * before wakeDate) replace the plan while the user scrubs.
     */
    fun project(
        nights: List<Night>,
        settings: UserSleepSettings,
        now: Instant,
        zone: ZoneId,
        wakeDate: LocalDate,
        wakeOverride: LocalTime? = null,
        bedtimeOverride: LocalTime? = null,
    ): DebtPreview {
        val today = now.atZone(zone).toLocalDate()
        val latency = settings.personalLatency ?: config.sleepLatency
        val need = settings.sleepNeed
        val baseTiming = settings.timing
        val timing = if (wakeOverride == null) {
            baseTiming
        } else {
            val mode = baseTiming.week.resolve(wakeDate, baseTiming.oneOffs).alarmMode
            val once = DateOverride(wakeDate, OverrideKind.JUST_ONCE, time = wakeOverride, mode = mode)
            baseTiming.copy(oneOffs = baseTiming.oneOffs.copy(overrides = baseTiming.oneOffs.overrides + once))
        }

        val present = nights.filter { it.isPresent && !it.wakeDate.isAfter(today) }
        val byDate = nights.associateBy { it.wakeDate }
        val todayIn = byDate[today]?.isPresent == true
        val medianAwake = present.sortedByDescending { it.wakeDate }
            .take(config.anchor.debtPreviewAwakeNights)
            .map { it.awakeInMain.toMillis().toDouble() }
            .takeIf { it.isNotEmpty() }
            ?.let { Duration.ofMillis(Math.round(median(it))) }
            ?: Duration.ZERO

        val projected = HashMap(byDate)
        var asleepBy: Instant? = null
        var x = if (todayIn) today.plusDays(1) else today
        while (!x.isAfter(wakeDate)) {
            val wake = ZonedDateTime.of(x, times.wake(x, nights, timing).first, zone).toInstant()
            val evening = x.minusDays(1)
            val bedtime = if (x == wakeDate && bedtimeOverride != null) {
                times.eveningOf(evening, bedtimeOverride, zone)
            } else {
                times.bedtime(evening, zone, nights, need, timing).first
            }
            val predicted = maxOf(Duration.ZERO, sleepOpportunity(bedtime, wake, latency) - medianAwake)
            projected[x] = syntheticNight(x, minOf(bedtime + latency, wake), wake, predicted, zone)
            if (x == wakeDate) asleepBy = bedtime + latency
            x = x.plusDays(1)
        }

        val displayed = debtCalculator.debtAsOf(if (todayIn) today else today.minusDays(1), byDate, need).debt
        val projectedDebt = debtCalculator.debtAsOf(wakeDate, projected, need).debt
        val historyFrom = today.minusDays(config.anchor.debtPreviewHistoryDays)
        val presentNights = present.count { it.wakeDate.isAfter(historyFrom) }
        return DebtPreview(
            wakeDate = wakeDate,
            asleepBy = asleepBy ?: plannedAsleepBy(wakeDate, zone, nights, need, timing, latency, bedtimeOverride),
            projectedDebt = projectedDebt,
            change = projectedDebt - displayed,
            enoughHistory = presentNights >= config.anchor.debtPreviewMinNights,
            presentNights = presentNights,
        )
    }

    /** asleepBy when [wakeDate]'s night is already in (nothing projected): the plan's bedtime + latency all the same. */
    private fun plannedAsleepBy(
        wakeDate: LocalDate,
        zone: ZoneId,
        nights: List<Night>,
        need: Duration,
        timing: SleepTiming,
        latency: Duration,
        bedtimeOverride: LocalTime?,
    ): Instant {
        val evening = wakeDate.minusDays(1)
        val bedtime = bedtimeOverride?.let { times.eveningOf(evening, it, zone) }
            ?: times.bedtime(evening, zone, nights, need, timing).first
        return bedtime + latency
    }

    private fun syntheticNight(wakeDate: LocalDate, start: Instant, end: Instant, asleep: Duration, zone: ZoneId): Night {
        val startOffset = zone.rules.getOffset(start)
        val endOffset = zone.rules.getOffset(end)
        val block = SleepBlock(
            kind = BlockKind.MAIN,
            start = start,
            end = end,
            startOffset = startOffset,
            endOffset = endOffset,
            asleep = asleep,
            sessionIds = listOf("preview:$wakeDate"),
        )
        return Night(
            wakeDate = wakeDate,
            main = block,
            fellAsleep = start,
            wokeUp = end,
            fellAsleepOffset = startOffset,
            wokeUpOffset = endOffset,
            mainSleep = asleep,
            awakeInMain = maxOf(Duration.ZERO, Duration.between(start, end) - asleep),
            extraSleep = Duration.ZERO,
            napSleep = Duration.ZERO,
            totalSleep = asleep,
            stageTotals = null,
        )
    }
}
