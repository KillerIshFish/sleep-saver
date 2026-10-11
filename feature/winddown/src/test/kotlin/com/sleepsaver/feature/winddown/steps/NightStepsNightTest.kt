package com.sleepsaver.feature.winddown.steps

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.hooks.NightState
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.PlanSamples.NightVariant
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.feature.winddown.HushChoice
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Night steps' no-clock night and "Until my window" follow the night in focus (NightRules.focus, as on Tonight, Sleep
 * home and Energy), with only that night's own alarm: never the next alarm of any date.
 */
class NightStepsNightTest {

    private val zone = PlanSamples.zone

    /** Friday 2026-10-09. Saturday has no alarm; the next one is Monday's 6:45. */
    private val friday = PlanSamples.today.plusDays(3)
    private val saturday = friday.plusDays(1)
    private val mondayAlarm = PlanSamples.plannedAlarm(date = friday.plusDays(3))

    private fun at(date: LocalDate, time: String): Instant = PlanSamples.at(date, LocalTime.parse(time))

    private fun state(
        now: Instant,
        schedule: EnergySchedule,
        alarms: List<PlannedAlarm>,
        nightState: NightState = NightState(),
    ): NightStepsUiState.Ready {
        val session = PlanSamples.nightStepsSession(nightOf = schedule.date, bedtime = schedule.targetBedtime.atZone(zone).toLocalTime())
        return nightStepsUiState(
            session = session,
            adherence = PlanSamples.adherence(schedule.date),
            suggestions = emptyList(),
            effects = emptyList(),
            marks = emptySet(),
            week = PlanSamples.weekPlan(),
            alarms = alarms,
            schedule = schedule,
            nightState = nightState,
            hush = HushChoice(),
            now = now,
            zone = zone,
        ) as NightStepsUiState.Ready
    }

    @Test
    fun `a night with no morning alarm runs from bedtime to the wake zone, never stretched to Monday's alarm`() {
        val schedule = SampleData.schedule(friday)
        val bedtime = schedule.targetBedtime
        val end = schedule.tomorrowWakeZone.start
        val alarms = listOf(mondayAlarm)

        assertThat(state(bedtime - Duration.ofMinutes(1), schedule, alarms).night).isFalse()
        assertThat(state(bedtime + Duration.ofMinutes(30), schedule, alarms).night).isTrue()
        assertThat(state(end - Duration.ofMinutes(1), schedule, alarms).night).isTrue()
        assertThat(state(end, schedule, alarms).night).isFalse()
    }

    @Test
    fun `an anchor on such a night ends with Saturday's wake zone, not hours later`() {
        val schedule = SampleData.schedule(friday)
        val anchored = PlanSamples.nightState(NightVariant.ANCHORED, nightOf = friday) // dropped 22:55 on Friday

        assertThat(state(at(saturday, "02:00"), schedule, listOf(mondayAlarm), anchored).night).isTrue()
        assertThat(state(schedule.tomorrowWakeZone.start + Duration.ofHours(1), schedule, listOf(mondayAlarm), anchored).night).isFalse()
    }

    @Test
    fun `the small hours stay night once the schedule has moved on to today's evening`() {
        val wednesday = PlanSamples.today.plusDays(1)
        val schedule = SampleData.schedule(wednesday)
        // This morning's 6:45 alarm, its window from 6:15; tomorrow's after it.
        val alarms = listOf(PlanSamples.plannedAlarm(date = wednesday), PlanSamples.plannedAlarm(date = wednesday.plusDays(1)))

        assertThat(state(at(wednesday, "02:00"), schedule, alarms).night).isTrue()
        assertThat(state(at(wednesday, "06:14"), schedule, alarms).night).isTrue()
        assertThat(state(at(wednesday, "06:15"), schedule, alarms).night).isFalse()
    }

    @Test
    fun `Until my window is offered only for the alarm of the night in focus`() {
        val schedule = SampleData.schedule(friday)
        val evening = schedule.targetBedtime + Duration.ofMinutes(30)
        val saturdayAlarm = PlanSamples.plannedAlarm(date = saturday)

        assertThat(state(evening, schedule, listOf(mondayAlarm)).hush.untilWindowAvailable).isFalse()
        assertThat(state(at(saturday, "02:00"), schedule, listOf(mondayAlarm)).hush.untilWindowAvailable).isFalse()
        assertThat(state(evening, schedule, listOf(saturdayAlarm, mondayAlarm)).hush.untilWindowAvailable).isTrue()
        assertThat(state(at(saturday, "02:00"), schedule, listOf(saturdayAlarm, mondayAlarm)).hush.untilWindowAvailable).isTrue()
    }
}
