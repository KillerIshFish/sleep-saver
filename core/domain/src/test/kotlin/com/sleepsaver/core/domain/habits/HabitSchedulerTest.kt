package com.sleepsaver.core.domain.habits

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.energy.EnergyScheduleCalculator
import com.sleepsaver.core.domain.model.BedtimeMode
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.domain.model.ReminderCategory
import com.sleepsaver.core.domain.model.ScheduledHabit
import com.sleepsaver.core.domain.model.SleepTiming
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.domain.testing.Fx
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.jupiter.api.Test

class HabitSchedulerTest {

    private val date = LocalDate.of(2026, 9, 30)
    private val catalog = HabitCatalog()
    private val scheduler = HabitScheduler(catalog)

    /** W = 07:00, B = 22:45 UTC. */
    private val schedule: EnergySchedule = EnergyScheduleCalculator().schedule(
        date = date,
        zone = Fx.UTC,
        nights = (0L..6L).map { Fx.night(date.minusDays(it), "23:00", "07:00") },
        need = Duration.ofHours(8),
        debt = Duration.ZERO,
        timing = SleepTiming(wakeTime = LocalTime.of(7, 0), bedtimeMode = BedtimeMode.SLEEP_NEED),
    )

    private fun at(time: String): Instant = LocalDateTime.of(date, LocalTime.parse(time)).toInstant(Fx.UTC)
    private fun List<ScheduledHabit>.of(type: HabitType) = single { it.type == type }

    @Test
    fun `default habits are placed relative to wake, bedtime and phases`() {
        val habits = scheduler.schedule(schedule, catalog.defaultSettings())
        val bedtime = schedule.targetBedtime
        val dipStart = schedule.phase(PhaseType.AFTERNOON_DIP)!!.start

        assertThat(bedtime).isEqualTo(at("22:45"))
        assertThat(habits.map { it.type }).containsExactlyElementsIn(HabitType.entries)
        assertThat(habits.of(HabitType.MORNING_LIGHT).time).isEqualTo(at("07:15"))
        assertThat(habits.of(HabitType.MORNING_LIGHT).windowEnd).isEqualTo(at("07:45"))
        assertThat(habits.of(HabitType.CAFFEINE_CUTOFF).time).isEqualTo(bedtime.minus(Duration.ofHours(10)))
        assertThat(habits.of(HabitType.CAFFEINE_CUTOFF).windowEnd).isNull()
        assertThat(habits.of(HabitType.LARGE_MEAL_CUTOFF).time).isEqualTo(bedtime.minus(Duration.ofHours(3)))
        assertThat(habits.of(HabitType.ALCOHOL_CUTOFF).time).isEqualTo(bedtime.minus(Duration.ofHours(3)))
        assertThat(habits.of(HabitType.EXERCISE_CUTOFF).time).isEqualTo(bedtime.minus(Duration.ofHours(2)))
        assertThat(habits.of(HabitType.DIM_LIGHTS).time).isEqualTo(at("21:15"))
        assertThat(habits.of(HabitType.DIM_LIGHTS).windowEnd).isEqualTo(at("22:15"))
        assertThat(habits.of(HabitType.EVENING_ROUTINE).time).isEqualTo(at("21:15"))
        // Bedtime is B itself (the bedtime every screen shows), until the melatonin window ends.
        assertThat(habits.of(HabitType.BEDTIME).time).isEqualTo(bedtime)
        assertThat(habits.of(HabitType.BEDTIME).windowEnd).isEqualTo(at("23:15"))
        assertThat(habits.of(HabitType.NAP_WINDOW).time).isEqualTo(dipStart)
        assertThat(habits.of(HabitType.NAP_WINDOW).windowEnd).isEqualTo(dipStart.plus(Duration.ofMinutes(30)))
    }

    @Test
    fun `habits are ordered by time with catalog order breaking ties`() {
        val habits = scheduler.schedule(schedule, catalog.defaultSettings())

        assertThat(habits.map { it.time }).isInOrder()
        assertThat(habits.map { it.type }.filter { it == HabitType.DIM_LIGHTS || it == HabitType.EVENING_ROUTINE })
            .containsExactly(HabitType.DIM_LIGHTS, HabitType.EVENING_ROUTINE).inOrder()
    }

