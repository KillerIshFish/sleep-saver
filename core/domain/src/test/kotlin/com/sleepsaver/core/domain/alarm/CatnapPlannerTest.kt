package com.sleepsaver.core.domain.alarm

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.config.CatnapConfig
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.energy.EnergyScheduleCalculator
import com.sleepsaver.core.domain.model.BedtimeMode
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.domain.model.SleepTiming
import com.sleepsaver.core.domain.testing.Fx
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.jupiter.api.Test

class CatnapPlannerTest {

    private val planner = CatnapPlanner()
    private val date = LocalDate.of(2026, 9, 30)
    private val twenty = Duration.ofMinutes(20)

    /** Woke at 07:00, B = 22:45 UTC. */
    private val schedule: EnergySchedule = EnergyScheduleCalculator().schedule(
        date = date,
        zone = Fx.UTC,
        nights = (0L..6L).map { Fx.night(date.minusDays(it), "23:00", "07:00") },
        need = Duration.ofHours(8),
        debt = Duration.ZERO,
        timing = SleepTiming(wakeTime = LocalTime.of(7, 0), bedtimeMode = BedtimeMode.SLEEP_NEED),
    )
    private val dip = schedule.phase(PhaseType.AFTERNOON_DIP)!!

    private fun at(time: String): Instant = Fx.instant(date, time)

    @Test
    fun `it rings after the time to fall asleep plus the nap`() {
        val plan = planner.plan(at("11:00"), twenty, schedule, caffeineCutoff = null)

        assertThat(plan.start).isEqualTo(at("11:00"))
        assertThat(plan.ringsAt).isEqualTo(at("11:25"))
        assertThat(plan.length).isEqualTo(twenty)
    }

    @Test
    fun `a nap in the afternoon dip says so`() {
        assertThat(dip.start).isLessThan(at("16:00"))

        assertThat(planner.plan(dip.start + Duration.ofMinutes(10), twenty, schedule, null).timing)
            .isEqualTo(CatnapTiming.IN_AFTERNOON_DIP)
    }

    @Test
    fun `a nap ringing within 6 hours of bedtime is close to bedtime`() {
        // B − 6 h = 16:45: a ring after it is close to bedtime.
        assertThat(planner.plan(at("16:21"), twenty, schedule, null).timing).isEqualTo(CatnapTiming.CLOSE_TO_BEDTIME)
        assertThat(planner.plan(at("16:20"), twenty, schedule, null).timing).isNotEqualTo(CatnapTiming.CLOSE_TO_BEDTIME)
        assertThat(planner.plan(at("16:21"), Duration.ofMinutes(10), schedule, null).timing).isNotEqualTo(CatnapTiming.CLOSE_TO_BEDTIME)
    }

    @Test
    fun `close to bedtime wins over the dip`() {
        val strict = CatnapPlanner(SleepConfig(catnap = CatnapConfig(latestBeforeBedtime = Duration.ofHours(10))))

        assertThat(strict.plan(dip.start + Duration.ofMinutes(10), twenty, schedule, null).timing)
            .isEqualTo(CatnapTiming.CLOSE_TO_BEDTIME)
    }

    @Test
    fun `less than 3 hours after waking is early`() {
        assertThat(planner.plan(at("09:59"), twenty, schedule, null).timing).isEqualTo(CatnapTiming.EARLY)
        assertThat(planner.plan(at("10:00"), twenty, schedule, null).timing).isEqualTo(CatnapTiming.FINE)
    }

    @Test
    fun `early counts from the typical wake time while last night isn't in`() {
        val noLastNight = schedule.copy(actualWake = null, typicalWake = schedule.typicalWake.withHour(8))

        assertThat(planner.plan(at("10:30"), twenty, noLastNight, null).timing).isEqualTo(CatnapTiming.EARLY)
        assertThat(planner.plan(at("10:30"), twenty, schedule, null).timing).isEqualTo(CatnapTiming.FINE)
    }

    @Test
    fun `without a schedule the timing is fine`() {
        assertThat(planner.plan(at("22:00"), twenty, schedule = null, caffeineCutoff = null).timing).isEqualTo(CatnapTiming.FINE)
    }

    @Test
    fun `thirty minutes may feel groggy`() {
        assertThat(planner.plan(at("11:00"), Duration.ofMinutes(30), schedule, null).mayFeelGroggy).isTrue()
        assertThat(planner.plan(at("11:00"), twenty, schedule, null).mayFeelGroggy).isFalse()
    }

    @Test
    fun `the coffee hint needs a short nap before the caffeine cutoff`() {
        val cutoff = at("12:45")

        assertThat(planner.plan(at("11:00"), twenty, schedule, cutoff).coffeeHint).isTrue()
        assertThat(planner.plan(at("11:00"), Duration.ofMinutes(30), schedule, cutoff).coffeeHint).isFalse()
        assertThat(planner.plan(at("12:45"), twenty, schedule, cutoff).coffeeHint).isFalse()
        assertThat(planner.plan(at("11:00"), twenty, schedule, caffeineCutoff = null).coffeeHint).isFalse()
    }
}
