package com.sleepsaver.core.notifications

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.HabitType.BEDTIME
import com.sleepsaver.core.domain.model.HabitType.CAFFEINE_CUTOFF
import com.sleepsaver.core.domain.model.HabitType.DIM_LIGHTS
import com.sleepsaver.core.domain.model.HabitType.EVENING_ROUTINE
import com.sleepsaver.core.domain.model.HabitType.LARGE_MEAL_CUTOFF
import com.sleepsaver.core.domain.model.HabitType.MORNING_LIGHT
import com.sleepsaver.core.domain.model.HabitType.NAP_WINDOW
import com.sleepsaver.core.notifications.testing.TestInsights.TODAY
import com.sleepsaver.core.notifications.testing.TestInsights.at
import com.sleepsaver.core.notifications.testing.TestInsights.habit
import org.junit.Test

class ReminderPlanningTest {

    private val planning = ReminderPlanning()
    private val tomorrow = TODAY.plusDays(1)

    @Test
    fun `skips disabled habits, habits without reminders and past habits`() {
        val now = at(TODAY, "12:00")
        val habits = listOf(
            habit(MORNING_LIGHT, at(TODAY, "07:15")), // past
            habit(LARGE_MEAL_CUTOFF, at(TODAY, "19:45"), enabled = false),
            habit(NAP_WINDOW, at(TODAY, "14:30"), reminder = false),
            habit(CAFFEINE_CUTOFF, at(TODAY, "12:45")),
            habit(BEDTIME, at(TODAY, "22:30")),
        )

        val pending = planning.pending(habits, now, postedThrough = null)

        assertThat(pending.map { it.type }).containsExactly(CAFFEINE_CUTOFF, BEDTIME).inOrder()
    }

    @Test
    fun `ignores reminders beyond the 36 hour horizon`() {
        val now = at(TODAY, "08:00")
        val habits = listOf(
            habit(BEDTIME, at(tomorrow, "20:00")), // 36 h exactly: kept
            habit(DIM_LIGHTS, at(tomorrow, "20:01")),
        )

        assertThat(planning.pending(habits, now, null).map { it.type }).containsExactly(BEDTIME)
    }

    @Test
    fun `next alarm is the earliest pending reminder regardless of input order`() {
        val now = at(TODAY, "09:00")
        val habits = listOf(
            habit(BEDTIME, at(TODAY, "22:30")),
            habit(DIM_LIGHTS, at(TODAY, "21:00")),
            habit(CAFFEINE_CUTOFF, at(TODAY, "12:45")),
        )

        assertThat(planning.nextAlarm(habits, now, null)).isEqualTo(at(TODAY, "12:45"))
    }

    @Test
    fun `no pending reminders means no alarm`() {
        val habits = listOf(habit(MORNING_LIGHT, at(TODAY, "07:15")), habit(BEDTIME, at(TODAY, "22:30"), reminder = false))

        assertThat(planning.nextAlarm(habits, at(TODAY, "12:00"), null)).isNull()
    }

    @Test
    fun `once today's reminders are past, tomorrow's first one is next`() {
        val now = at(TODAY, "23:10")
        val habits = listOf(
            habit(MORNING_LIGHT, at(TODAY, "07:15")),
            habit(CAFFEINE_CUTOFF, at(TODAY, "12:45")),
            habit(BEDTIME, at(TODAY, "22:30")),
            habit(MORNING_LIGHT, at(tomorrow, "07:20")),
            habit(CAFFEINE_CUTOFF, at(tomorrow, "12:50")),
        )

        assertThat(planning.nextAlarm(habits, now, postedThrough = at(TODAY, "22:32"))).isEqualTo(at(tomorrow, "07:20"))
    }

    @Test
    fun `reminders within two minutes of the fire are posted together`() {
        val fire = at(TODAY, "21:00")
        val habits = listOf(
            habit(DIM_LIGHTS, at(TODAY, "21:00")),
            habit(EVENING_ROUTINE, at(TODAY, "21:01:30")),
            habit(BEDTIME, at(TODAY, "21:02:01")),
        )

        val due = planning.due(habits, fire, postedThrough = at(TODAY, "13:00"))

        assertThat(due.map { it.type }).containsExactly(DIM_LIGHTS, EVENING_ROUTINE).inOrder()
    }

