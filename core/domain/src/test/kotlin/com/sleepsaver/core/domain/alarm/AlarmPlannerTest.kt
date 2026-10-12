package com.sleepsaver.core.domain.alarm

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.OverrideKind
import com.sleepsaver.core.domain.schedule.PlanPause
import com.sleepsaver.core.domain.schedule.WakeStyle
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.domain.testing.Fx
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.jupiter.api.Test

class AlarmPlannerTest {

    private val planner = AlarmPlanner()
    private val sixThirty = LocalTime.of(6, 30)

    /** Wednesday 2026-10-07. */
    private val wednesday = LocalDate.of(2026, 10, 7)

    /** Mon–Fri 06:30 SURFACING. */
    private val workWeek: WeekPlan = Fx.weekPlan(
        MONDAY to sixThirty, TUESDAY to sixThirty, WEDNESDAY to sixThirty, THURSDAY to sixThirty, FRIDAY to sixThirty,
    )

    private fun at(date: LocalDate, time: String): Instant = Fx.instant(date, time)

    @Test
    fun `upcoming alarms are the planned mornings after now, in time order, within the lookahead`() {
        val upcoming = planner.upcoming(workWeek, OneOffs(), at(wednesday, "07:00"), Fx.UTC)

        // Wednesday's 06:30 has passed; the lookahead ends on the next Wednesday.
        assertThat(upcoming.map { it.id }).containsExactly(
            "2026-10-08T06:30", "2026-10-09T06:30", "2026-10-12T06:30", "2026-10-13T06:30", "2026-10-14T06:30",
        ).inOrder()
        with(upcoming.first()) {
            assertThat(date).isEqualTo(wednesday.plusDays(1))
            assertThat(localTime).isEqualTo(sixThirty)
            assertThat(target).isEqualTo(at(wednesday.plusDays(1), "06:30"))
            assertThat(offset).isEqualTo(ZoneOffset.UTC)
            assertThat(source).isEqualTo(AlarmSource.DAY_PLAN)
            assertThat(settings.mode).isEqualTo(AlarmMode.SURFACING)
            assertThat(window).isEqualTo(Duration.ofMinutes(30))
        }
    }

    @Test
    fun `an alarm at now is no longer upcoming, one a minute later is`() {
        assertThat(planner.next(workWeek, OneOffs(), at(wednesday, "06:30"), Fx.UTC)?.date).isEqualTo(wednesday.plusDays(1))
        assertThat(planner.next(workWeek, OneOffs(), at(wednesday, "06:29"), Fx.UTC)?.date).isEqualTo(wednesday)
    }

    @Test
    fun `handled occurrences are excluded`() {
        val next = planner.next(workWeek, OneOffs(), at(wednesday, "06:10"), Fx.UTC, exclude = setOf("2026-10-07T06:30"))

        assertThat(next?.id).isEqualTo("2026-10-08T06:30")
    }

    @Test
    fun `a morning whose alarm already rang out gets no plan alarm at a new time, only a just-once one`() {
        // Wednesday's 06:30 rang and was stopped; at 06:40 Wednesday moves to 07:15 (meant for next week).
        val later = Fx.weekPlan(
            MONDAY to sixThirty, TUESDAY to sixThirty, WEDNESDAY to LocalTime.of(7, 15), THURSDAY to sixThirty, FRIDAY to sixThirty,
        )
        val now = at(wednesday, "06:40")
        val stopped = setOf("2026-10-07T06:30")
        val ended = setOf(wednesday)

        assertThat(planner.next(later, OneOffs(), now, Fx.UTC, exclude = stopped)?.id).isEqualTo("2026-10-07T07:15")
        assertThat(planner.next(later, OneOffs(), now, Fx.UTC, exclude = stopped, endedDates = ended)?.id).isEqualTo("2026-10-08T06:30")
        // Next Wednesday is another morning: it gets the new time.
        assertThat(planner.upcoming(later, OneOffs(), now, Fx.UTC, endedDates = ended).map { it.id }).contains("2026-10-14T07:15")

        val once = OneOffs(overrides = listOf(DateOverride(wednesday, OverrideKind.JUST_ONCE, LocalTime.of(8, 0), AlarmMode.FIXED)))
        assertThat(planner.next(later, once, now, Fx.UTC, exclude = stopped, endedDates = ended)?.id).isEqualTo("2026-10-07T08:00")
    }

