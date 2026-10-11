package com.sleepsaver.core.domain.nightsteps

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.domain.schedule.EveningPlan
import com.sleepsaver.core.domain.testing.Fx
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Test

class NightStepsPlannerTest {

    private val planner = NightStepsPlanner()
    private val evening = LocalDate.of(2026, 10, 7)
    private val bedtime = at("23:00")
    private val windowStart = at("21:30")

    private fun at(time: String): Instant = Fx.instant(evening, time)

    private fun activity(id: Long, lead: Long, duration: Long, technique: Technique? = null, selected: Boolean = true) = RoutineActivity(
        id = id,
        label = "step $id",
        emoji = technique?.emoji ?: "⭐",
        isCustom = technique == null,
        selected = selected,
        technique = technique,
        lead = Duration.ofMinutes(lead),
        duration = Duration.ofMinutes(duration),
    )

    private val slowTide = activity(1, lead = 30, duration = 10, technique = Technique.SLOW_TIDE)
    private val screensDown = activity(2, lead = 60, duration = 0, technique = Technique.SCREENS_DOWN)
    private val tomorrowList = activity(3, lead = 45, duration = 5, technique = Technique.TOMORROW_LIST, selected = false)
    private val custom = activity(4, lead = 30, duration = 5)
    private val routine = listOf(slowTide, screensDown, tomorrowList, custom)

    private fun log(nightOf: LocalDate, activityId: Long, time: String) =
        StepLog(nightOf, activityId, technique = null, doneAt = Fx.instant(nightOf, time), offset = Fx.UTC)

    @Test
    fun `steps start their lead before bedtime and are sorted by start, then routine order`() {
        val session = planner.plan(evening, windowStart, bedtime, routine, emptyList(), enabled = true)

        assertThat(session.steps.map { it.activity.id }).containsExactly(2L, 3L, 1L, 4L).inOrder()
        assertThat(session.steps.map { it.start }).containsExactly(at("22:00"), at("22:15"), at("22:30"), at("22:30")).inOrder()
        assertThat(session.steps.map { it.end }).containsExactly(at("22:00"), at("22:20"), at("22:40"), at("22:35")).inOrder()
        assertThat(session.nightOf).isEqualTo(evening)
        assertThat(session.windowStart).isEqualTo(windowStart)
        assertThat(session.bedtime).isEqualTo(bedtime)
        assertThat(session.enabled).isTrue()
        assertThat(session.fromSettingsOnly).isFalse()
    }

    @Test
    fun `a step may run past bedtime`() {
        val late = activity(5, lead = 5, duration = 20)

        val step = planner.plan(evening, windowStart, bedtime, listOf(late), emptyList(), enabled = true).steps.single()

        assertThat(step.start).isEqualTo(at("22:55"))
        assertThat(step.end).isEqualTo(at("23:15"))
    }

    @Test
    fun `checks come from this evening's logs only`() {
        val logs = listOf(log(evening, 3, "22:18"), log(evening.minusDays(1), 1, "22:35"))

        val session = planner.plan(evening, windowStart, bedtime, routine, logs, enabled = false, fromSettingsOnly = true)

        assertThat(session.steps.filter { it.done }.map { it.activity.id }).containsExactly(3L)
        assertThat(session.steps.single { it.activity.id == 3L }.doneAt).isEqualTo(at("22:18"))
        assertThat(session.doneCount).isEqualTo(1)
        assertThat(session.isComplete).isFalse()
        assertThat(session.enabled).isFalse()
        assertThat(session.fromSettingsOnly).isTrue()
    }

    @Test
    fun `the current step is the first open one not yet over, else the first open one`() {
        val session = planner.plan(evening, windowStart, bedtime, routine, listOf(log(evening, 3, "22:18")), enabled = true)

        assertThat(session.currentStep(at("21:00"))?.activity?.id).isEqualTo(2L)
        assertThat(session.currentStep(at("22:00"))?.activity?.id).isEqualTo(2L) // end ≥ now
        assertThat(session.currentStep(at("22:25"))?.activity?.id).isEqualTo(1L)
        assertThat(session.currentStep(at("22:38"))?.activity?.id).isEqualTo(1L)
        assertThat(session.currentStep(at("23:30"))?.activity?.id).isEqualTo(2L)
    }

    @Test
    fun `a session is complete when every step is checked`() {
        val logs = routine.map { log(evening, it.id, "22:50") }

        val session = planner.plan(evening, windowStart, bedtime, routine, logs, enabled = true)

        assertThat(session.isComplete).isTrue()
        assertThat(session.doneCount).isEqualTo(4)
        assertThat(session.currentStep(at("22:00"))).isNull()
        assertThat(planner.plan(evening, windowStart, bedtime, emptyList(), logs, enabled = true).isComplete).isFalse()
    }

    @Test
    fun `an evening's own routine keeps its order and drops unknown ids`() {
        val own = EveningPlan(routine = listOf(3L, 99L, 1L, 3L))

        assertThat(routineFor(own, routine).map { it.id }).containsExactly(3L, 1L).inOrder()
    }

    @Test
    fun `without its own routine an evening uses the selected activities in stored order`() {
        assertThat(routineFor(EveningPlan(), routine).map { it.id }).containsExactly(1L, 2L, 4L).inOrder()
        assertThat(routineFor(EveningPlan(routine = emptyList()), routine)).isEmpty()
    }
}
