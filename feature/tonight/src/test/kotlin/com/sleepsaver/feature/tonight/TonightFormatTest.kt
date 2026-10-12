package com.sleepsaver.feature.tonight

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/** Day names that can't be mistaken for a nearer day. */
class TonightFormatTest {

    /** Monday 2026-10-12, the morning a Monday-only alarm rang. */
    private val monday = LocalDate.of(2026, 10, 12)

    @Test
    fun `the coming five days go by their weekday`() {
        assertThat(nearDay(monday.plusDays(1), monday)).isEqualTo("Tue")
        assertThat(nearDay(monday.plusDays(5), monday)).isEqualTo("Sat")
    }

    @Test
    fun `six or more days away, today and today's weekday carry the date`() {
        assertThat(nearDay(monday.plusDays(6), monday)).isEqualTo("Sun, Oct\u00A018")
        assertThat(nearDay(monday.plusDays(7), monday)).isEqualTo("Mon, Oct\u00A019")
        assertThat(nearDay(monday.plusDays(14), monday)).isEqualTo("Mon, Oct\u00A026")
        assertThat(nearDay(monday, monday)).isEqualTo("Mon, Oct\u00A012")
    }

    @Test
    fun `a date in another year says which`() {
        val newYearsEve = LocalDate.of(2026, 12, 31)
        assertThat(nearDay(newYearsEve.plusDays(7), newYearsEve)).isEqualTo("Thu, Jan\u00A07, 2027")
        assertThat(nearDay(newYearsEve.plusDays(1), newYearsEve)).isEqualTo("Fri")
    }
}