    @Test
    fun `after firing, the posted group is not planned again and the next alarm is the following reminder`() {
        val fire = at(TODAY, "21:00:05")
        val habits = listOf(
            habit(DIM_LIGHTS, at(TODAY, "21:00")),
            habit(EVENING_ROUTINE, at(TODAY, "21:01")),
            habit(BEDTIME, at(TODAY, "22:30")),
        )

        val postedThrough = planning.postedThroughAfterFiring(fire)

        assertThat(planning.pending(habits, fire, postedThrough).map { it.type }).containsExactly(BEDTIME)
        assertThat(planning.nextAlarm(habits, at(TODAY, "21:00:30"), postedThrough)).isEqualTo(at(TODAY, "22:30"))
    }

    @Test
    fun `an alarm delayed by Doze still posts the overdue reminder`() {
        val habits = listOf(habit(CAFFEINE_CUTOFF, at(TODAY, "12:45")), habit(BEDTIME, at(TODAY, "22:30")))
        val lateFire = at(TODAY, "12:53")

        assertThat(planning.due(habits, lateFire, postedThrough = at(TODAY, "07:17")).map { it.type })
            .containsExactly(CAFFEINE_CUTOFF)
        // A replan between the planned time and the delayed fire keeps the overdue reminder armed.
        assertThat(planning.nextAlarm(habits, at(TODAY, "12:50"), postedThrough = at(TODAY, "07:17")))
            .isEqualTo(at(TODAY, "12:45"))
    }

    @Test
    fun `long overdue reminders are dropped`() {
        val habits = listOf(habit(CAFFEINE_CUTOFF, at(TODAY, "12:45")), habit(BEDTIME, at(TODAY, "22:30")))

        assertThat(planning.due(habits, at(TODAY, "14:00"), postedThrough = at(TODAY, "07:17"))).isEmpty()
        assertThat(planning.nextAlarm(habits, at(TODAY, "14:00"), postedThrough = at(TODAY, "07:17")))
            .isEqualTo(at(TODAY, "22:30"))
    }

    @Test
    fun `without a posted marker nothing in the past is overdue`() {
        val habits = listOf(habit(CAFFEINE_CUTOFF, at(TODAY, "12:45")), habit(BEDTIME, at(TODAY, "22:30")))

        assertThat(planning.nextAlarm(habits, at(TODAY, "12:50"), postedThrough = null)).isEqualTo(at(TODAY, "22:30"))
    }

    @Test
    fun `the same habit in today's and tomorrow's lists counts once`() {
        val bedtime = habit(BEDTIME, at(TODAY, "22:30"))

        assertThat(planning.pending(listOf(bedtime, bedtime.copy()), at(TODAY, "20:00"), null)).hasSize(1)
    }

    @Test
    fun `a clock set back doesn't silence reminders`() {
        val habits = listOf(habit(BEDTIME, at(TODAY, "22:30")))

        // Posted through tomorrow morning, but the clock now says this evening.
        assertThat(planning.nextAlarm(habits, at(TODAY, "20:00"), postedThrough = at(tomorrow, "07:22")))
            .isEqualTo(at(TODAY, "22:30"))
    }

    @Test
    fun `a habit already posted today isn't posted again when its time moves later`() {
        val habits = listOf(habit(MORNING_LIGHT, at(TODAY, "07:21")), habit(CAFFEINE_CUTOFF, at(TODAY, "12:51")))
        val lastPosted = mapOf(MORNING_LIGHT to at(TODAY, "07:15"))

        assertThat(planning.nextAlarm(habits, at(TODAY, "07:16"), at(TODAY, "07:17:10"), lastPosted)).isEqualTo(at(TODAY, "12:51"))
        assertThat(planning.due(habits, at(TODAY, "07:21:30"), at(TODAY, "07:17:10"), lastPosted)).isEmpty()
    }

    @Test
    fun `tomorrow's reminder of an already posted habit is still pending`() {
        val habits = listOf(habit(MORNING_LIGHT, at(tomorrow, "07:20")))

        assertThat(planning.nextAlarm(habits, at(TODAY, "23:00"), at(TODAY, "22:32"), mapOf(MORNING_LIGHT to at(TODAY, "07:15"))))
            .isEqualTo(at(tomorrow, "07:20"))
    }

    @Test
    fun `a posted marker from a clock that was later set back doesn't silence the habit`() {
        val habits = listOf(habit(BEDTIME, at(TODAY, "22:30")))

        assertThat(planning.nextAlarm(habits, at(TODAY, "20:00"), null, mapOf(BEDTIME to at(tomorrow, "00:10"))))
            .isEqualTo(at(TODAY, "22:30"))
    }
}
