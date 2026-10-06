package com.sleepsaver.feature.progress.chart

import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.VitalSample
import com.sleepsaver.core.domain.model.VitalType
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor

/**
 * One night's stacked stage bar (bottom to top: deep, light, REM, other asleep, awake).
 * A night without stage data has only [noStages] = its time in bed, drawn as a faint bar.
 */
data class StageBar(
    val wakeDate: LocalDate,
    val deep: Duration = Duration.ZERO,
    val light: Duration = Duration.ZERO,
    val rem: Duration = Duration.ZERO,
    /** Asleep but not classified. */
    val other: Duration = Duration.ZERO,
    val awake: Duration = Duration.ZERO,
    val noStages: Duration? = null,
    val isMissing: Boolean = false,
) {
    val hasStages: Boolean get() = !isMissing && noStages == null
    val total: Duration get() = noStages ?: (deep + light + rem + other + awake)
}

data class StageChart(val bars: List<StageBar>, val maxHours: Int, val tickHours: List<Int>) {
    val isEmpty: Boolean get() = bars.all { it.isMissing }
}

/** A heart-rate style trend over the window: one averaged value per wake date (null = no reading). */
data class VitalTrend(
    val values: List<Double?>,
    val average: Double,
    /** Axis bounds: a little below the lowest and above the highest value. */
    val axisLow: Double,
    val axisHigh: Double,
) {
    /** 0 at the bottom of the axis, 1 at the top. */
    fun y(value: Double): Float = ((value - axisLow) / (axisHigh - axisLow)).toFloat().coerceIn(0f, 1f)
    val min: Double get() = values.filterNotNull().min()
    val max: Double get() = values.filterNotNull().max()
}

data class QualitySummary(
    val chart: StageChart,
    /** Average main sleep / time in bed over present nights, 0..1. */
    val averageEfficiency: Double?,
    /** Average number of interruptions per present night. */
    val averageInterruptions: Double?,
    val averageDeep: Duration?,
    val averageRem: Duration?,
    val restingHeartRate: VitalTrend?,
    val heartRateVariability: VitalTrend?,
) {
    val hasVitals: Boolean get() = restingHeartRate != null || heartRateVariability != null
}

object QualityGeometry {
    /** The stage chart's scale never drops below this many hours, so a short night doesn't fill the plot. */
    const val MIN_SCALE_HOURS = 8

    fun summary(
        dates: List<LocalDate>,
        nightsByDate: Map<LocalDate, Night>,
        vitals: List<VitalSample>,
        zone: ZoneId,
    ): QualitySummary {
        val nights = dates.mapNotNull { nightsByDate[it]?.takeIf(Night::isPresent) }
        val staged = nights.mapNotNull { night -> night.stageTotals?.takeIf { it.hasStageData } }
        return QualitySummary(
            chart = stageChart(dates, nightsByDate),
            averageEfficiency = nights.mapNotNull { it.efficiency }.averageOrNull(),
            averageInterruptions = nights.map { it.interruptions.size.toDouble() }.averageOrNull(),
            averageDeep = staged.map { it.deep }.averageDuration(),
            averageRem = staged.map { it.rem }.averageDuration(),
            restingHeartRate = trend(dates, vitals, VitalType.RESTING_HR, zone, minSpan = 6.0),
            heartRateVariability = trend(dates, vitals, VitalType.HRV_RMSSD, zone, minSpan = 10.0),
        )
    }

    fun stageChart(dates: List<LocalDate>, nightsByDate: Map<LocalDate, Night>): StageChart {
        val bars = dates.map { date ->
            val night = nightsByDate[date]?.takeIf(Night::isPresent)
            val stages = night?.stageTotals?.takeIf { it.hasStageData }
            when {
                night == null -> StageBar(date, isMissing = true)
                stages == null -> StageBar(date, noStages = night.timeInBed ?: night.mainSleep)
                else -> StageBar(
                    wakeDate = date,
                    deep = stages.deep,
                    light = stages.light,
                    rem = stages.rem,
                    other = stages.unclassified,
                    awake = stages.awake,
                )
            }
        }
        val maxSeconds = bars.maxOfOrNull { it.total.seconds } ?: 0L
        val hours = ceil(maxSeconds / 3600.0).toInt()
        val maxHours = maxOf(MIN_SCALE_HOURS, hours + hours % 2)
        return StageChart(bars, maxHours, (0..maxHours step 2).toList())
    }

    /**
     * Averages [type] samples per wake date. Resting heart rate is a daily value, so it belongs to its local
     * date; HRV is measured overnight, so a reading from the evening belongs to the next morning (same rule as
     * a night's wake date).
     */
    fun trend(
        dates: List<LocalDate>,
        vitals: List<VitalSample>,
        type: VitalType,
        zone: ZoneId,
        minSpan: Double,
    ): VitalTrend? {
        val byDate = vitals
            .filter { it.type == type }
            .groupBy { wakeDateOf(it, zone) }
            .mapValues { (_, samples) -> samples.map { it.value }.average() }
        val values = dates.map { byDate[it] }
        val present = values.filterNotNull()
        if (present.isEmpty()) return null
        val low = present.min()
        val high = present.max()
        val pad = maxOf((minSpan - (high - low)) / 2, (high - low) * 0.15)
        return VitalTrend(values, present.average(), floor(low - pad), ceil(high + pad))
    }

    internal fun wakeDateOf(sample: VitalSample, zone: ZoneId): LocalDate {
        val local = LocalDateTime.ofInstant(sample.time, zone)
        return when {
            sample.type == VitalType.HRV_RMSSD && local.toLocalTime() >= OvernightCutoff -> local.toLocalDate().plusDays(1)
            else -> local.toLocalDate()
        }
    }

    private val OvernightCutoff: LocalTime = LocalTime.of(14, 0)
}

private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

private fun List<Duration>.averageDuration(): Duration? =
    if (isEmpty()) null else Duration.ofSeconds(sumOf { it.seconds } / size)
