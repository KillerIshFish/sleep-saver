package com.sleepsaver.core.ui.format

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate

class DateFormatTest {

    // Tuesday.
    private val today = LocalDate.of(2026, 10, 6)

    @Test
    fun `night label is last night, a weekday, then a date`() {
        assertThat(formatNightLabel(wakeDate = today, today = today)).isEqualTo("Last night")
        // Woke Monday Oct 5 -> started Sunday.
        assertThat(formatNightLabel(LocalDate.of(2026, 10, 5), today)).isEqualTo("Sunday night")
        // Woke Sunday Oct 4 -> started Saturday.
        assertThat(formatNightLabel(LocalDate.of(2026, 10, 4), today)).isEqualTo("Saturday night")
        // Started Wednesday Sep 30, six days ago.
        assertThat(formatNightLabel(LocalDate.of(2026, 10, 1), today)).isEqualTo("Wednesday night")
        // Started Tuesday Sep 29: a week ago would read like tonight, so show the date.
        assertThat(formatNightLabel(LocalDate.of(2026, 9, 30), today)).isEqualTo("Tue, Sep 29")
        assertThat(formatNightLabel(LocalDate.of(2026, 9, 29), today)).isEqualTo("Mon, Sep 28")
    }

    @Test
    fun `older years carry the year`() {
        assertThat(formatNightLabel(LocalDate.of(2025, 12, 30), today)).isEqualTo("Mon, Dec 29, 2025")
        assertThat(formatShortDate(LocalDate.of(2026, 9, 28), today)).isEqualTo("Mon, Sep 28")
    }

    @Test
    fun `future wake dates show a date`() {
        assertThat(formatNightLabel(today.plusDays(1), today)).isEqualTo("Tue, Oct 6")
    }

    @Test
    fun `window header`() {
        assertThat(formatWindowHeader(LocalDate.of(2026, 9, 22), LocalDate.of(2026, 10, 5)))
            .isEqualTo("Sep 22 – Oct 5")
        assertThat(formatWindowHeader(LocalDate.of(2025, 12, 22), LocalDate.of(2026, 1, 4)))
            .isEqualTo("Dec 22, 2025 – Jan 4, 2026")
    }

    @Test
    fun `day names come short, narrow and long`() {
        assertThat(formatDayShort(DayOfWeek.MONDAY)).isEqualTo("Mon")
        assertThat(formatDayShort(DayOfWeek.THURSDAY)).isEqualTo("Thu")
        assertThat(formatDayNarrow(DayOfWeek.MONDAY)).isEqualTo("M")
        assertThat(formatDayNarrow(DayOfWeek.SUNDAY)).isEqualTo("S")
        assertThat(formatDayLong(DayOfWeek.WEDNESDAY)).isEqualTo("Wednesday")
        assertThat(DayOfWeek.entries.map(::formatDayNarrow)).containsExactly("M", "T", "W", "T", "F", "S", "S").inOrder()
    }

    @Test
    fun `formatDays names the common sets`() {
        assertThat(formatDays(DayOfWeek.entries.toSet())).isEqualTo("Every day")
        assertThat(formatDays(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY))).isEqualTo("Weekdays")
        assertThat(formatDays(setOf(SATURDAY, SUNDAY))).isEqualTo("Weekends")
        assertThat(formatDays(emptySet())).isEqualTo("No days")
    }

    @Test
    fun `formatDays turns a run of three or more into a range`() {
        assertThat(formatDays(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY))).isEqualTo("Mon–Thu")
        assertThat(formatDays(setOf(FRIDAY, SATURDAY, SUNDAY))).isEqualTo("Fri–Sun")
        assertThat(formatDays(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY))).isEqualTo("Mon–Sat")
        assertThat(formatDays(setOf(TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY))).isEqualTo("Tue–Sun")
    }

    @Test
    fun `formatDays lists everything else Monday first`() {
        assertThat(formatDays(setOf(FRIDAY, MONDAY, WEDNESDAY))).isEqualTo("Mon, Wed, Fri")
        assertThat(formatDays(setOf(TUESDAY, WEDNESDAY))).isEqualTo("Tue, Wed")
        assertThat(formatDays(setOf(SUNDAY))).isEqualTo("Sun")
        assertThat(formatDays(setOf(MONDAY))).isEqualTo("Mon")
        // A run doesn't wrap past Sunday.
        assertThat(formatDays(setOf(SATURDAY, SUNDAY, MONDAY))).isEqualTo("Mon, Sat, Sun")
        // Six days with a gap.
        assertThat(formatDays(setOf(MONDAY, TUESDAY, WEDNESDAY, FRIDAY, SATURDAY, SUNDAY)))
            .isEqualTo("Mon, Tue, Wed, Fri, Sat, Sun")
    }
}
