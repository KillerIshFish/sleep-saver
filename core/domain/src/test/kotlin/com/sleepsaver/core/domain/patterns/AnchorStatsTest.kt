package com.sleepsaver.core.domain.patterns

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.journal.Anchor
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.testing.Fx
import java.time.Duration
import java.time.LocalDate
import org.junit.jupiter.api.Test

class AnchorStatsTest {

    private val calculator = AnchorStatsCalculator()

    /** Sunday 2026-10-11; the evening before is Saturday the 10th. */
    private val wakeDate = LocalDate.of(2026, 10, 11)
    private val evening = wakeDate.minusDays(1)

    /** Asleep 23:30 → 07:00. */
    private val night: Night = Fx.night(wakeDate, "23:30", "07:00")

    private fun anchorAt(date: LocalDate, time: String, id: Long = 1L): Anchor = Fx.anchor(Fx.dt(date, time), id = id)

    private fun minutes(m: Long): Duration = Duration.ofMinutes(m)

    /** Nights on [dates] asleep 23:30 → 07:00, each with an anchor [latency] minutes before its onset. */
    private fun anchoredNights(dates: List<LocalDate>, latency: (LocalDate) -> Long): Pair<List<Night>, List<Anchor>> {
        val nights = dates.map { Fx.night(it, "23:30", "07:00") }
        val anchors = dates.mapIndexed { i, date ->
            Fx.anchor(Fx.dt(date.minusDays(1), "23:30").minusMinutes(latency(date)), id = i + 1L)
        }
        return nights to anchors
    }

    // ---------------------------------------------------------------- choose

    @Test
    fun `the latest anchor up to 30 minutes after onset counts`() {
        val early = anchorAt(evening, "22:50", id = 1)
        val settled = anchorAt(evening, "23:20", id = 2)
        val afterOnset = anchorAt(evening, "23:55", id = 3) // 25 min after onset
        val muchLater = anchorAt(wakeDate, "00:10", id = 4) // 40 min after onset

        assertThat(calculator.choose(listOf(early, settled, muchLater), night)).isEqualTo(settled)
        assertThat(calculator.choose(listOf(muchLater, afterOnset, early), night)).isEqualTo(afterOnset)
        assertThat(calculator.choose(listOf(muchLater), night)).isNull()
        assertThat(calculator.choose(listOf(anchorAt(wakeDate, "00:00", id = 5)), night)?.id).isEqualTo(5L) // exactly 30 min
    }

    @Test
    fun `without an onset the latest anchor of the night counts`() {
        val noOnset = night.copy(fellAsleep = null)
        val late = anchorAt(wakeDate, "00:10", id = 4)

        assertThat(calculator.choose(listOf(anchorAt(evening, "22:50"), late), noOnset)).isEqualTo(late)
        assertThat(calculator.choose(emptyList(), noOnset)).isNull()
    }

    @Test
    fun `anchors of other nights never count`() {
        val lastNight = anchorAt(evening.minusDays(1), "23:00", id = 7) // wake date the 10th
        val tonight = anchorAt(wakeDate, "22:30", id = 8) // wake date the 12th

        assertThat(lastNight.wakeDate).isEqualTo(evening)
        assertThat(tonight.wakeDate).isEqualTo(wakeDate.plusDays(1))
        assertThat(calculator.choose(listOf(lastNight, tonight), night)).isNull()
        assertThat(calculator.choose(listOf(lastNight, tonight), night.copy(fellAsleep = null))).isNull()
    }

    // ---------------------------------------------------------------- night

    @Test
    fun `latency and efficiency of a night`() {
        // Anchor 23:05, asleep 23:21 → 07:05 with 7 h 20 m asleep.
        val anchor = anchorAt(evening, "23:05")
        val slept = Fx.night(wakeDate, "23:21", "07:05", total = minutes(440))

        val stats = calculator.night(anchor, slept)

        assertThat(stats.wakeDate).isEqualTo(wakeDate)
        assertThat(stats.anchor).isEqualTo(anchor)
        assertThat(stats.latency).isEqualTo(minutes(16))
        assertThat(stats.latencyShown).isEqualTo(minutes(15))
        assertThat(stats.efficiency).isWithin(1e-12).of(440.0 / 480.0)
    }

