package com.sleepsaver.core.domain.patterns

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.config.KeelConfig
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepStageType.AWAKE
import com.sleepsaver.core.domain.model.SleepStageType.LIGHT
import com.sleepsaver.core.domain.testing.Fx
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

class KeelTest {

    private val calculator = KeelCalculator()

    /** Sunday 2026-10-11. */
    private val asOf = LocalDate.of(2026, 10, 11)

    /** 14 nights ending on [asOf], alternating 22:00 and 02:00 onsets (22:00 on asOf), always up at 07:00. */
    private val alternating: List<Night> = (13L downTo 0L).map { back ->
        Fx.night(asOf.minusDays(back), fellAsleep = if (back % 2 == 0L) "22:00" else "02:00", wokeUp = "07:00")
    }

    private fun ready(result: KeelResult): KeelResult.Ready {
        assertThat(result).isInstanceOf(KeelResult.Ready::class.java)
        return result as KeelResult.Ready
    }

    @Test
    fun `fourteen identical nights keep an even keel`() {
        val result = calculator.keel(Fx.regularNights(14, asOf), asOf)

        assertThat(result).isEqualTo(
            KeelResult.Ready(score = 100, band = KeelBand.EVEN, validDays = 14, onsetSd = Duration.ZERO, wakeSd = Duration.ZERO, asOf = asOf),
        )
    }