    @Test
    fun `flags and categories come from settings and the catalog`() {
        val habits = scheduler.schedule(schedule, catalog.defaultSettings())

        assertThat(habits.of(HabitType.CAFFEINE_CUTOFF).enabled).isTrue()
        assertThat(habits.of(HabitType.LARGE_MEAL_CUTOFF).enabled).isFalse()
        assertThat(habits.of(HabitType.NAP_WINDOW).reminderEnabled).isFalse()
        assertThat(habits.of(HabitType.BEDTIME).category).isEqualTo(ReminderCategory.BEDTIME)
        assertThat(habits.of(HabitType.EXERCISE_CUTOFF).category).isEqualTo(ReminderCategory.CUTOFFS)
    }

    @Test
    fun `missing settings fall back to catalog defaults`() {
        assertThat(scheduler.schedule(schedule, emptyList())).isEqualTo(scheduler.schedule(schedule, catalog.defaultSettings()))
    }

    @Test
    fun `custom offsets and flags are applied`() {
        val settings = catalog.defaultSettings().map {
            when (it.type) {
                HabitType.CAFFEINE_CUTOFF -> it.copy(offset = Duration.ofHours(-8))
                HabitType.MORNING_LIGHT -> it.copy(offset = Duration.ofMinutes(30), reminderEnabled = false)
                HabitType.NAP_WINDOW -> it.copy(offset = Duration.ofMinutes(10), enabled = true)
                else -> it
            }
        }

        val habits = scheduler.schedule(schedule, settings)

        assertThat(habits.of(HabitType.CAFFEINE_CUTOFF).time).isEqualTo(at("14:45"))
        assertThat(habits.of(HabitType.MORNING_LIGHT).time).isEqualTo(at("07:30"))
        assertThat(habits.of(HabitType.MORNING_LIGHT).windowEnd).isEqualTo(at("08:00"))
        assertThat(habits.of(HabitType.MORNING_LIGHT).reminderEnabled).isFalse()
        val dipStart = schedule.phase(PhaseType.AFTERNOON_DIP)!!.start
        assertThat(habits.of(HabitType.NAP_WINDOW).time).isEqualTo(dipStart.plus(Duration.ofMinutes(10)))
        assertThat(habits.of(HabitType.NAP_WINDOW).enabled).isTrue()
    }

    @Test
    fun `a stored Bedtime offset never moves it off the target bedtime`() {
        // Older builds offered a Bedtime shift; a row saved then (or by any other writer) is ignored.
        listOf(Duration.ofMinutes(-30), Duration.ofMinutes(60)).forEach { stored ->
            val settings = catalog.defaultSettings().map { if (it.type == HabitType.BEDTIME) it.copy(offset = stored) else it }

            val bedtime = scheduler.schedule(schedule, settings).of(HabitType.BEDTIME)

            assertThat(bedtime.time).isEqualTo(schedule.targetBedtime)
            assertThat(bedtime.windowEnd).isEqualTo(at("23:15"))
        }
        assertThat(catalog.definition(HabitType.BEDTIME).adjustable).isFalse()
        assertThat(catalog.all.filter { !it.adjustable }.map { it.type }).containsExactly(HabitType.BEDTIME)
    }

    @Test
    fun `next up skips disabled habits`() {
        val habits = scheduler.schedule(schedule, catalog.defaultSettings())

        // 19:45 meal/alcohol and 20:45 exercise cutoffs are off by default.
        assertThat(habits.of(HabitType.LARGE_MEAL_CUTOFF).enabled).isFalse()
        assertThat(scheduler.nextUp(habits, at("19:00"))?.type).isEqualTo(HabitType.DIM_LIGHTS)

        val withMeal = scheduler.schedule(
            schedule,
            catalog.defaultSettings().map { if (it.type == HabitType.LARGE_MEAL_CUTOFF) it.copy(enabled = true) else it },
        )
        assertThat(scheduler.nextUp(withMeal, at("19:00"))?.type).isEqualTo(HabitType.LARGE_MEAL_CUTOFF)
    }

