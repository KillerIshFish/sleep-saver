package com.sleepsaver.core.domain.debt

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.Streak
import com.sleepsaver.core.domain.testing.Fx
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class SleepDebtCalculatorTest {

    private val calc = SleepDebtCalculator()
    private val need = h(8.0)

    @Nested
    inner class DebtAsOf {

        @Test
        fun `every night at need gives zero debt`() {
            val debt = calc.debtAsOf(D, nightsBack(*Array(14) { need }), need)

            assertThat(debt.asOf).isEqualTo(D)
            assertThat(debt.debt).isEqualTo(Duration.ZERO)
            assertThat(debt.rawWeightedSum).isEqualTo(Duration.ZERO)
            assertThat(debt.missingNights).isEmpty()
            assertThat(debt.contributions.map { it.index }).containsExactlyElementsIn(1..14).inOrder()
            assertThat(debt.contributions.map { it.wakeDate }).isEqualTo((0L..13L).map { D.minusDays(it) })
            assertThat(debt.contributions.map { it.delta }.toSet()).containsExactly(Duration.ZERO)
        }

        @Test
        fun `a short last night adds its whole shortfall`() {
            val debt = calc.debtAsOf(D, nightsBack(h(6.0), *Array(13) { need }), need)

            assertThat(debt.debt).isEqualTo(h(2.0))
            with(debt.contributions.first()) {
                assertThat(index).isEqualTo(1)
                assertThat(weight).isEqualTo(1.0)
                assertThat(totalSleep).isEqualTo(h(6.0))
                assertThat(delta).isEqualTo(h(2.0))
                assertThat(weighted).isEqualTo(h(2.0))
            }
        }

        @Test
        fun `a shortfall two nights back is weighted by the decay`() {
            val debt = calc.debtAsOf(D, nightsBack(need, h(6.0), *Array(12) { need }), need)

            assertThat(debt.debt).isEqualTo(h(1.7))
            assertThat(debt.debt).isEqualTo(m(102))
            assertThat(debt.contributions[1].weight).isWithin(1e-12).of(0.85)
            assertThat(debt.contributions[1].weighted).isEqualTo(m(102))
        }

        @Test
        fun `weights decay geometrically over the window`() {
            val debt = calc.debtAsOf(D, nightsBack(*Array(14) { need }), need)

            debt.contributions.forEachIndexed { i, c ->
                assertThat(c.weight).isWithin(1e-12).of(Math.pow(0.85, i.toDouble()))
            }
        }

        @Test
        fun `surplus credit is capped per night`() {
            val debt = calc.debtAsOf(D, nightsBack(h(12.0), *Array(13) { need }), need)

            with(debt.contributions.first()) {
                assertThat(totalSleep).isEqualTo(h(12.0))
                assertThat(delta).isEqualTo(h(-2.0))
                assertThat(weighted).isEqualTo(h(-2.0))
            }
            assertThat(debt.rawWeightedSum).isEqualTo(h(-2.0))
            assertThat(debt.debt).isEqualTo(Duration.ZERO)
        }

        @Test
        fun `a capped surplus only pays back two hours of earlier debt`() {
            // 4 h short two nights back (0.85 * 4 h = 3.4 h), then a 12 h night credits only 2 h.
            val debt = calc.debtAsOf(D, nightsBack(h(12.0), h(4.0)), need)

            assertThat(debt.debt).isEqualTo(h(1.4))
        }

        @Test
        fun `banked sleep clamps debt at zero but keeps the negative raw sum`() {
            // -2 h, 0.85 * -1 h, 0.7225 * +1 h
            val debt = calc.debtAsOf(D, nightsBack(h(10.0), h(9.0), h(7.0)), need)

            assertThat(debt.contributions.take(3).map { it.weighted })
                .containsExactly(s(-7200), s(-3060), s(2601)).inOrder()
            assertThat(debt.rawWeightedSum).isEqualTo(s(-7659))
            assertThat(debt.debt).isEqualTo(Duration.ZERO)
        }

        @Test
        fun `deficits and capped surpluses add up as weighted deltas`() {
            // +4 h, 0.85 * -2 h, 0.7225 * -2 h (12 h night capped)
            val debt = calc.debtAsOf(D, nightsBack(h(4.0), h(10.0), h(12.0)), need)

            assertThat(debt.contributions[2].delta).isEqualTo(h(-2.0))
            assertThat(debt.rawWeightedSum).isEqualTo(s(14400 - 6120 - 5202))
            assertThat(debt.debt).isEqualTo(s(3078))
        }

        @Test
        fun `missing nights are skipped without renormalising and listed`() {
            val debt = calc.debtAsOf(D, nightsBack(null, h(6.0)), need)

            // Only night 2 is present: its weight stays 0.85, so 1.7 h rather than 2 h.
            assertThat(debt.debt).isEqualTo(h(1.7))
            assertThat(debt.missingNights).isEqualTo(listOf(D) + (2L..13L).map { D.minusDays(it) })
            with(debt.contributions.first()) {
                assertThat(weight).isEqualTo(1.0)
                assertThat(totalSleep).isNull()
                assertThat(delta).isNull()
                assertThat(weighted).isNull()
            }
        }

        @Test
        fun `a day with naps only counts as a missing night`() {
            val napsOnly = Fx.night(D, napSleep = h(1.0)).copy(main = null)
            assertThat(napsOnly.isPresent).isFalse()

            val debt = calc.debtAsOf(D, Fx.byDate(napsOnly, Fx.night(D.minusDays(1), total = h(6.0))), need)

            assertThat(debt.missingNights).contains(D)
            assertThat(debt.contributions.first().totalSleep).isNull()
            assertThat(debt.debt).isEqualTo(h(1.7))
        }

        @Test
        fun `nap sleep counts toward the night total`() {
            val night = Fx.night(D, total = h(6.0), napSleep = h(1.0))

            assertThat(calc.debtAsOf(D, Fx.byDate(night), need).debt).isEqualTo(h(1.0))
        }

        @Test
        fun `the fifteenth night back is outside the window`() {
            val debt = calc.debtAsOf(D, nightsBack(*Array(14) { need }, Duration.ZERO), need)

            assertThat(debt.debt).isEqualTo(Duration.ZERO)
            assertThat(debt.contributions).hasSize(14)
            assertThat(debt.contributions.map { it.wakeDate }).doesNotContain(D.minusDays(14))
            assertThat(debt.missingNights).isEmpty()
        }

        @Test
        fun `a steady one hour shortfall settles near six hours`() {
            val debt = calc.debtAsOf(D, nightsBack(*Array(30) { h(7.0) }), need)

            val expectedHours = (1 - Math.pow(0.85, 14.0)) / 0.15
            assertThat(debt.debt).isEqualTo(s(Math.round(expectedHours * 3600)))
        }

        @Test
        fun `no nights at all gives zero debt with every night missing`() {
            val debt = calc.debtAsOf(D, emptyMap(), need)

            assertThat(debt.debt).isEqualTo(Duration.ZERO)
            assertThat(debt.rawWeightedSum).isEqualTo(Duration.ZERO)
            assertThat(debt.missingNights).hasSize(14)
        }

        @Test
        fun `window length and decay come from the config`() {
            val custom = SleepDebtCalculator(SleepConfig(debtWindowNights = 3, debtDecay = 0.5))

            val debt = custom.debtAsOf(D, nightsBack(h(7.0), h(6.0), h(4.0), h(0.0)), need)

            assertThat(debt.contributions).hasSize(3)
            assertThat(debt.debt).isEqualTo(h(1.0 + 0.5 * 2 + 0.25 * 4))
        }
    }

    @Nested
    inner class TimeZones {

        @Test
        fun `a fall-back night of nine elapsed hours banks one hour`() {
            val wake = LocalDate.of(2026, 11, 1)
            val night = nightInZone(wake, "23:00", "07:00", Fx.NEW_YORK)
            assertThat(night.fellAsleepOffset).isNotEqualTo(night.wokeUpOffset)
            assertThat(night.totalSleep).isEqualTo(h(9.0))

            val debt = calc.debtAsOf(wake, Fx.byDate(night), need)

            assertThat(debt.contributions.first().delta).isEqualTo(h(-1.0))
            assertThat(debt.rawWeightedSum).isEqualTo(h(-1.0))
            assertThat(debt.debt).isEqualTo(Duration.ZERO)
        }

        @Test
        fun `a spring-forward night of seven elapsed hours adds one hour`() {
            val wake = LocalDate.of(2026, 3, 8)
            val night = nightInZone(wake, "23:00", "07:00", Fx.NEW_YORK)
            assertThat(night.totalSleep).isEqualTo(h(7.0))

            assertThat(calc.debtAsOf(wake, Fx.byDate(night), need).debt).isEqualTo(h(1.0))
        }

        @Test
        fun `nights recorded in different offsets count by elapsed duration only`() {
            val newYork = ZoneOffset.ofHours(-4)
            val paris = ZoneOffset.ofHours(1)
            val nights = Fx.byDate(
                Fx.night(D.minusDays(2), "23:00", "07:00", offset = newYork),
                // Overnight flight: 22:00 in New York to 10:00 in Paris is 7 h elapsed.
                nightBetween(D.minusDays(1), Fx.dt(D.minusDays(2), "22:00"), newYork, Fx.dt(D.minusDays(1), "10:00"), paris),
                Fx.night(D, "23:30", "07:30", offset = paris),
            )

            val debt = calc.debtAsOf(D, nights, need)

            assertThat(debt.contributions.take(3).map { it.totalSleep }).containsExactly(need, h(7.0), need).inOrder()
            assertThat(debt.debt).isEqualTo(h(0.85))
        }
    }

    @Nested
    inner class SeriesAndChange {

        @Test
        fun `series returns each day's debt in ascending order`() {
            val nights = nightsBack(need, h(6.0), need, h(5.0))

            val series = calc.series(D.minusDays(3), D, nights, need)

            assertThat(series.map { it.asOf }).isEqualTo((3L downTo 0L).map { D.minusDays(it) })
            assertThat(series).isEqualTo(series.map { calc.debtAsOf(it.asOf, nights, need) })
            assertThat(series[0].debt).isEqualTo(h(3.0))
            assertThat(series[1].debt).isEqualTo(h(0.85 * 3))
        }

        @Test
        fun `series of a single day and of an empty range`() {
            val nights = nightsBack(h(6.0))

            assertThat(calc.series(D, D, nights, need).map { it.debt }).containsExactly(h(2.0))
            assertThat(calc.series(D, D.minusDays(1), nights, need)).isEmpty()
        }

        @Test
        fun `change since yesterday`() {
            val nights = nightsBack(need, h(6.0), *Array(13) { need })

            assertThat(calc.changeSinceYesterday(D.minusDays(1), nights, need)).isEqualTo(h(2.0))
            assertThat(calc.changeSinceYesterday(D, nights, need)).isEqualTo(h(-0.3))
        }

        @Test
        fun `change is zero while nothing moves`() {
            assertThat(calc.changeSinceYesterday(D, nightsBack(*Array(20) { need }), need)).isEqualTo(Duration.ZERO)
        }
    }

    @Nested
    inner class Breakdown {

        @Test
        fun `sleeping exactly the need with prior debt shows a negative fade`() {
            val nights = nightsBack(need, h(6.0), *Array(13) { need })

            val b = calc.breakdown(D, nights, need)

            assertThat(b.wakeDate).isEqualTo(D)
            assertThat(b.need).isEqualTo(need)
            assertThat(b.slept).isEqualTo(need)
            assertThat(b.before).isEqualTo(h(2.0))
            assertThat(b.lastNightDelta).isEqualTo(Duration.ZERO)
            assertThat(b.after).isEqualTo(h(1.7))
            assertThat(b.fade).isEqualTo(h(-0.3))
        }

        @Test
        fun `a short night on top of prior debt`() {
            val nights = nightsBack(h(6.0), h(6.0), *Array(13) { need })

            val b = calc.breakdown(D, nights, need)

            assertThat(b.slept).isEqualTo(h(6.0))
            assertThat(b.before).isEqualTo(h(2.0))
            assertThat(b.lastNightDelta).isEqualTo(h(2.0))
            assertThat(b.after).isEqualTo(h(3.7))
            assertThat(b.fade).isEqualTo(h(-0.3))
        }

        @Test
        fun `last night delta is capped like the debt formula`() {
            val b = calc.breakdown(D, nightsBack(h(11.0), h(4.0)), need)

            assertThat(b.slept).isEqualTo(h(11.0))
            assertThat(b.lastNightDelta).isEqualTo(h(-2.0))
        }

        @Test
        fun `a missing last night shows only fading`() {
            val b = calc.breakdown(D, nightsBack(null, h(6.0)), need)

            assertThat(b.slept).isEqualTo(Duration.ZERO)
            assertThat(b.lastNightDelta).isEqualTo(Duration.ZERO)
            assertThat(b.before).isEqualTo(h(2.0))
            assertThat(b.after).isEqualTo(h(1.7))
            assertThat(b.fade).isEqualTo(h(-0.3))
        }

        @Test
        fun `before and after match the debt on both days and the parts add up`() {
            val totals = listOf(6.5, 9.25, null, 5.0, 8.0, 7.2, 12.0, null, null, 4.75, 8.5, 6.0, 7.0, 10.0, 3.0, 8.0, 7.5, 6.25)
            val nights = nightsBack(*totals.map { hours -> hours?.let { h(it) } }.toTypedArray())

            for (back in 0L..10L) {
                val day = D.minusDays(back)
                val b = calc.breakdown(day, nights, need)
                assertThat(b.before).isEqualTo(calc.debtAsOf(day.minusDays(1), nights, need).debt)
                assertThat(b.after).isEqualTo(calc.debtAsOf(day, nights, need).debt)
                assertThat(b.before.plus(b.lastNightDelta).plus(b.fade)).isEqualTo(b.after)
                assertThat(b.after.minus(b.before)).isEqualTo(calc.changeSinceYesterday(day, nights, need))
            }
        }
    }

    @Nested
    inner class Status {

        @Test
        fun `status boundaries are low-inclusive`() {
            assertThat(calc.status(Duration.ZERO)).isEqualTo(DebtStatus.LOW)
            assertThat(calc.status(h(2.0).minusSeconds(1))).isEqualTo(DebtStatus.LOW)
            assertThat(calc.status(h(2.0))).isEqualTo(DebtStatus.MODERATE)
            assertThat(calc.status(h(5.0).minusSeconds(1))).isEqualTo(DebtStatus.MODERATE)
            assertThat(calc.status(h(5.0))).isEqualTo(DebtStatus.HIGH)
            assertThat(calc.status(h(20.0))).isEqualTo(DebtStatus.HIGH)
        }

        @Test
        fun `status uses the thresholds passed in`() {
            fun status(debt: Duration) = calc.status(debt, lowDebtMax = m(30), highDebtMin = h(1.0))

            assertThat(status(m(29))).isEqualTo(DebtStatus.LOW)
            assertThat(status(m(30))).isEqualTo(DebtStatus.MODERATE)
            assertThat(status(m(59))).isEqualTo(DebtStatus.MODERATE)
            assertThat(status(m(60))).isEqualTo(DebtStatus.HIGH)
        }
    }

    @Nested
    inner class EnergyPotential {

        @ParameterizedTest(name = "{0} min of debt -> {1}")
        @CsvSource(
            "0, 100",
            "6, 100", // 99.5 rounds up
            "18, 99", // 98.5 rounds up
            "78, 94", // 1.3 h -> 93.5
            "240, 80",
            "594, 51", // 50.5 rounds up
            "600, 50",
            "900, 50",
        )
        fun `energy potential from debt`(debtMinutes: Long, expected: Int) {
            assertThat(calc.energyPotential(m(debtMinutes))).isEqualTo(expected)
        }

        @Test
        fun `energy potential follows the config`() {
            val custom = SleepDebtCalculator(
                SleepConfig(energyPotentialPerDebtHour = 10.0, energyPotentialFloor = 20, energyPotentialCeiling = 90),
            )

            assertThat(custom.energyPotential(Duration.ZERO)).isEqualTo(90)
            assertThat(custom.energyPotential(h(3.0))).isEqualTo(70)
            assertThat(custom.energyPotential(h(12.0))).isEqualTo(20)
        }
    }

    @Nested
    inner class StreakCount {

        @Test
        fun `consecutive low-debt days all count`() {
            val nights = nightsOn(D.minusDays(9), D)

            assertThat(calc.streak(D, nights, need)).isEqualTo(Streak(10, D.minusDays(9)))
        }

        @Test
        fun `a high-debt day ends the streak`() {
            // A 0 h night 14 days back: debt 8 h, then 6.8 h, 5.78 h, 4.913 h ...
            val nights = nightsOn(D.minusDays(19), D) + Fx.byDate(Fx.night(D.minusDays(14), total = Duration.ZERO))
            assertThat(calc.debtAsOf(D.minusDays(12), nights, need).debt).isAtLeast(h(5.0))
            assertThat(calc.debtAsOf(D.minusDays(11), nights, need).debt).isLessThan(h(5.0))

            assertThat(calc.streak(D, nights, need)).isEqualTo(Streak(12, D.minusDays(11)))
        }

        @Test
        fun `a high debt on the as-of day gives no streak`() {
            val nights = nightsOn(D.minusDays(5), D.minusDays(1)) + Fx.byDate(Fx.night(D, total = Duration.ZERO))

            assertThat(calc.streak(D, nights, need)).isEqualTo(Streak(0, null))
        }

        @Test
        fun `two missing days in a row are bridged`() {
            val nights = nightsOn(D.minusDays(9), D, skip = setOf(D.minusDays(5), D.minusDays(6)))

            assertThat(calc.streak(D, nights, need)).isEqualTo(Streak(8, D.minusDays(9)))
        }

        @Test
        fun `separate short gaps are each bridged`() {
            val nights = nightsOn(D.minusDays(9), D, skip = setOf(2L, 3L, 5L, 6L).map { D.minusDays(it) }.toSet())

            assertThat(calc.streak(D, nights, need)).isEqualTo(Streak(6, D.minusDays(9)))
        }

        @Test
        fun `three missing days in a row end the streak`() {
            val nights = nightsOn(D.minusDays(9), D, skip = setOf(4L, 5L, 6L).map { D.minusDays(it) }.toSet())

            assertThat(calc.streak(D, nights, need)).isEqualTo(Streak(4, D.minusDays(3)))
        }

        @Test
        fun `missing days at the as-of end are skipped`() {
            val nights = nightsOn(D.minusDays(9), D.minusDays(1))

            assertThat(calc.streak(D, nights, need)).isEqualTo(Streak(9, D.minusDays(9)))
            assertThat(calc.streak(D.plusDays(1), nights, need)).isEqualTo(Streak(9, D.minusDays(9)))
            assertThat(calc.streak(D.plusDays(2), nights, need)).isEqualTo(Streak(0, null))
        }

        @Test
        fun `no nights gives no streak`() {
            assertThat(calc.streak(D, emptyMap(), need)).isEqualTo(Streak(0, null))
        }

        @Test
        fun `the threshold passed in decides`() {
            // A steady 1 h shortfall builds up to about 5.98 h.
            val nights = nightsOn(D.minusDays(19), D, total = h(7.0))

            assertThat(calc.streak(D, nights, need)).isEqualTo(Streak(0, null))
            assertThat(calc.streak(D, nights, need, threshold = h(6.0))).isEqualTo(Streak(20, D.minusDays(19)))
        }

        @Test
        fun `the streak stops at the lookback limit`() {
            val custom = SleepDebtCalculator(SleepConfig(streakLookbackDays = 5))

            assertThat(custom.streak(D, nightsOn(D.minusDays(9), D), need)).isEqualTo(Streak(5, D.minusDays(4)))
        }

        @Test
        fun `the bridged gap length comes from the config`() {
            val strict = SleepDebtCalculator(SleepConfig(streakMaxBridgedMissingDays = 0))
            val nights = nightsOn(D.minusDays(9), D, skip = setOf(D.minusDays(3)))

            assertThat(strict.streak(D, nights, need)).isEqualTo(Streak(3, D.minusDays(2)))
        }
    }

    /** Present nights holding [totals] for wake dates [asOf], asOf − 1, …; a null total leaves that day empty. */
    private fun nightsBack(vararg totals: Duration?, asOf: LocalDate = D): Map<LocalDate, Night> =
        totals.withIndex()
            .mapNotNull { (i, total) -> total?.let { Fx.night(asOf.minusDays(i.toLong()), total = it) } }
            .associateBy { it.wakeDate }

    /** A night at [total] for every wake date in [from, to] except [skip]. */
    private fun nightsOn(
        from: LocalDate,
        to: LocalDate,
        total: Duration = need,
        skip: Set<LocalDate> = emptySet(),
    ): Map<LocalDate, Night> =
        generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .filterNot { it in skip }
            .associateWith { Fx.night(it, total = total) }

    private companion object {
        val D: LocalDate = LocalDate.of(2026, 3, 20)

        fun h(hours: Double): Duration = Duration.ofSeconds(Math.round(hours * 3600))
        fun m(minutes: Long): Duration = Duration.ofMinutes(minutes)
        fun s(seconds: Long): Duration = Duration.ofSeconds(seconds)

        /** A night asleep the whole time from [start] (local, in [startOffset]) to [end] (local, in [endOffset]). */
        fun nightBetween(
            wakeDate: LocalDate,
            start: LocalDateTime,
            startOffset: ZoneOffset,
            end: LocalDateTime,
            endOffset: ZoneOffset,
        ): Night {
            val startInstant = start.toInstant(startOffset)
            val endInstant = end.toInstant(endOffset)
            val asleep = Duration.between(startInstant, endInstant)
            val base = Fx.night(wakeDate)
            return base.copy(
                main = base.main!!.copy(
                    start = startInstant,
                    end = endInstant,
                    startOffset = startOffset,
                    endOffset = endOffset,
                    asleep = asleep,
                ),
                fellAsleep = startInstant,
                wokeUp = endInstant,
                fellAsleepOffset = startOffset,
                wokeUpOffset = endOffset,
                mainSleep = asleep,
                awakeInMain = Duration.ZERO,
                totalSleep = asleep,
            )
        }

        /** Fell asleep the evening before [wakeDate] and woke on it, both local in [zone] (DST-aware offsets). */
        fun nightInZone(wakeDate: LocalDate, fellAsleep: String, wokeUp: String, zone: ZoneId): Night {
            val start = Fx.dt(wakeDate.minusDays(1), fellAsleep)
            val end = Fx.dt(wakeDate, wokeUp)
            return nightBetween(wakeDate, start, Fx.offsetAt(zone, start), end, Fx.offsetAt(zone, end))
        }
    }
}
