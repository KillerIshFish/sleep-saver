package com.sleepsaver.core.domain.nightsteps

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.testing.Fx
import java.time.LocalDate
import org.junit.jupiter.api.Test

class StepAdherenceTest {

    /** Sunday 2026-10-11 (the evening). Monday of that week is the 5th. */
    private val sunday = LocalDate.of(2026, 10, 11)
    private val monday = sunday.minusDays(6)

    private fun day(offsetFromMonday: Long): LocalDate = monday.plusDays(offsetFromMonday)

    /** [count] checked steps (activity ids 1..count) on [evening]. */
    private fun done(evening: LocalDate, count: Int): List<StepLog> =
        (1..count).map { StepLog(evening, it.toLong(), technique = null, doneAt = Fx.instant(evening, "22:30"), offset = Fx.UTC) }

    private fun states(adherence: StepAdherence) = adherence.evenings.associate { it.nightOf to it.state }

    @Test
    fun `evenings with steps off are neutral`() {
        val off = setOf(day(4), day(5)) // Friday and Saturday
        val logs = (0L..3L).flatMap { done(day(it), 2) } + done(sunday, 3)

        val adherence = stepAdherence(logs, { if (it in off) 0 else 4 }, tonight = sunday, tonightOver = true)

        assertThat(states(adherence)[day(4)]).isEqualTo(EveningState.NEUTRAL)
        assertThat(states(adherence)[day(5)]).isEqualTo(EveningState.NEUTRAL)
        assertThat(adherence.doneRecent).isEqualTo(5)
        assertThat(adherence.countedRecent).isEqualTo(5)
        assertThat(adherence.quietStreak).isEqualTo(5)
    }

    @Test
    fun `an empty routine is neutral`() {
        val logs = done(day(0), 2) + done(day(1), 2)

        val adherence = stepAdherence(logs, { if (it == day(2)) 0 else 2 }, tonight = day(2), tonightOver = true)

        assertThat(adherence.evenings.last().state).isEqualTo(EveningState.NEUTRAL)
        assertThat(adherence.quietStreak).isEqualTo(2)
    }

    @Test
    fun `a new user has nothing before the first log`() {
        val adherence = stepAdherence(done(sunday, 2), { 4 }, tonight = sunday, tonightOver = true)

        assertThat(adherence.evenings).hasSize(14)
        assertThat(adherence.evenings.map { it.nightOf }).isEqualTo((13L downTo 0L).map { sunday.minusDays(it) })
        assertThat(adherence.evenings.dropLast(1).map { it.state }.toSet()).containsExactly(EveningState.NEUTRAL)
        assertThat(adherence.doneRecent).isEqualTo(1)
        assertThat(adherence.countedRecent).isEqualTo(1)
        assertThat(adherence.quietStreak).isEqualTo(1)
    }

    @Test
    fun `without any logs everything is neutral`() {
        val adherence = stepAdherence(emptyList(), { 4 }, tonight = sunday, tonightOver = false)

        assertThat(adherence.evenings.map { it.state }.toSet()).containsExactly(EveningState.NEUTRAL)
        assertThat(adherence.countedRecent).isEqualTo(0)
        assertThat(adherence.quietStreak).isEqualTo(0)
    }

    @Test
    fun `a single missed evening is bridged and two in a row end the streak`() {
        // Mon done, Tue and Wed missed, Thu and Fri done, Sat missed, Sun done.
        val logs = done(day(0), 2) + done(day(3), 2) + done(day(4), 2) + done(sunday, 2)

        val adherence = stepAdherence(logs, { 2 }, tonight = sunday, tonightOver = true)

        assertThat(states(adherence)[day(5)]).isEqualTo(EveningState.MISSED)
        assertThat(adherence.quietStreak).isEqualTo(3) // Sun, Fri, Thu; Wed + Tue stop it
        assertThat(adherence.doneRecent).isEqualTo(4)
        assertThat(adherence.countedRecent).isEqualTo(7)
    }

    @Test
    fun `a miss right at the start is bridged too`() {
        val logs = done(day(4), 1) + done(day(5), 1)

        val adherence = stepAdherence(logs, { 1 }, tonight = sunday, tonightOver = true)

        assertThat(adherence.evenings.last().state).isEqualTo(EveningState.MISSED)
        assertThat(adherence.quietStreak).isEqualTo(2)
    }

    @Test
    fun `an open tonight neither counts nor breaks`() {
        val logs = done(day(4), 2) + done(day(5), 2) + done(sunday, 1)

        val adherence = stepAdherence(logs, { 4 }, tonight = sunday, tonightOver = false)

        assertThat(adherence.evenings.last().state).isEqualTo(EveningState.OPEN)
        assertThat(adherence.evenings.last().done).isEqualTo(1)
        assertThat(adherence.countedRecent).isEqualTo(2)
        assertThat(adherence.quietStreak).isEqualTo(2)
        // Once the evening is over the same evening is missed.
        assertThat(stepAdherence(logs, { 4 }, tonight = sunday, tonightOver = true).evenings.last().state).isEqualTo(EveningState.MISSED)
    }

    @Test
    fun `tonight is done as soon as enough steps are checked`() {
        val adherence = stepAdherence(done(sunday, 2), { 4 }, tonight = sunday, tonightOver = false)

        assertThat(adherence.evenings.last().state).isEqualTo(EveningState.DONE)
    }

    @Test
    fun `done needs at least half the steps and at least one`() {
        val tonight = sunday
        fun state(planned: Int, checked: Int) =
            stepAdherence(done(tonight.minusDays(1), 1) + done(tonight, checked), { planned }, tonight, tonightOver = true)
                .evenings.last().state

        assertThat(state(planned = 3, checked = 1)).isEqualTo(EveningState.MISSED)
        assertThat(state(planned = 3, checked = 2)).isEqualTo(EveningState.DONE)
        assertThat(state(planned = 1, checked = 1)).isEqualTo(EveningState.DONE)
        assertThat(state(planned = 1, checked = 0)).isEqualTo(EveningState.MISSED)
    }

    @Test
    fun `a step checked twice counts once`() {
        val logs = done(sunday, 1) + done(sunday, 1)

        val adherence = stepAdherence(logs, { 3 }, tonight = sunday, tonightOver = true)

        assertThat(adherence.evenings.last().done).isEqualTo(1)
        assertThat(adherence.evenings.last().state).isEqualTo(EveningState.MISSED)
    }
}