    @Test
    fun `next up keeps a habit until its window ends`() {
        val habits = scheduler.schedule(schedule, catalog.defaultSettings())

        assertThat(scheduler.nextUp(habits, at("06:00"))?.type).isEqualTo(HabitType.MORNING_LIGHT)
        assertThat(scheduler.nextUp(habits, at("07:30"))?.type).isEqualTo(HabitType.MORNING_LIGHT)
        assertThat(scheduler.nextUp(habits, at("07:45"))?.type).isEqualTo(HabitType.CAFFEINE_CUTOFF)
        assertThat(scheduler.nextUp(habits, at("12:45"))?.type).isNotEqualTo(HabitType.CAFFEINE_CUTOFF)
        assertThat(scheduler.nextUp(habits, at("22:30"))?.type).isEqualTo(HabitType.BEDTIME)
        assertThat(scheduler.nextUp(habits, at("23:15"))).isNull()
    }

    @Test
    fun `dim lights and the evening routine last the evening's wind-down`() {
        val week = WeekPlan.DEFAULT.let { plan ->
            plan.with(plan.day(date.dayOfWeek).let { it.copy(evening = it.evening.copy(windDownLength = Duration.ofMinutes(90))) })
        }
        val longWindDown = scheduleWith(week)
        val windDown = longWindDown.phase(PhaseType.WIND_DOWN)!!

        val habits = scheduler.schedule(longWindDown, catalog.defaultSettings())

        assertThat(habits.of(HabitType.DIM_LIGHTS).time).isEqualTo(at("20:45"))
        assertThat(habits.of(HabitType.DIM_LIGHTS).windowEnd).isEqualTo(windDown.end)
        assertThat(habits.of(HabitType.EVENING_ROUTINE).windowEnd).isEqualTo(at("22:15"))
        // Other windows keep the catalog's.
        assertThat(habits.of(HabitType.BEDTIME).windowEnd).isEqualTo(at("23:15"))
    }

    @Test
    fun `with Night steps off the evening routine stays listed without a reminder`() {
        val week = WeekPlan.DEFAULT.let { plan ->
            plan.with(plan.day(date.dayOfWeek).let { it.copy(evening = it.evening.copy(nightStepsOn = false)) })
        }
        val stepsOff = scheduleWith(week)

        val habits = scheduler.schedule(stepsOff, catalog.defaultSettings())

        assertThat(stepsOff.nightStepsOn).isFalse()
        assertThat(habits.of(HabitType.EVENING_ROUTINE).enabled).isTrue()
        assertThat(habits.of(HabitType.EVENING_ROUTINE).reminderEnabled).isFalse()
        assertThat(habits.of(HabitType.DIM_LIGHTS).reminderEnabled).isTrue()
        assertThat(scheduler.schedule(schedule, catalog.defaultSettings()).of(HabitType.EVENING_ROUTINE).reminderEnabled).isTrue()
    }

    private fun scheduleWith(week: WeekPlan): EnergySchedule = EnergyScheduleCalculator().schedule(
        date = date,
        zone = Fx.UTC,
        nights = (0L..6L).map { Fx.night(date.minusDays(it), "23:00", "07:00") },
        need = Duration.ofHours(8),
        debt = Duration.ZERO,
        timing = SleepTiming(wakeTime = LocalTime.of(7, 0), bedtimeMode = BedtimeMode.SLEEP_NEED, week = week),
    )

    @Test
    fun `catalog defaults match the plan`() {
        val defaults = catalog.defaultSettings().associateBy(HabitSettings::type)

        assertThat(defaults.filterValues { it.enabled }.keys).containsExactly(
            HabitType.MORNING_LIGHT,
            HabitType.CAFFEINE_CUTOFF,
            HabitType.DIM_LIGHTS,
            HabitType.EVENING_ROUTINE,
            HabitType.BEDTIME,
        )
        assertThat(catalog.definition(HabitType.NAP_WINDOW).window).isEqualTo(Duration.ofMinutes(30))
    }
}
