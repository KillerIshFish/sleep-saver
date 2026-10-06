package com.sleepsaver.feature.progress.chart

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.VitalSample
import com.sleepsaver.core.domain.model.VitalType
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.feature.progress.ProgressFixtures.assemble
import com.sleepsaver.feature.progress.ProgressFixtures.at
import com.sleepsaver.feature.progress.ProgressFixtures.session
import org.junit.Test
import java.time.Duration
import java.time.LocalDate

class QualityGeometryTest {

    private val zone = SampleData.zone
    private val today = SampleData.today
    private val dates = (13 downTo 0).map { today.minusDays(it.toLong()) }

    @Test
    fun `stage bars stack each night's stages and keep missing nights empty`() {
        val nights = SampleData.nights().filterNot { it.wakeDate == today.minusDays(2) }.associateBy { it.wakeDate }

        val chart = QualityGeometry.stageChart(dates, nights)

        assertThat(chart.bars).hasSize(14)
        val missing = chart.bars.single { it.wakeDate == today.minusDays(2) }
        assertThat(missing.isMissing).isTrue()
        assertThat(missing.hasStages).isFalse()
        val last = chart.bars.last()
        val totals = nights.getValue(today).stageTotals!!
        assertThat(last.deep).isEqualTo(totals.deep)
        assertThat(last.rem).isEqualTo(totals.rem)
        assertThat(last.hasStages).isTrue()
        assertThat(chart.maxHours).isAtLeast(QualityGeometry.MIN_SCALE_HOURS)
        assertThat(chart.maxHours % 2).isEqualTo(0)
        chart.bars.forEach { assertThat(it.total.seconds).isAtMost(chart.maxHours * 3600L) }
        assertThat(chart.tickHours.last()).isEqualTo(chart.maxHours)
    }

    @Test
    fun `nights without stages become a faint bar of their time in bed`() {
        val day = LocalDate.of(2026, 10, 4)
        val nights = assemble(listOf(session("n", at(day.minusDays(1), 23), at(day, 6, 30)))).associateBy { it.wakeDate }

        val bar = QualityGeometry.stageChart(listOf(day), nights).bars.single()

        assertThat(bar.isMissing).isFalse()
        assertThat(bar.hasStages).isFalse()
        assertThat(bar.noStages).isEqualTo(Duration.ofMinutes(450))
        assertThat(bar.total).isEqualTo(Duration.ofMinutes(450))
    }

    @Test
    fun `summary averages efficiency, interruptions, deep and REM over present nights`() {
        val nights = SampleData.nights().associateBy { it.wakeDate }
        val window = dates.mapNotNull { nights[it] }

        val summary = QualityGeometry.summary(dates, nights, emptyList(), zone)

        assertThat(summary.averageEfficiency).isWithin(1e-9).of(window.mapNotNull { it.efficiency }.average())
        assertThat(summary.averageInterruptions).isWithin(1e-9).of(window.map { it.interruptions.size.toDouble() }.average())
        assertThat(summary.averageDeep!!.seconds).isEqualTo(window.sumOf { it.stageTotals!!.deep.seconds } / window.size)
        assertThat(summary.averageRem).isNotNull()
        assertThat(summary.hasVitals).isFalse()
    }

    @Test
    fun `an empty window has no averages`() {
        val summary = QualityGeometry.summary(dates, emptyMap(), emptyList(), zone)

        assertThat(summary.chart.isEmpty).isTrue()
        assertThat(summary.averageEfficiency).isNull()
        assertThat(summary.averageInterruptions).isNull()
        assertThat(summary.averageDeep).isNull()
        assertThat(summary.averageRem).isNull()
    }

    @Test
    fun `vitals are averaged per wake date, evening HRV belongs to the next morning`() {
        fun sample(id: String, type: VitalType, hour: Int, day: LocalDate, value: Double) =
            VitalSample(id, type, at(day, hour).atZone(zone).toInstant(), value, SleepConfig.GARMIN_CONNECT_PACKAGE)
        val yesterday = today.minusDays(1)
        val vitals = listOf(
            sample("r1", VitalType.RESTING_HR, 8, today, 50.0),
            sample("r2", VitalType.RESTING_HR, 20, today, 54.0),
            sample("h1", VitalType.HRV_RMSSD, 23, yesterday, 40.0),
            sample("h2", VitalType.HRV_RMSSD, 4, today, 60.0),
            sample("s", VitalType.STEPS_DAY, 0, today, 8000.0),
        )

        val summary = QualityGeometry.summary(dates, emptyMap(), vitals, zone)

        val rhr = summary.restingHeartRate!!
        assertThat(rhr.values.last()).isEqualTo(52.0)
        assertThat(rhr.values.dropLast(1).all { it == null }).isTrue()
        val hrv = summary.heartRateVariability!!
        assertThat(hrv.values.last()).isEqualTo(50.0)
        assertThat(hrv.values[12]).isNull()
        assertThat(summary.hasVitals).isTrue()
    }

    @Test
    fun `a flat trend still gets a readable axis`() {
        val samples = dates.map { day ->
            VitalSample("r$day", VitalType.RESTING_HR, at(day, 8).atZone(zone).toInstant(), 55.0, SleepConfig.GARMIN_CONNECT_PACKAGE)
        }

        val trend = QualityGeometry.trend(dates, samples, VitalType.RESTING_HR, zone, minSpan = 6.0)!!

        assertThat(trend.axisLow).isEqualTo(52.0)
        assertThat(trend.axisHigh).isEqualTo(58.0)
        assertThat(trend.y(55.0)).isWithin(1e-6f).of(0.5f)
        assertThat(trend.average).isEqualTo(55.0)
        assertThat(QualityGeometry.trend(dates, emptyList(), VitalType.HRV_RMSSD, zone, 10.0)).isNull()
    }
}