    @Test
    fun `the limit caps the list`() {
        assertThat(planner.upcoming(workWeek, OneOffs(), at(wednesday, "07:00"), Fx.UTC, limit = 2)).hasSize(2)
        assertThat(planner.upcoming(workWeek, OneOffs(), at(wednesday, "07:00"), Fx.UTC, limit = 0)).isEmpty()
    }

    @Test
    fun `one-offs add, skip and pause alarms`() {
        val saturday = wednesday.plusDays(3)
        val oneOffs = OneOffs(
            overrides = listOf(
                DateOverride(wednesday.plusDays(1), OverrideKind.DAY_OFF),
                DateOverride(saturday, OverrideKind.JUST_ONCE, LocalTime.of(8, 0), AlarmMode.FIXED),
            ),
            pause = PlanPause(wednesday.plusDays(5), wednesday.plusDays(6)),
        )

        val upcoming = planner.upcoming(workWeek, oneOffs, at(wednesday, "07:00"), Fx.UTC)

        assertThat(upcoming.map { it.id }).containsExactly("2026-10-09T06:30", "2026-10-10T08:00", "2026-10-14T06:30").inOrder()
        with(upcoming[1]) {
            assertThat(source).isEqualTo(AlarmSource.JUST_ONCE)
            assertThat(settings.mode).isEqualTo(AlarmMode.FIXED)
            assertThat(settings.style).isEqualTo(WakeStyle.CALM) // Saturday's own style
            assertThat(window).isEqualTo(Duration.ZERO)
        }
    }

    @Test
    fun `a set time with the alarm off is not an alarm`() {
        val plan = Fx.weekPlan(THURSDAY to sixThirty, mode = AlarmMode.OFF)

        assertThat(planner.upcoming(plan, OneOffs(), at(wednesday, "07:00"), Fx.UTC)).isEmpty()
        assertThat(planner.next(WeekPlan.DEFAULT, OneOffs(), at(wednesday, "07:00"), Fx.UTC)).isNull()
    }

    @Test
    fun `a time in the spring-forward gap rings at the first moment after it`() {
        val plan = Fx.weekPlan(SUNDAY to LocalTime.of(2, 30))

        val next = planner.next(plan, OneOffs(), Fx.instant(Fx.SPRING_FORWARD.minusDays(1), "12:00", Fx.NEW_YORK), Fx.NEW_YORK)!!

        assertThat(next.id).isEqualTo("2026-03-08T02:30")
        assertThat(next.localTime).isEqualTo(LocalTime.of(2, 30))
        assertThat(next.target).isEqualTo(Instant.parse("2026-03-08T07:30:00Z")) // 03:30 EDT
        assertThat(next.offset).isEqualTo(ZoneOffset.ofHours(-4))
    }

    @Test
    fun `a time in the fall-back overlap rings the first time it comes`() {
        val plan = Fx.weekPlan(SUNDAY to LocalTime.of(1, 30))

        val next = planner.next(plan, OneOffs(), Fx.instant(Fx.FALL_BACK.minusDays(1), "12:00", Fx.NEW_YORK), Fx.NEW_YORK)!!

        assertThat(next.target).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"))
        assertThat(next.offset).isEqualTo(ZoneOffset.ofHours(-4))
    }

    @Test
    fun `today is the date in the given zone`() {
        // 03:00 UTC on Thursday is Wednesday 23:00 in New York: Thursday 06:30 New York is next.
        val next = planner.next(workWeek, OneOffs(), Instant.parse("2026-10-08T03:00:00Z"), Fx.NEW_YORK)!!

        assertThat(next.id).isEqualTo("2026-10-08T06:30")
        assertThat(next.target).isEqualTo(Instant.parse("2026-10-08T10:30:00Z"))
    }

    @Test
    fun `planned ids cover yesterday through the lookahead and ignore now`() {
        val ids = planner.plannedIds(workWeek, OneOffs(), at(wednesday, "07:00"), Fx.UTC)

        assertThat(ids).containsExactly(
            "2026-10-06T06:30", "2026-10-07T06:30", "2026-10-08T06:30", "2026-10-09T06:30",
            "2026-10-12T06:30", "2026-10-13T06:30", "2026-10-14T06:30",
        )
    }

    @Test
    fun `occurrence ids are the date and the wall time to the minute`() {
        assertThat(AlarmOccurrence.occurrenceId(wednesday, LocalTime.of(6, 30, 45))).isEqualTo("2026-10-07T06:30")
        assertThat(AlarmOccurrence.occurrenceId(wednesday, LocalTime.of(7, 0))).isEqualTo("2026-10-07T07:00")
    }
}