    @Test
    fun `bedtimes alternating between 22 00 and 02 00 score what the minute pairs say`() {
        val result = ready(calculator.keel(alternating, asOf))

        // 13 neighbouring day pairs × 1440 minutes; each pair disagrees from 22:00 to 02:00 (240 minutes).
        // P = (18720 − 3120) / 18720 = 0.833 → round(200·P − 100) = 67.
        assertThat(result.score).isEqualTo(PatternFx.sri(pairs = 13 * 1440, mismatches = 13 * 240))
        assertThat(result.score).isEqualTo(67)
        assertThat(result.band).isEqualTo(KeelBand.CHOPPY)
        // Two onsets 4 h apart, 7 each: R = cos(π/6), sd = sqrt(−2·ln R)·1440/2π = 122.9 min.
        assertThat(result.onsetSd).isEqualTo(Duration.ofMinutes(123))
        assertThat(result.wakeSd).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `missing nights are left out rather than counted as awake`() {
        val missing = setOf(asOf.minusDays(2), asOf.minusDays(6), asOf.minusDays(7))
        val nights = Fx.regularNights(14, asOf).filter { it.wakeDate !in missing } +
            Fx.night(asOf.minusDays(20), "02:00", "11:00") + // outside the 14 days
            Fx.night(asOf.plusDays(1), "02:00", "11:00") // after asOf

        val result = ready(calculator.keel(nights, asOf))

        assertThat(result.score).isEqualTo(100)
        assertThat(result.validDays).isEqualTo(11)
    }

    @Test
    fun `an extra segment and a nap don't count as asleep`() {
        val nights = Fx.regularNights(14, asOf).toMutableList()
        val extraDay = asOf.minusDays(5)
        nights[8] = Fx.stagedNight(
            extraDay,
            stages = Fx.hypnogram(Fx.dt(extraDay.minusDays(1), "23:00"), LIGHT to 480L),
            extra = listOf(Fx.hypnogram(Fx.dt(extraDay, "09:00"), LIGHT to 90L)),
        )
        val napDay = asOf.minusDays(9)
        nights[4] = PatternFx.withNap(nights[4], Fx.dt(napDay, "15:00"), Fx.dt(napDay, "16:00"))
        check(nights[8].wakeDate == extraDay && nights[4].wakeDate == napDay)

        assertThat(ready(calculator.keel(nights, asOf)).score).isEqualTo(100)

        // With naps included the hour of napping disagrees with the day before and the day after.
        val withNaps = KeelCalculator(SleepConfig(keel = KeelConfig(includeNaps = true)))
        assertThat(ready(withNaps.keel(nights, asOf)).score).isEqualTo(PatternFx.sri(pairs = 13 * 1440, mismatches = 120))
        assertThat(ready(withNaps.keel(nights, asOf)).score).isEqualTo(99)
    }

    @Test
    fun `awake periods inside the main block are not asleep`() {
        val nights = Fx.regularNights(14, asOf).toMutableList()
        val day = asOf.minusDays(3)
        // 23:00–07:00 with 03:00–04:00 scored awake.
        nights[10] = Fx.stagedNight(
            day,
            Fx.hypnogram(Fx.dt(day.minusDays(1), "23:00"), LIGHT to 240L, AWAKE to 60L, LIGHT to 180L),
        )

        assertThat(ready(calculator.keel(nights, asOf)).score).isEqualTo(PatternFx.sri(pairs = 13 * 1440, mismatches = 120))
    }

    @Test
    fun `a manual night counts from fell asleep to woke up`() {
        val nights = Fx.regularNights(14, asOf).toMutableList()
        val day = asOf.minusDays(3)
        nights[10] = PatternFx.manualNight(day, "23:00", "07:00", awakeFrom = Fx.dt(day, "03:00"), awakeTo = Fx.dt(day, "04:00"))

        assertThat(ready(calculator.keel(nights, asOf)).score).isEqualTo(100)
    }

    @Test
    fun `a 23-hour spring-forward day costs only the hour that moves`() {
        // Wake dates 1–14 March 2026 in New York, asleep 23:00–07:00 local; clocks spring forward on the 8th.
        val last = LocalDate.of(2026, 3, 14)
        val nights = (13L downTo 0L).map { Fx.nightInZone(last.minusDays(it), "23:00", "07:00", zone = Fx.NEW_YORK) }

        val result = ready(calculator.keel(nights, last))

        // Windows 1–7 March run noon to noon EST (17:00Z), 8–14 March EDT (16:00Z): the grid is 14 days − 1 h = 20100
        // minutes, all valid (the windows overlap by an hour), so 20100 − 1440 = 18660 pairs. In elapsed time the
        // night of the 8th ends an hour earlier than the 7th's and the 9th starts an hour earlier than the 8th's.
        assertThat(result.score).isEqualTo(PatternFx.sri(pairs = 18_660, mismatches = 120))
        assertThat(result.score).isEqualTo(99)
        assertThat(result.validDays).isEqualTo(14)
        assertThat(result.onsetSd).isEqualTo(Duration.ZERO)
        assertThat(result.wakeSd).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `a 25-hour fall-back day costs only the hour that moves`() {
        // Wake dates 25 Oct – 7 Nov 2026 in New York; clocks fall back on 1 November.
        val last = LocalDate.of(2026, 11, 7)
        val nights = (13L downTo 0L).map { Fx.nightInZone(last.minusDays(it), "23:00", "07:00", zone = Fx.NEW_YORK) }

        val result = ready(calculator.keel(nights, last))

        // Windows to 31 Oct run noon to noon EDT (16:00Z), from 1 Nov EST (17:00Z): the grid is 14 days + 1 h = 20220
        // minutes with an invalid hour (16:00–17:00Z on 31 Oct). Pairs = 20220 − 1440 − 60 (t in the gap)
        // − 60 (t + 24 h in the gap) = 18660; the night of the 1st is an hour longer at its end, the 2nd starts an
        // hour later.
        assertThat(result.score).isEqualTo(PatternFx.sri(pairs = 18_660, mismatches = 120))
        assertThat(result.score).isEqualTo(99)
        assertThat(result.validDays).isEqualTo(14)
    }

    @Test
    fun `an offset change mid-window compares elapsed hours`() {
        // Seven nights in UTC, then seven at +02:00, all 23:00–07:00 local.
        val plus2 = ZoneOffset.ofHours(2)
        val nights = (13L downTo 0L).map { back ->
            Fx.night(asOf.minusDays(back), "23:00", "07:00", offset = if (back >= 7) Fx.UTC else plus2)
        }

        val result = ready(calculator.keel(nights, asOf))

        // Windows 12:00Z → 10:00Z: 14 days − 2 h = 20040 minutes, all valid, 18600 pairs. Across the change the sleep
        // moves 2 h earlier in elapsed time: 2 h at each end disagree.
        assertThat(result.score).isEqualTo(PatternFx.sri(pairs = 18_600, mismatches = 240))
        assertThat(result.score).isEqualTo(97)
        // Local clock times didn't move.
        assertThat(result.onsetSd).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `six valid days are still calibrating`() {
        val result = calculator.keel(Fx.regularNights(6, asOf), asOf)

        assertThat(result).isEqualTo(KeelResult.Calibrating(validDays = 6, needed = 7))
    }

    @Test
    fun `seven nights that never sit 24 hours apart are still calibrating`() {
        val everyOther = Fx.regularNights(14, asOf).filterIndexed { i, _ -> i % 2 == 0 }

        assertThat(calculator.keel(everyOther, asOf)).isEqualTo(KeelResult.Calibrating(validDays = 7, needed = 7))
    }

    @Test
    fun `score over explicit days equals keel for the same spans`() {
        val days = alternating.map { night ->
            KeelDay(
                wakeDate = night.wakeDate,
                windowStart = Fx.instant(night.wakeDate.minusDays(1), "12:00"),
                windowEnd = Fx.instant(night.wakeDate, "12:00"),
                asleep = listOf(SleepSpan(checkNotNull(night.fellAsleep), checkNotNull(night.wokeUp))),
            )
        }

        assertThat(calculator.score(days)).isEqualTo(ready(calculator.keel(alternating, asOf)).score)
        assertThat(calculator.score(emptyList())).isNull()
        assertThat(calculator.score(days.take(1))).isNull()
    }

    @Test
    fun `minutes are sampled at their midpoint`() {
        // Day 1 asleep all day; day 2 asleep for the first 31 s (or 30 s) of every minute. The midpoint, 30 s in, is
        // inside a [0, 31 s) span and just outside a [0, 30 s) one.
        val day1 = asOf.minusDays(1)
        val wholeDay = KeelDay(
            wakeDate = day1,
            windowStart = Fx.instant(day1.minusDays(1), "12:00"),
            windowEnd = Fx.instant(day1, "12:00"),
            asleep = listOf(SleepSpan(Fx.instant(day1.minusDays(1), "12:00"), Fx.instant(day1, "12:00"))),
        )
        fun flickering(seconds: Long): KeelDay {
            val start = Fx.instant(asOf.minusDays(1), "12:00")
            return KeelDay(
                wakeDate = asOf,
                windowStart = start,
                windowEnd = Fx.instant(asOf, "12:00"),
                asleep = (0L until 1440L).map { m -> SleepSpan(start.plusSeconds(60 * m), start.plusSeconds(60 * m + seconds)) },
            )
        }

        assertThat(calculator.score(listOf(wholeDay, flickering(31)))).isEqualTo(100)
        assertThat(calculator.score(listOf(wholeDay, flickering(30)))).isEqualTo(0)
    }

    @Test
    fun `bands follow the even and fair thresholds`() {
        assertThat(calculator.band(100)).isEqualTo(KeelBand.EVEN)
        assertThat(calculator.band(80)).isEqualTo(KeelBand.EVEN)
        assertThat(calculator.band(79)).isEqualTo(KeelBand.FAIR)
        assertThat(calculator.band(70)).isEqualTo(KeelBand.FAIR)
        assertThat(calculator.band(69)).isEqualTo(KeelBand.CHOPPY)
        assertThat(calculator.band(0)).isEqualTo(KeelBand.CHOPPY)
    }

    @Test
    fun `onset and wake spread need three nights`() {
        val lenient = KeelCalculator(SleepConfig(keel = KeelConfig(minValidDays = 2)))
        val nights = listOf(Fx.night(asOf.minusDays(1), "22:00", "06:00"), Fx.night(asOf, "23:00", "07:00"))

        val result = ready(lenient.keel(nights, asOf))

        assertThat(result.onsetSd).isNull()
        assertThat(result.wakeSd).isNull()
    }

    @Test
    fun `the score is clamped at zero`() {
        // Asleep 12:00–24:00 one day and 00:00–12:00 the next, alternating: every pair disagrees.
        val days = (0L until 14L).map { i ->
            val date = asOf.minusDays(13 - i)
            val start = if (i % 2 == 0L) Fx.dt(date.minusDays(1), "12:00") else Fx.dt(date, "00:00")
            KeelDay(
                wakeDate = date,
                windowStart = Fx.instant(date.minusDays(1), "12:00"),
                windowEnd = Fx.instant(date, "12:00"),
                asleep = listOf(SleepSpan(start.toInstant(Fx.UTC), start.plusHours(12).toInstant(Fx.UTC))),
            )
        }

        assertThat(calculator.score(days)).isEqualTo(0)
    }

    @Nested
    inner class Budget {

        private val nights = (13L downTo 0L).map { back ->
            Fx.typicalNight(asOf.minusDays(back), fellAsleep = LocalTime.of(22, 30).plusMinutes(7 * back).toString(), wokeUp = "06:45")
        }

        /** Loads the classes once, so the budget below measures the computation. */
        @BeforeEach
        fun warmUp() {
            assertThat(KeelCalculator().keel(nights, asOf)).isInstanceOf(KeelResult.Ready::class.java)
        }

        @Test
        @Timeout(value = 50, unit = TimeUnit.MILLISECONDS)
        fun `a keel over fourteen days takes under 50 ms`() {
            val result = KeelCalculator().keel(nights, asOf)

            assertThat(result).isInstanceOf(KeelResult.Ready::class.java)
        }
    }
}
