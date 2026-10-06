package com.sleepsaver.feature.progress.chart

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.DebtContribution
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.SleepDebt
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.testing.SampleData
import org.junit.Test
import java.time.Duration
import java.time.LocalDate

class DebtChartGeometryTest {

    private val geometry = DebtChartGeometry()
    private val today = SampleData.today
    private val settings = UserSleepSettings()

    private fun hours(h: Long) = Duration.ofHours(h)

    @Test
    fun `scale is 0 to 12 hours unless debt runs higher`() {
        assertThat(DebtScale.maxHours(null)).isEqualTo(12)
        assertThat(DebtScale.maxHours(Duration.ZERO)).isEqualTo(12)
        assertThat(DebtScale.maxHours(hours(12))).isEqualTo(12)
        assertThat(DebtScale.maxHours(hours(12).plusMinutes(1))).isEqualTo(16)
        assertThat(DebtScale.maxHours(hours(17))).isEqualTo(20)
        assertThat(DebtScale.maxHours(hours(24))).isEqualTo(24)
        assertThat(DebtScale.maxHours(hours(30))).isEqualTo(32)
    }

    @Test
    fun `ticks every 4 hours, every 8 past a day`() {
        assertThat(DebtScale.ticks(12)).containsExactly(0, 4, 8, 12).inOrder()
        assertThat(DebtScale.ticks(20)).containsExactly(0, 4, 8, 12, 16, 20).inOrder()
        assertThat(DebtScale.ticks(32)).containsExactly(0, 8, 16, 24, 32).inOrder()
    }

    @Test
    fun `points follow the engine's debt and are coloured by the user's thresholds`() {
        val nights = SampleData.nights()
        val dates = (13 downTo 0).map { today.minusDays(it.toLong()) }
        val series = SampleData.debtSeries(dates.first(), dates.last(), nights)
        val strict = settings.copy(lowDebtMax = Duration.ofMinutes(30), highDebtMin = Duration.ofHours(1))

        val chart = geometry.chart(dates, series, nights.associateBy { it.wakeDate }, settings, today)
        val strictChart = geometry.chart(dates, series, nights.associateBy { it.wakeDate }, strict, today)

        assertThat(chart.points.map { it.date }).isEqualTo(dates)
        assertThat(chart.points.map { it.debt }).isEqualTo(series.map { it.debt })
        chart.points.forEach { point ->
            assertThat(point.y).isWithin(1e-4f).of(point.debt!!.seconds / (12 * 3600f))
        }
        assertThat(chart.maxHours).isEqualTo(12)
        assertThat(chart.thresholdY).isWithin(1e-6f).of(5 / 12f)
        assertThat(chart.isEmpty).isFalse()
        strictChart.points.filter { it.debt!! >= Duration.ofHours(1) }.forEach { assertThat(it.status).isEqualTo(DebtStatus.HIGH) }
    }

    @Test
    fun `today has no point while last night isn't in`() {
        val nights = SampleData.nights(includeLastNight = false)
        val dates = listOf(today.minusDays(1), today)
        val series = SampleData.debtSeries(dates.first(), dates.last(), nights)

        val chart = geometry.chart(dates, series, nights.associateBy { it.wakeDate }, settings, today)

        assertThat(chart.points[0].debt).isNotNull()
        assertThat(chart.points[1].debt).isNull()
        assertThat(chart.points[1].waiting).isTrue()
        assertThat(chart.points[1].nightMissing).isTrue()
    }

    @Test
    fun `days with no night in their debt window have no point`() {
        val day = LocalDate.of(2026, 1, 1)
        val empty = SleepDebt(
            asOf = day,
            debt = Duration.ZERO,
            rawWeightedSum = Duration.ZERO,
            contributions = listOf(DebtContribution(day, 1, 1.0, null, null, null)),
            missingNights = listOf(day),
        )

        val chart = geometry.chart(listOf(day), listOf(empty), emptyMap(), settings, today)

        assertThat(chart.points.single().debt).isNull()
        assertThat(chart.points.single().y).isNull()
        assertThat(chart.isEmpty).isTrue()
    }

    @Test
    fun `a missing night is marked but its day still has a debt`() {
        val nights = SampleData.nights().filterNot { it.wakeDate == today.minusDays(3) }
        val dates = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val series = SampleData.debtSeries(dates.first(), dates.last(), nights)

        val point = geometry.chart(dates, series, nights.associateBy { it.wakeDate }, settings, today)
            .points.single { it.date == today.minusDays(3) }

        assertThat(point.nightMissing).isTrue()
        assertThat(point.waiting).isFalse()
        assertThat(point.debt).isNotNull()
    }

    @Test
    fun `a high debt extends the scale and keeps the streak line`() {
        val day = today
        val big = SleepDebt(
            asOf = day,
            debt = Duration.ofHours(14),
            rawWeightedSum = Duration.ofHours(14),
            contributions = listOf(DebtContribution(day, 1, 1.0, Duration.ofHours(1), Duration.ofHours(7), Duration.ofHours(7))),
            missingNights = emptyList(),
        )
        val nights = SampleData.nights().filter { it.wakeDate == day }

        val chart = geometry.chart(listOf(day), listOf(big), nights.associateBy { it.wakeDate }, settings, today)

        assertThat(chart.maxHours).isEqualTo(16)
        assertThat(chart.points.single().y).isWithin(1e-6f).of(14 / 16f)
        assertThat(chart.points.single().status).isEqualTo(DebtStatus.HIGH)
        assertThat(chart.thresholdY).isWithin(1e-6f).of(5 / 16f)
    }
}
