package com.sleepsaver.core.domain.journal

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.BlockKind
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepBlock
import com.sleepsaver.core.domain.schedule.NightRules
import com.sleepsaver.core.domain.testing.Fx
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.jupiter.api.Test

class MomentMarksTest {

    /** Monday night 23:10 → Tuesday 2026-10-13 07:20; the planned wake is 07:00 unless a test says otherwise. */
    private val tuesday = LocalDate.of(2026, 10, 13)
    private val monday = tuesday.minusDays(1)
    private val wednesday = tuesday.plusDays(1)
    private val nights = Fx.byDate(Fx.night(tuesday, fellAsleep = "23:10", wokeUp = "07:20"))

    /** A moment at local [time] on [date] with its stored wake date (NightRules.wakeDateOf). */
    private fun moment(date: LocalDate, time: String, offset: ZoneOffset = Fx.UTC): Pair<Instant, LocalDate> {
        val at = Fx.instant(date, time, offset)
        return at to NightRules.wakeDateOf(at, offset)
    }

    private fun marks(
        vararg moments: Pair<Instant, LocalDate>,
        nights: Map<LocalDate, Night> = this.nights,
        plannedWake: String = "07:00",
    ): MomentMarkDates = momentMarkDates(moments.toList(), nights, plannedWake = { LocalTime.parse(plannedWake) })

    @Test
    fun `a moment in the evening or during the night goes with that night`() {
        val marks = marks(moment(monday, "22:30"), moment(tuesday, "05:40"))

        assertThat(marks.dates).containsExactly(tuesday)
        assertThat(marks.first).isEqualTo(tuesday)
    }

    @Test
    fun `a moment after the night's wake-up is daytime use and goes with no night`() {
        // The release check's case: Hush played at 10:27, after a 07:20 wake, has wake date Tuesday by the 14:00 rule.
        val marks = marks(moment(tuesday, "10:27"))

        assertThat(marks.dates).isEmpty()
        assertThat(marks.first).isEqualTo(wednesday)
    }

    @Test
    fun `sleeping past the planned wake keeps the night on until the wake-up`() {
        assertThat(marks(moment(tuesday, "07:19")).dates).containsExactly(tuesday)
        assertThat(marks(moment(tuesday, "07:20")).dates).isEmpty()
    }

    @Test
    fun `a night that ended early still runs until its planned wake`() {
        // The watch's last sleep ended at 03:00 and there was no more; Night reset played Hush at 03:20.
        val early = Fx.byDate(Fx.night(tuesday, fellAsleep = "23:10", wokeUp = "03:00"))

        val nightReset = marks(moment(tuesday, "03:20"), nights = early)
        assertThat(nightReset.dates).containsExactly(tuesday)
        assertThat(nightReset.first).isEqualTo(tuesday)
        assertThat(marks(moment(tuesday, "06:59"), nights = early).dates).containsExactly(tuesday)
        assertThat(marks(moment(tuesday, "07:00"), nights = early).dates).isEmpty()
        val daytime = marks(moment(tuesday, "10:27"), nights = early)
        assertThat(daytime.dates).isEmpty()
        assertThat(daytime.first).isEqualTo(wednesday)
        // A later planned wake (a free morning) keeps the night on for longer.
        assertThat(marks(moment(tuesday, "08:30"), nights = early, plannedWake = "09:00").dates).containsExactly(tuesday)
    }

    @Test
    fun `the planned wake is placed at the night's own offset`() {
        val plusTwo = ZoneOffset.ofHours(2)
        val early = Fx.byDate(Fx.night(tuesday, fellAsleep = "23:10", wokeUp = "03:00", offset = plusTwo))

        assertThat(marks(moment(tuesday, "06:30", plusTwo), nights = early).dates).containsExactly(tuesday)
        assertThat(marks(moment(tuesday, "07:30", plusTwo), nights = early).dates).isEmpty()
    }

    @Test
    fun `an afternoon moment before tonight's onset goes with no night`() {
        // Wednesday's night: Tuesday 23:10 → Wednesday 07:20, so it counts moments from Tuesday 17:10.
        val both = Fx.byDate(nights.getValue(tuesday), Fx.night(wednesday, fellAsleep = "23:10", wokeUp = "07:20"))

        val nap = marks(moment(tuesday, "15:00"), nights = both) // Hush for a Catnap: wake date Wednesday by the cutoff
        assertThat(nap.dates).isEmpty()
        assertThat(nap.first).isEqualTo(wednesday)
        assertThat(marks(moment(tuesday, "17:09"), nights = both).dates).isEmpty()
        assertThat(marks(moment(tuesday, "17:10"), nights = both).dates).containsExactly(wednesday)
        assertThat(marks(moment(tuesday, "21:30"), nights = both).dates).containsExactly(wednesday) // Night steps
    }

    @Test
    fun `past the cutoff a moment goes with tonight`() {
        val marks = marks(moment(tuesday, "10:27"), moment(tuesday, "22:30"))

        assertThat(marks.dates).containsExactly(wednesday)
        assertThat(marks.first).isEqualTo(wednesday)
    }

    @Test
    fun `extra segments widen the night, a nap doesn't`() {
        val night = nights.getValue(tuesday)
        fun block(kind: BlockKind, date: LocalDate, from: String, to: String) = SleepBlock(
            kind = kind,
            start = Fx.instant(date, from),
            end = Fx.instant(date, to),
            startOffset = Fx.UTC,
            endOffset = Fx.UTC,
            asleep = Duration.between(Fx.instant(date, from), Fx.instant(date, to)),
        )
        val backToSleep = Fx.byDate(night.copy(extraSegments = listOf(block(BlockKind.EXTRA, tuesday, "08:30", "09:45"))))
        val dozedEarly = Fx.byDate(night.copy(extraSegments = listOf(block(BlockKind.EXTRA, monday, "21:00", "21:40"))))
        val napped = Fx.byDate(night.copy(naps = listOf(block(BlockKind.NAP, tuesday, "11:00", "11:40"))))

        assertThat(marks(moment(tuesday, "09:20"), nights = backToSleep).dates).containsExactly(tuesday)
        assertThat(marks(moment(monday, "15:30"), nights = dozedEarly).dates).containsExactly(tuesday)
        assertThat(marks(moment(monday, "15:30")).dates).isEmpty()
        assertThat(marks(moment(tuesday, "10:50"), nights = napped).dates).isEmpty()
    }

    @Test
    fun `a night that isn't in yet keeps its moments`() {
        val marks = marks(moment(tuesday, "10:27"), moment(tuesday, "15:00"), nights = emptyMap())

        assertThat(marks.dates).containsExactly(tuesday, wednesday)
        assertThat(marks.first).isEqualTo(tuesday)
    }

    @Test
    fun `no moments, no dates`() {
        assertThat(marks()).isEqualTo(MomentMarkDates(emptySet(), first = null))
    }
}
