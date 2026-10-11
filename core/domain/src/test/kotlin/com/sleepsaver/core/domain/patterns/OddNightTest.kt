package com.sleepsaver.core.domain.patterns

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.Night
import java.time.Duration
import java.time.LocalDate
import org.junit.jupiter.api.Test

class OddNightTest {

    private val detector = OddNightDetector()

    /** Sunday 2026-10-11: last night's wake date. */
    private val wakeDate = LocalDate.of(2026, 10, 11)

    /** The 28 nights before: asleep 7.5 h from 23:00 with 30 min awake (medians: 450 min, 30 min, 23:00). */
    private val baseline: List<Night> = PatternFx.dates(28, wakeDate.minusDays(1)).map { PatternFx.night(it, asleep = 450, awake = 30) }

    private fun lastNight(asleep: Long = 450, awake: Long = 30, fellAsleep: String = "23:00", extra: Long = 0): Night =
        PatternFx.night(wakeDate, asleep = asleep, awake = awake, fellAsleep = fellAsleep, extra = extra)

    private fun odd(night: Night, history: List<Night> = baseline): OddNight? = detector.check(history + night, wakeDate)

    @Test
    fun `an ordinary night asks nothing`() {
        assertThat(odd(lastNight(asleep = 430, awake = 40, fellAsleep = "23:20"))).isNull()
    }

    @Test
    fun `a short night is odd`() {
        assertThat(odd(lastNight(asleep = 380))?.ways).containsExactly(OddWay.LESS_SLEEP)
    }

    @Test
    fun `it says by how much the night differed from the median`() {
        val result = odd(lastNight(asleep = 380, awake = 45, fellAsleep = "23:30"))

        assertThat(result).isEqualTo(
            OddNight(
                wakeDate = wakeDate,
                ways = setOf(OddWay.LESS_SLEEP),
                totalDelta = Duration.ofMinutes(-70),
                awakeDelta = Duration.ofMinutes(15),
                onsetDelta = Duration.ofMinutes(30),
            ),
        )
        assertThat(odd(lastNight(asleep = 520, fellAsleep = "00:15"))?.onsetDelta).isEqualTo(Duration.ofMinutes(75))
    }

    @Test
    fun `a good night is odd too`() {
        val result = odd(lastNight(asleep = 520, fellAsleep = "22:00"))

        assertThat(result?.ways).containsExactly(OddWay.MORE_SLEEP, OddWay.EARLIER_ONSET)
        assertThat(result?.totalDelta).isEqualTo(Duration.ofMinutes(70))
        assertThat(result?.onsetDelta).isEqualTo(Duration.ofMinutes(-60))
    }

    @Test
    fun `thresholds are inclusive`() {
        assertThat(odd(lastNight(asleep = 510))?.ways).containsExactly(OddWay.MORE_SLEEP)
        assertThat(odd(lastNight(asleep = 509))).isNull()
        assertThat(odd(lastNight(asleep = 390))?.ways).containsExactly(OddWay.LESS_SLEEP)
        assertThat(odd(lastNight(awake = 50))?.ways).containsExactly(OddWay.MORE_AWAKE)
        assertThat(odd(lastNight(awake = 49))).isNull()
        assertThat(odd(lastNight(fellAsleep = "22:00"))?.ways).containsExactly(OddWay.EARLIER_ONSET)
        assertThat(odd(lastNight(fellAsleep = "22:01"))).isNull()
    }

    @Test
    fun `less awake time is never odd`() {
        assertThat(odd(lastNight(awake = 0))).isNull()
    }

    @Test
    fun `a late onset after midnight is measured across it`() {
        // 00:15 is 75 min after the usual 23:00, not 22 h 45 m before it.
        assertThat(odd(lastNight(fellAsleep = "00:15"))?.ways).containsExactly(OddWay.LATER_ONSET)
    }

    @Test
    fun `the onset median is taken across midnight`() {
        // Usual onsets alternate 23:30 and 00:30: the median is midnight, so 23:00 is an hour early.
        val history = PatternFx.dates(28, wakeDate.minusDays(1)).mapIndexed { i, date ->
            PatternFx.night(date, asleep = 450, awake = 30, fellAsleep = if (i % 2 == 0) "23:30" else "00:30")
        }

        assertThat(odd(lastNight(fellAsleep = "23:00"), history)?.ways).containsExactly(OddWay.EARLIER_ONSET)
        assertThat(odd(lastNight(fellAsleep = "23:01"), history)).isNull()
    }

    @Test
    fun `extra segments count toward total sleep`() {
        assertThat(odd(lastNight(asleep = 380, extra = 60))).isNull()
    }

    @Test
    fun `the baseline needs seven nights of the last 28`() {
        val tooOld = PatternFx.dates(10, wakeDate.minusDays(29)).map { PatternFx.night(it, asleep = 450, awake = 30) }
        val six = baseline.takeLast(6)
        val seven = baseline.takeLast(7)

        assertThat(odd(lastNight(asleep = 300), tooOld + six)).isNull()
        assertThat(odd(lastNight(asleep = 300), tooOld + seven)?.ways).containsExactly(OddWay.LESS_SLEEP)
    }

    @Test
    fun `the night itself must be there`() {
        val absent = lastNight(asleep = 300).copy(main = null)

        assertThat(detector.check(baseline, wakeDate)).isNull()
        assertThat(odd(absent)).isNull()
    }
}
