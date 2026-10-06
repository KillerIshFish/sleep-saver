package com.sleepsaver.core.domain.need

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.Confidence
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepNeedEstimate
import com.sleepsaver.core.domain.model.SleepNeedEstimateResult
import com.sleepsaver.core.domain.testing.Fx
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.random.Random
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SleepNeedEstimatorTest {

    private val estimator = SleepNeedEstimator()
    private val need = h(8)

    @Nested
    inner class Estimate {

        @Test
        fun `fewer than 14 nights is not enough data`() {
            val nights = typicalWeeks(13)

            assertThat(estimator.estimate(nights, need, nights.today())).isEqualTo(
                SleepNeedEstimateResult.NotEnoughData(
                    nightsSoFar = 13,
                    nightsRequired = 14,
                    freeNightsSoFar = 3,
                    freeNightsRequired = 4,
                ),
            )
        }

        @Test
        fun `14 nights with four free nights is enough`() {
            val nights = typicalWeeks(14)

            val estimate = estimator.estimate(nights, need, nights.today()).available()

            assertThat(estimate.freeNightsUsed).isEqualTo(4)
            assertThat(estimate.need).isEqualTo(m(515))
        }

        @Test
        fun `weekday alarms and weekend lie-ins give the weekend sleep length`() {
            val nights = typicalWeeks(35)

            val estimate = estimator.estimate(nights, need, nights.today()).available()

            assertThat(estimate).isEqualTo(
                SleepNeedEstimate(
                    need = m(515),
                    freeNightsUsed = 10,
                    interquartileRange = Duration.ZERO,
                    confidence = Confidence.HIGH,
                    typicalWeekdayWake = LocalTime.of(6, 30),
                ),
            )
        }

        @Test
        fun `a varied weekend median is rounded to five minutes`() {
            // Sorted: 500 505 508 511 513 | 519 519 522 525 530 -> median 516 min -> 8 h 35 m.
            val totals = listOf(500L, 530, 511, 519, 505, 525, 513, 519, 508, 522).map { m(it) }
            val nights = weeksWithFreeNights(totals)

            val estimate = estimator.estimate(nights, need, nights.today()).available()

            assertThat(estimate.need).isEqualTo(m(515))
            assertThat(estimate.freeNightsUsed).isEqualTo(10)
            // Q1 = 508 + 0.25 * 3, Q3 = 519 + 0.75 * 3
            assertThat(estimate.interquartileRange).isEqualTo(Duration.ofSeconds(750))
            assertThat(estimate.confidence).isEqualTo(Confidence.HIGH)
        }

        @Test
        fun `an even count averages the middle values and rounds half up`() {
            val nights = weeksWithFreeNights(listOf(510L, 515, 520, 525).map { m(it) })

            // median 517.5 min -> 520 min
            assertThat(estimator.estimate(nights, need, nights.today()).available().need).isEqualTo(m(520))
        }

        @Test
        fun `the first recovery night after a short night is dropped`() {
            // Free nights land on Sat, Sun, Sat, Sun, Sat; the third Saturday follows a 5 h Friday.
            val friday = MONDAY.plusDays(18)
            val nights = weeksWithFreeNights(listOf(h(8), h(8), h(9), h(9), m(630)))
                .map { if (it.wakeDate == friday) workNight(friday, total = h(5)) else it }

            val estimate = estimator.estimate(nights, need, nights.today()).available()

            assertThat(estimate.freeNightsUsed).isEqualTo(4)
            assertThat(estimate.need).isEqualTo(m(510))
        }

        @Test
        fun `a short night is judged against the current need`() {
            val friday = MONDAY.plusDays(18)
            val nights = weeksWithFreeNights(listOf(h(8), h(8), h(9), h(9), m(630)))
                .map { if (it.wakeDate == friday) workNight(friday, total = h(5)) else it }

            // With a 6 h need, 5 h is not shorter than need - 60 min, so the long Saturday stays in.
            val estimate = estimator.estimate(nights, h(6), nights.today()).available()

            assertThat(estimate.freeNightsUsed).isEqualTo(5)
            assertThat(estimate.need).isEqualTo(h(9))
        }

        @Test
        fun `only the first recovery night is dropped`() {
            val friday = MONDAY.plusDays(18)
            val saturday = MONDAY.plusDays(19)
            val nights = typicalWeeks(35).map {
                when (it.wakeDate) {
                    friday -> workNight(friday, total = h(5))
                    saturday -> lieIn(saturday, total = h(11))
                    else -> it
                }
            }

            val estimate = estimator.estimate(nights, need, nights.today()).available()

            assertThat(estimate.freeNightsUsed).isEqualTo(9)
            assertThat(estimate.need).isEqualTo(m(515))
        }

        @Test
        fun `long estimates are clamped to the maximum`() {
            val nights = weeksWithFreeNights(List(8) { m(690) })

            assertThat(estimator.estimate(nights, need, nights.today()).available().need).isEqualTo(m(630))
        }

        @Test
        fun `short estimates are clamped to the minimum`() {
            val nights = weeksWithFreeNights(List(8) { h(5) })

            val estimate = estimator.estimate(nights, h(6), nights.today()).available()

            assertThat(estimate.freeNightsUsed).isEqualTo(8)
            assertThat(estimate.need).isEqualTo(h(6))
        }

        @Test
        fun `fewer than four free nights is not enough data`() {
            val few = weeksWithFreeNights(List(3) { m(515) })
            val many = weeksWithFreeNights(List(3) { m(515) }, minNights = 35)

            assertThat(estimator.estimate(few, need, few.today()))
                .isEqualTo(SleepNeedEstimateResult.NotEnoughData(14, 14, 3, 4))
            assertThat(estimator.estimate(many, need, many.today()))
                .isEqualTo(SleepNeedEstimateResult.NotEnoughData(35, 14, 3, 4))
        }

        @Test
        fun `the typical weekday wake is a median and the free threshold is inclusive`() {
            // 20 weekday wakes: ten at 06:20, ten at 06:40 -> 06:30, so free nights start at 07:15.
            val nights = (0L until 28L).map { i ->
                val date = MONDAY.plusDays(i)
                when {
                    date.dayOfWeek == DayOfWeek.SATURDAY -> Fx.night(date, "22:15", "07:15")
                    date.dayOfWeek == DayOfWeek.SUNDAY -> Fx.night(date, "00:14", "07:14")
                    i < 14 -> Fx.night(date, "23:20", "06:20")
                    else -> Fx.night(date, "23:40", "06:40")
                }
            }

            val estimate = estimator.estimate(nights, need, nights.today()).available()

            assertThat(estimate.typicalWeekdayWake).isEqualTo(LocalTime.of(6, 30))
            assertThat(estimate.freeNightsUsed).isEqualTo(4)
            assertThat(estimate.need).isEqualTo(h(9))
        }

        @Test
        fun `wake times are read in each night's own offset`() {
            val nights = typicalWeeks(35) { date ->
                if (date.dayOfMonth % 2 == 0) ZoneOffset.ofHours(9) else ZoneOffset.ofHours(-5)
            }

            assertThat(estimator.estimate(nights, need, nights.today()))
                .isEqualTo(estimator.estimate(typicalWeeks(35), need, nights.today()))
        }

        @Test
        fun `only the most recent 60 nights are used`() {
            val recent = typicalWeeks(63)
            val older = (1L..30L).map { lieIn(MONDAY.minusDays(it), total = m(630)) }

            val estimate = estimator.estimate(older + recent, need, recent.today()).available()

            // 60 most recent drops the first Monday-Wednesday and every older night: 9 weekends remain.
            assertThat(estimate.freeNightsUsed).isEqualTo(18)
            assertThat(estimate.need).isEqualTo(m(515))
        }

        @Test
        fun `nights after today are ignored`() {
            val nights = typicalWeeks(35)
            val future = (1L..10L).map { lieIn(nights.today().plusDays(it), total = m(630)) }

            assertThat(estimator.estimate(nights + future, need, nights.today()))
                .isEqualTo(estimator.estimate(nights, need, nights.today()))
        }

        @Test
        fun `input order and nights without main sleep do not matter`() {
            val nights = typicalWeeks(35)
            val napsOnly = (1L..3L).map { Fx.night(MONDAY.minusDays(it), napSleep = h(1)).copy(main = null) }

            assertThat(estimator.estimate((nights + napsOnly).shuffled(Random(7)), need, nights.today()))
                .isEqualTo(estimator.estimate(nights, need, nights.today()))

            val short = typicalWeeks(13) + napsOnly
            val result = estimator.estimate(short, need, short.maxOf { it.wakeDate })
            assertThat((result as SleepNeedEstimateResult.NotEnoughData).nightsSoFar).isEqualTo(13)
        }

        @Test
        fun `no nights at all is not enough data`() {
            assertThat(estimator.estimate(emptyList(), need, MONDAY))
                .isEqualTo(SleepNeedEstimateResult.NotEnoughData(0, 14, 0, 4))
        }

        @Test
        fun `without weekday nights there is no typical wake and no estimate`() {
            val weekends = typicalWeeks(70).filter { it.wakeDate.isWeekend() }

            assertThat(estimator.estimate(weekends, need, weekends.today()))
                .isEqualTo(SleepNeedEstimateResult.NotEnoughData(20, 14, 0, 4))
        }

        @Test
        fun `a late wake on a weekday counts as a free night`() {
            val wednesday = MONDAY.plusDays(16)
            val nights = typicalWeeks(35).map { if (it.wakeDate == wednesday) lieIn(wednesday, total = h(9)) else it }

            val estimate = estimator.estimate(nights, need, nights.today()).available()

            assertThat(estimate.typicalWeekdayWake).isEqualTo(LocalTime.of(6, 30))
            assertThat(estimate.freeNightsUsed).isEqualTo(11)
            assertThat(estimate.need).isEqualTo(m(515))
        }

        @Test
        fun `a present night without a wake time counts as a night but never as free`() {
            val nights = typicalWeeks(14).map { if (it.wakeDate.dayOfWeek == DayOfWeek.SUNDAY) it.copy(wokeUp = null) else it }

            assertThat(estimator.estimate(nights, need, nights.today()))
                .isEqualTo(SleepNeedEstimateResult.NotEnoughData(14, 14, 2, 4))
        }
    }

    @Nested
    inner class ConfidenceLevels {

        @Test
        fun `many consistent free nights give high confidence`() {
            // n = 9: Q1 = 480, Q3 = 525 -> IQR exactly 45 min.
            val estimate = estimateFromFreeNights(450, 465, 480, 490, 500, 510, 525, 540, 555)

            assertThat(estimate.interquartileRange).isEqualTo(m(45))
            assertThat(estimate.confidence).isEqualTo(Confidence.HIGH)
            assertThat(estimate.need).isEqualTo(m(500))
        }

        @Test
        fun `many free nights with a wider spread give medium confidence`() {
            val estimate = estimateFromFreeNights(450, 465, 480, 490, 500, 510, 526, 540, 555)

            assertThat(estimate.interquartileRange).isEqualTo(m(46))
            assertThat(estimate.confidence).isEqualTo(Confidence.MEDIUM)
        }

        @Test
        fun `a handful of consistent free nights give medium confidence`() {
            // n = 5: Q1 = 500, Q3 = 540.
            val estimate = estimateFromFreeNights(480, 500, 520, 540, 560)

            assertThat(estimate.interquartileRange).isEqualTo(m(40))
            assertThat(estimate.confidence).isEqualTo(Confidence.MEDIUM)
            assertThat(estimate.need).isEqualTo(m(520))
        }

        @Test
        fun `the medium spread limit is inclusive`() {
            assertThat(estimateFromFreeNights(480, 490, 520, 565, 570).confidence).isEqualTo(Confidence.MEDIUM)
            assertThat(estimateFromFreeNights(480, 490, 520, 566, 570).confidence).isEqualTo(Confidence.LOW)
        }

        @Test
        fun `four free nights give low confidence however tight`() {
            assertThat(estimateFromFreeNights(515, 515, 515, 515).confidence).isEqualTo(Confidence.LOW)
        }

        @Test
        fun `the interquartile range interpolates between ranks`() {
            // n = 4: Q1 = 420 + 0.75 * 60 = 465, Q3 = 540 + 0.25 * 60 = 555.
            val estimate = estimateFromFreeNights(420, 480, 540, 600)

            assertThat(estimate.interquartileRange).isEqualTo(m(90))
            assertThat(estimate.need).isEqualTo(m(510))
            assertThat(estimate.confidence).isEqualTo(Confidence.LOW)
        }

        @Test
        fun `many scattered free nights give low confidence`() {
            val estimate = estimateFromFreeNights(450, 465, 480, 490, 500, 510, 560, 570, 580)

            assertThat(estimate.interquartileRange).isEqualTo(m(80))
            assertThat(estimate.confidence).isEqualTo(Confidence.LOW)
        }

        private fun estimateFromFreeNights(vararg minutes: Long): SleepNeedEstimate {
            val nights = weeksWithFreeNights(minutes.map { m(it) })
            return estimator.estimate(nights, need, nights.today()).available()
                .also { assertThat(it.freeNightsUsed).isEqualTo(minutes.size) }
        }
    }

    @Nested
    inner class ShouldOffer {

        private val now = Instant.parse("2026-10-06T08:00:00Z")

        @Test
        fun `not offered when the estimate is within 15 minutes of the current need`() {
            assertThat(offer(estimate = m(490), current = h(8))).isFalse()
            assertThat(offer(estimate = m(466), current = h(8))).isFalse()
            assertThat(offer(estimate = h(8), current = h(8))).isFalse()
        }

        @Test
        fun `offered when the estimate differs by 15 minutes or more and was never dismissed`() {
            assertThat(offer(estimate = m(495), current = h(8))).isTrue()
            assertThat(offer(estimate = m(465), current = h(8))).isTrue()
            assertThat(offer(estimate = m(515), current = h(8))).isTrue()
        }

        @Test
        fun `not offered again soon after a dismissal even if the estimate moved`() {
            val dismissedAt = now.minus(Duration.ofDays(2))

            assertThat(offer(estimate = m(515), current = h(8), dismissed = m(485), dismissedAt = dismissedAt)).isFalse()
        }

        @Test
        fun `not offered again long after a dismissal when the estimate barely moved`() {
            val dismissedAt = now.minus(Duration.ofDays(30))

            assertThat(offer(estimate = m(515), current = h(8), dismissed = m(515), dismissedAt = dismissedAt)).isFalse()
            assertThat(offer(estimate = m(515), current = h(8), dismissed = m(501), dismissedAt = dismissedAt)).isFalse()
        }

        @Test
        fun `offered again once the estimate moved 15 minutes and 7 days passed`() {
            val weekAgo = now.minus(Duration.ofDays(7))

            assertThat(offer(estimate = m(515), current = h(8), dismissed = m(500), dismissedAt = weekAgo)).isTrue()
            assertThat(offer(estimate = m(485), current = h(8), dismissed = m(500), dismissedAt = weekAgo)).isFalse()
            assertThat(offer(estimate = m(515), current = h(8), dismissed = m(500), dismissedAt = weekAgo.plusSeconds(1)))
                .isFalse()
        }

        @Test
        fun `never offered close to the current need even long after a dismissal`() {
            val dismissedAt = now.minus(Duration.ofDays(60))

            assertThat(offer(estimate = m(490), current = h(8), dismissed = m(540), dismissedAt = dismissedAt)).isFalse()
        }

        private fun offer(
            estimate: Duration,
            current: Duration,
            dismissed: Duration? = null,
            dismissedAt: Instant? = null,
        ): Boolean = estimator.shouldOffer(
            estimate = SleepNeedEstimate(estimate, 10, m(20), Confidence.HIGH, LocalTime.of(6, 30)),
            currentNeed = current,
            lastDismissedValue = dismissed,
            lastDismissedAt = dismissedAt,
            now = now,
        )
    }

    private fun SleepNeedEstimateResult.available(): SleepNeedEstimate {
        assertThat(this).isInstanceOf(SleepNeedEstimateResult.Available::class.java)
        return (this as SleepNeedEstimateResult.Available).estimate
    }

    private companion object {
        /** A Monday; every builder below starts here. */
        val MONDAY: LocalDate = LocalDate.of(2026, 8, 3).also { check(it.dayOfWeek == DayOfWeek.MONDAY) }

        fun h(hours: Long): Duration = Duration.ofHours(hours)
        fun m(minutes: Long): Duration = Duration.ofMinutes(minutes)

        fun List<Night>.today(): LocalDate = maxOf { it.wakeDate }

        fun LocalDate.isWeekend(): Boolean = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY

        /** Alarm night: asleep 23:30, up at 06:30 (7 h). */
        fun workNight(date: LocalDate, total: Duration? = null, offset: ZoneOffset = Fx.UTC): Night =
            Fx.night(date, fellAsleep = "23:30", wokeUp = "06:30", total = total, offset = offset)

        /** Lie-in: asleep 00:10, up at 08:45 (8 h 35 m). */
        fun lieIn(date: LocalDate, total: Duration? = null, offset: ZoneOffset = Fx.UTC): Night =
            Fx.night(date, fellAsleep = "00:10", wokeUp = "08:45", total = total, offset = offset)

        /** [days] nights from [MONDAY]: work nights Mon–Fri, lie-ins on weekends. */
        fun typicalWeeks(days: Int, offsetOf: (LocalDate) -> ZoneOffset = { Fx.UTC }): List<Night> =
            (0L until days).map { i ->
                val date = MONDAY.plusDays(i)
                if (date.isWeekend()) lieIn(date, offset = offsetOf(date)) else workNight(date, offset = offsetOf(date))
            }

        /**
         * Work nights from [MONDAY], except that weekend days take the next of [freeTotals] as a lie-in
         * (Sat, Sun, Sat, …). Continues until every total is used and there are at least [minNights] nights.
         */
        fun weeksWithFreeNights(freeTotals: List<Duration>, minNights: Int = 14): List<Night> {
            val nights = mutableListOf<Night>()
            var next = 0
            var date = MONDAY
            while (next < freeTotals.size || nights.size < minNights) {
                nights += if (date.isWeekend() && next < freeTotals.size) lieIn(date, freeTotals[next++]) else workNight(date)
                date = date.plusDays(1)
            }
            return nights
        }
    }
}
