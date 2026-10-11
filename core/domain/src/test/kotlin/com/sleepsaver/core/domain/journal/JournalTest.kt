package com.sleepsaver.core.domain.journal

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.testing.Fx
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Test

class JournalTest {

    /** Tuesday 2026-10-06. */
    private val tuesday = LocalDate.of(2026, 10, 6)
    private val wednesday = tuesday.plusDays(1)

    private fun at(date: LocalDate, time: String): Instant = Fx.instant(date, time)

    @Test
    fun `morning feel is editable all of its own day`() {
        assertThat(feelEditable(tuesday, today = tuesday, now = at(tuesday, "06:00"), todayWake = at(tuesday, "06:30"))).isTrue()
        assertThat(feelEditable(tuesday, today = tuesday, now = at(tuesday, "23:59"), todayWake = null)).isTrue()
    }

    @Test
    fun `morning feel stays editable after midnight until the next wake time`() {
        val wednesdayWake = at(wednesday, "06:30")

        assertThat(feelEditable(tuesday, today = wednesday, now = at(wednesday, "00:30"), todayWake = wednesdayWake)).isTrue()
        assertThat(feelEditable(tuesday, today = wednesday, now = at(wednesday, "06:29"), todayWake = wednesdayWake)).isTrue()
        assertThat(feelEditable(tuesday, today = wednesday, now = at(wednesday, "06:30"), todayWake = wednesdayWake)).isFalse()
        assertThat(feelEditable(tuesday, today = wednesday, now = at(wednesday, "07:00"), todayWake = wednesdayWake)).isFalse()
    }

    @Test
    fun `without a known wake time morning feel locks at midnight`() {
        assertThat(feelEditable(tuesday, today = wednesday, now = at(wednesday, "00:30"), todayWake = null)).isFalse()
    }

    @Test
    fun `morning feel is never editable two days later or in the future`() {
        val thursday = tuesday.plusDays(2)

        assertThat(feelEditable(tuesday, today = thursday, now = at(thursday, "00:10"), todayWake = at(thursday, "06:30"))).isFalse()
        assertThat(feelEditable(wednesday, today = tuesday, now = at(tuesday, "22:00"), todayWake = null)).isFalse()
    }

    @Test
    fun `marks are editable from six days back through tomorrow`() {
        val today = LocalDate.of(2026, 10, 10)

        assertThat(markEditable(today.minusDays(7), today)).isFalse()
        assertThat(markEditable(today.minusDays(6), today)).isTrue()
        assertThat(markEditable(today, today)).isTrue()
        assertThat(markEditable(today.plusDays(1), today)).isTrue()
        assertThat(markEditable(today.plusDays(2), today)).isFalse()
    }

    @Test
    fun `tomorrow list items are trimmed non-blank lines without bullets`() {
        val list = TomorrowList(
            nightOf = tuesday,
            text = "  - call Sam \n\n• buy milk\r\n*pay rent\n   plain line  \n-\n  \n- - double",
            updatedAt = at(tuesday, "22:00"),
            offset = Fx.UTC,
        )

        assertThat(list.items).containsExactly("call Sam", "buy milk", "pay rent", "plain line", "- double").inOrder()
        assertThat(list.copy(text = " \n ").items).isEmpty()
    }

    @Test
    fun `eight marks are stored and three are derived`() {
        assertThat(Mark.stored).hasSize(8)
        assertThat(Mark.stored.none { it.derived }).isTrue()
        assertThat(Mark.entries.filter { it.derived })
            .containsExactly(Mark.NIGHT_STEPS_DONE, Mark.HUSH_USED, Mark.ANCHOR_DROPPED).inOrder()
    }

    @Test
    fun `morning feel levels run from 1 to 5`() {
        assertThat(MorningFeel.entries.map { it.level }).containsExactly(1, 2, 3, 4, 5).inOrder()
        assertThat(MorningFeel.of(3)).isEqualTo(MorningFeel.OKAY)
        assertThat(MorningFeel.of(0)).isNull()
        assertThat(MorningFeel.of(6)).isNull()
    }
}
