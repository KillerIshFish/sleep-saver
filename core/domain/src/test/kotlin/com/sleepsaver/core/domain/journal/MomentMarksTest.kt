package com.sleepsaver.core.domain.journal

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.BlockKind
import com.sleepsaver.core.domain.model.SleepBlock
import com.sleepsaver.core.domain.schedule.NightRules
import com.sleepsaver.core.domain.testing.Fx
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Test

class MomentMarksTest {

    /** Monday night ended Tuesday 2026-10-13 at 07:20. */
    private val tuesday = LocalDate.of(2026, 10, 13)
    private val wednesday = tuesday.plusDays(1)
    private val nights = Fx.byDate(Fx.night(tuesday, fellAsleep = "23:10", wokeUp = "07:20"))

    /** A moment at local [time] on [date] (UTC) with its stored wake date (NightRules.wakeDateOf). */
    private fun moment(date: LocalDate, time: String): Pair<Instant, LocalDate> {
        val at = Fx.instant(date, time)
        return at to NightRules.wakeDateOf(at, Fx.UTC)
    }

    @Test
    fun `a moment in the evening or during the night goes with that night`() {
        val marks = momentMarkDates(listOf(moment(tuesday.minusDays(1), "22:30"), moment(tuesday, "05:40")), nights)

        assertThat(marks.dates).containsExactly(tuesday)
        assertThat(marks.first).isEqualTo(tuesday)
    }

    @Test
    fun `a moment after the night's wake-up is daytime use and goes with no night`() {
        // The release check's case: Hush played at 10:27, after a 07:20 wake, has wake date Tuesday by the 14:00 rule.
        val marks = momentMarkDates(listOf(moment(tuesday, "10:27")), nights)

        assertThat(marks.dates).isEmpty()
        assertThat(marks.first).isEqualTo(wednesday)
    }

    @Test
    fun `the wake-up itself already ends the night`() {
        assertThat(momentMarkDates(listOf(moment(tuesday, "07:19")), nights).dates).containsExactly(tuesday)
        assertThat(momentMarkDates(listOf(moment(tuesday, "07:20")), nights).dates).isEmpty()
    }

    @Test
    fun `past the cutoff a moment goes with tonight`() {
        val marks = momentMarkDates(listOf(moment(tuesday, "10:27"), moment(tuesday, "22:30")), nights)

        assertThat(marks.dates).containsExactly(wednesday)
        assertThat(marks.first).isEqualTo(wednesday)
    }

    @Test
    fun `a later extra segment keeps the night on, a nap doesn't`() {
        val night = nights.getValue(tuesday)
        fun block(kind: BlockKind, from: String, to: String) = SleepBlock(
            kind = kind,
            start = Fx.instant(tuesday, from),
            end = Fx.instant(tuesday, to),
            startOffset = Fx.UTC,
            endOffset = Fx.UTC,
            asleep = Duration.between(Fx.instant(tuesday, from), Fx.instant(tuesday, to)),
        )
        val backToSleep = Fx.byDate(night.copy(extraSegments = listOf(block(BlockKind.EXTRA, "08:30", "09:45"))))
        val napped = Fx.byDate(night.copy(naps = listOf(block(BlockKind.NAP, "11:00", "11:40"))))

        assertThat(momentMarkDates(listOf(moment(tuesday, "08:20")), backToSleep).dates).containsExactly(tuesday)
        assertThat(momentMarkDates(listOf(moment(tuesday, "10:50")), napped).dates).isEmpty()
    }

    @Test
    fun `a night that isn't in yet keeps its moments`() {
        val marks = momentMarkDates(listOf(moment(tuesday, "10:27")), nights = emptyMap())

        assertThat(marks.dates).containsExactly(tuesday)
        assertThat(marks.first).isEqualTo(tuesday)
    }

    @Test
    fun `no moments, no dates`() {
        assertThat(momentMarkDates(emptyList(), nights)).isEqualTo(MomentMarkDates(emptySet(), first = null))
    }
}