    @Test
    fun `the shown latency rounds to five minutes and never below zero`() {
        fun shown(anchor: String): Duration? = calculator.night(anchorAt(evening, anchor), night).latencyShown

        assertThat(shown("23:12")).isEqualTo(minutes(20)) // 18 min
        assertThat(shown("23:13")).isEqualTo(minutes(15)) // 17 min
        assertThat(shown("23:12:30")).isEqualTo(minutes(20)) // 17.5 min rounds up
        assertThat(shown("23:30")).isEqualTo(Duration.ZERO)
        assertThat(calculator.night(anchorAt(evening, "23:40"), night).latency).isEqualTo(minutes(-10))
        assertThat(shown("23:40")).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `latencies outside -30 to 180 minutes are ignored`() {
        fun latency(date: LocalDate, anchor: String): Duration? = calculator.night(anchorAt(date, anchor), night).latency

        assertThat(latency(evening, "20:30")).isEqualTo(minutes(180))
        assertThat(latency(evening, "20:29")).isNull()
        assertThat(latency(wakeDate, "00:00")).isEqualTo(minutes(-30))
        assertThat(latency(wakeDate, "00:01")).isNull()
        assertThat(calculator.night(anchorAt(evening, "20:29"), night).latencyShown).isNull()
    }

    @Test
    fun `efficiency counts extra segments and stays within 0 to 1`() {
        // 23:00 → 07:00 with 2 h awake, plus an extra hour asleep 08:00 → 09:00: 7 h asleep over the 8 h from a
        // 23:00 anchor to waking.
        val withExtra = PatternFx.night(wakeDate, asleep = 360, awake = 120, extra = 60)
        assertThat(calculator.night(anchorAt(evening, "23:00"), withExtra).efficiency).isWithin(1e-12).of(420.0 / 480.0)

        // Anchored 20 min after falling asleep: more asleep than anchored time.
        assertThat(calculator.night(anchorAt(evening, "23:50"), night).efficiency).isEqualTo(1.0)
    }

    @Test
    fun `without a night or after waking there is nothing to measure`() {
        val anchor = anchorAt(evening, "23:00")

        assertThat(calculator.night(anchor, null)).isEqualTo(AnchorNightStats(wakeDate, anchor, null, null, null))

        val afterWaking = anchorAt(wakeDate, "07:00")
        val stats = calculator.night(afterWaking, night)
        assertThat(stats.efficiency).isNull()
        assertThat(stats.latency).isNull()
    }

    // ---------------------------------------------------------------- personal latency

    @Test
    fun `seven valid latencies make the personal latency active`() {
        val (nights, anchors) = anchoredNights(PatternFx.dates(7, wakeDate)) { 20 }

        assertThat(calculator.personalLatency(anchors, nights, wakeDate)).isEqualTo(PersonalLatency(7, minutes(20), active = true))
        assertThat(calculator.personalLatency(anchors.drop(1), nights, wakeDate)).isEqualTo(PersonalLatency(6, minutes(20), active = false))
        assertThat(calculator.personalLatency(emptyList(), nights, wakeDate)).isEqualTo(PersonalLatency(0, null, active = false))
    }

    @Test
    fun `the median is clamped to 5 to 45 minutes and rounded to the minute`() {
        val dates = PatternFx.dates(8, wakeDate)
        val (slowNights, slowAnchors) = anchoredNights(dates) { 90 }
        val (fastNights, fastAnchors) = anchoredNights(dates) { 2 }
        val (mixedNights, mixedAnchors) = anchoredNights(dates) { date -> if (date.dayOfMonth % 2 == 0) 10 else 11 }

        assertThat(calculator.personalLatency(slowAnchors, slowNights, wakeDate).median).isEqualTo(minutes(45))
        assertThat(calculator.personalLatency(fastAnchors, fastNights, wakeDate).median).isEqualTo(minutes(5))
        assertThat(calculator.personalLatency(mixedAnchors, mixedNights, wakeDate).median).isEqualTo(minutes(11)) // 10.5
    }

    @Test
    fun `only the 30 most recent valid latencies of the last 60 days count`() {
        // 15 recent nights at 10 min, 16 older at 30 min, plus nights outside the window and an invalid latency.
        val recent = PatternFx.dates(15, wakeDate)
        val older = PatternFx.dates(16, recent.first().minusDays(1))
        val (nights, anchors) = anchoredNights(recent + older) { date -> if (date in recent) 10 else 30 }
        val (outside, outsideAnchors) = anchoredNights(listOf(wakeDate.minusDays(60), wakeDate.plusDays(1))) { 120 }
        val invalidNight = Fx.night(older.first().minusDays(1), "23:30", "07:00")
        val invalidAnchor = Fx.anchor(Fx.dt(invalidNight.wakeDate.minusDays(1), "19:00"), id = 99) // 4.5 h before onset

        val result = calculator.personalLatency(
            anchors + outsideAnchors.map { it.copy(id = it.id + 100) } + invalidAnchor,
            nights + outside + invalidNight,
            wakeDate,
        )

        // The oldest 30-min value drops out: 15 × 10 and 15 × 30 → 20.
        assertThat(result).isEqualTo(PersonalLatency(30, minutes(20), active = true))
    }
}
