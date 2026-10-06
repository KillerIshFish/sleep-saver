package com.sleepsaver.feature.progress.chart

import com.sleepsaver.core.domain.debt.SleepDebtCalculator
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepDebt
import com.sleepsaver.core.domain.model.UserSleepSettings
import java.time.Duration
import java.time.LocalDate
import kotlin.math.ceil

/**
 * One day on the Sleep debt chart. [debt] is null when there's nothing to show: no night at all in that day's
 * debt window, or today before last night has come in (the home screen shows yesterday's debt then).
 */
data class DebtPoint(
    val date: LocalDate,
    val debt: Duration?,
    val status: DebtStatus?,
    /** 0 at the bottom (no debt), 1 at the top of the scale. */
    val y: Float?,
    /** The day's own night is missing (its debt skips that night). */
    val nightMissing: Boolean,
    /** Today, and last night hasn't arrived yet. */
    val waiting: Boolean,
)

data class DebtChart(
    val points: List<DebtPoint>,
    val maxHours: Int,
    val tickHours: List<Int>,
    val threshold: Duration,
    /** Where the dashed streak-limit line goes (0 bottom … 1 top), null when it's above the scale. */
    val thresholdY: Float?,
) {
    val isEmpty: Boolean get() = points.all { it.debt == null }
}

/** The debt chart's y scale: 0–12 h, extended in 4-hour steps (8-hour steps past a day) when debt runs higher. */
object DebtScale {
    const val BASE_MAX_HOURS = 12

    fun maxHours(maxDebt: Duration?): Int {
        val hours = maxDebt?.let { ceil(it.seconds / 3600.0).toInt() } ?: 0
        if (hours <= BASE_MAX_HOURS) return BASE_MAX_HOURS
        val step = step(hours)
        return ((hours + step - 1) / step) * step
    }

    fun ticks(maxHours: Int): List<Int> = (0..maxHours step step(maxHours)).toList()

    private fun step(hours: Int): Int = 4 * ceil(hours / 24.0).toInt().coerceAtLeast(1)
}

class DebtChartGeometry(private val calculator: SleepDebtCalculator = SleepDebtCalculator()) {

    fun chart(
        dates: List<LocalDate>,
        series: List<SleepDebt>,
        nightsByDate: Map<LocalDate, Night>,
        settings: UserSleepSettings,
        today: LocalDate,
    ): DebtChart {
        val byDate = series.associateBy { it.asOf }
        val raw = dates.map { date ->
            val nightMissing = nightsByDate[date]?.isPresent != true
            val waiting = date == today && nightMissing
            val debt = byDate[date]
                ?.takeIf { !waiting && it.contributions.any { c -> c.totalSleep != null } }
                ?.debt
            Triple(date, debt, nightMissing to waiting)
        }
        val maxHours = DebtScale.maxHours(raw.mapNotNull { it.second }.maxOrNull())
        val scaleSeconds = maxHours * 3600.0
        val points = raw.map { (date, debt, flags) ->
            DebtPoint(
                date = date,
                debt = debt,
                status = debt?.let { calculator.status(it, settings.lowDebtMax, settings.highDebtMin) },
                y = debt?.let { (it.seconds / scaleSeconds).toFloat().coerceIn(0f, 1f) },
                nightMissing = flags.first,
                waiting = flags.second,
            )
        }
        val threshold = settings.streakThreshold
        val thresholdY = (threshold.seconds / scaleSeconds).toFloat().takeIf { it in 0f..1f }
        return DebtChart(points, maxHours, DebtScale.ticks(maxHours), threshold, thresholdY)
    }
}
