package com.sleepsaver.core.domain.circadian

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.testing.Fx
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class SleepTimingEstimatorTest {

    private val estimator = SleepTimingEstimator()

    /** A Monday. */
    private val monday = LocalDate.of(2026, 10, 5)
    private val saturday = monday.plusDays(5)

    /** The [days] nights before [until]: weekday mornings 23:00 → 06:50 (alarm), weekend mornings 00:30 → 08:40. */
    private fun weeks(until: LocalDate, days: Long = 21): List<Night> = (1..days).map { back ->
        val wakeDate = until.minusDays(back)
        if (wakeDate.isWeekend()) Fx.night(wakeDate, "00:30", "08:40") else Fx.night(wakeDate, "23:00", "06:50")
    }

    @Test
    fun `weekday and weekend wake times are learned apart`() {
        val nights = weeks(monday)

        assertThat(estimator.wakeTime(nights, monday)).isEqualTo(LocalTime.of(6, 50))
        assertThat(estimator.wakeTime(nights, saturday)).isEqualTo(LocalTime.of(8, 40))
    }

    @Test
    fun `fall-asleep times are learned for the night before a weekday or a weekend day`() {
        val nights = weeks(monday)

        assertThat(estimator.fellAsleepTime(nights, monday)).isEqualTo(LocalTime.of(23, 0))
        assertThat(estimator.fellAsleepTime(nights, saturday)).isEqualTo(LocalTime.of(0, 30))
    }

    @Test
    fun `times around midnight average across it, not to noon`() {
        val nights = listOf(
            Fx.night(monday.minusDays(1), "23:50", "07:00"),
            Fx.night(monday.minusDays(2), "00:10", "07:00"),
            Fx.night(monday.minusDays(8), "23:50", "07:00"),
            Fx.night(monday.minusDays(9), "00:10", "07:00"),
        )

        val fellAsleep = estimator.fellAsleepTime(nights, saturday)!!

        assertThat(fellAsleep.isBefore(LocalTime.of(0, 10)) || fellAsleep.isAfter(LocalTime.of(23, 50))).isTrue()
    }

    @Test
    fun `recent nights weigh more`() {
        val nights = listOf(
            Fx.night(monday.minusDays(3), "23:00", "06:00"), // Friday
            Fx.night(monday.minusDays(4), "23:00", "07:00"), // Thursday
            Fx.night(monday.minusDays(5), "23:00", "07:00"), // Wednesday
        )

        val wake = estimator.wakeTime(nights, monday)!!

        // A plain average would be 06:40; the most recent night (06:00) pulls it earlier.
        assertThat(wake).isGreaterThan(LocalTime.of(6, 0))
        assertThat(wake).isLessThan(LocalTime.of(6, 40))
    }

    @Test
    fun `the target night itself is never used`() {
        val nights = weeks(monday) + Fx.night(monday, "02:00", "05:00")

        assertThat(estimator.wakeTime(nights, monday)).isEqualTo(LocalTime.of(6, 50))
    }

    @Test
    fun `with too few nights of the same kind every night counts`() {
        val nights = listOf(
            Fx.night(monday.minusDays(2), "00:00", "09:00"), // the only weekend morning (Saturday)
            Fx.night(monday.minusDays(3), "23:00", "07:00"),
            Fx.night(monday.minusDays(4), "23:00", "07:00"),
            Fx.night(monday.minusDays(5), "23:00", "07:00"),
        )

        val wake = estimator.wakeTime(nights, saturday)!!

        assertThat(wake).isGreaterThan(LocalTime.of(7, 0))
        assertThat(wake).isLessThan(LocalTime.of(9, 0))
    }

    @Test
    fun `not enough nights gives no answer`() {
        val nights = listOf(
            Fx.night(monday.minusDays(1), "23:00", "08:00"), // Sunday
            Fx.night(monday.minusDays(3), "23:00", "07:00"), // Friday
        )

        assertThat(estimator.wakeTime(nights, monday)).isNull()
        assertThat(estimator.wakeTime(emptyList(), monday)).isNull()
    }

    @Test
    fun `nights older than the lookback are ignored`() {
        val old = weeks(monday.minusDays(40))

        assertThat(estimator.wakeTime(old, monday)).isNull()
    }

    @Test
    fun `each night is read in the offset it was recorded in`() {
        val newYork = ZoneOffset.ofHours(-4)
        val nights = (1L..5L).map { Fx.night(monday.minusDays(it + 2), "23:00", "06:50", offset = newYork) }

        assertThat(estimator.wakeTime(nights, monday)).isEqualTo(LocalTime.of(6, 50))
    }

    @Test
    fun `learned timing covers the next weekday and the next weekend day`() {
        val learned = estimator.learned(weeks(monday), monday.minusDays(1))

        assertThat(learned.weekdayWake).isEqualTo(LocalTime.of(6, 50))
        assertThat(learned.weekendWake).isEqualTo(LocalTime.of(8, 40))
        assertThat(learned.weekdayFellAsleep).isEqualTo(LocalTime.of(23, 0))
        assertThat(learned.weekendFellAsleep).isEqualTo(LocalTime.of(0, 30))
    }
}
