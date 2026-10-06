package com.sleepsaver.core.domain.insights

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.debt.SleepDebtCalculator
import com.sleepsaver.core.domain.energy.EnergyScheduleCalculator
import com.sleepsaver.core.domain.habits.HabitCatalog
import com.sleepsaver.core.domain.habits.HabitScheduler
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.LastNightState
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.need.SleepNeedEstimator
import com.sleepsaver.core.domain.testing.Fx
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.jupiter.api.Test

/** Needs SleepDebtCalculator and SleepNeedEstimator (implemented in parallel). */
class InsightsEngineTest {

    private val engine = InsightsEngine()
    private val debtCalculator = SleepDebtCalculator()
    private val scheduleCalculator = EnergyScheduleCalculator()
    private val habitCatalog = HabitCatalog()
    private val habitScheduler = HabitScheduler(habitCatalog)
    private val today = LocalDate.of(2026, 9, 30)
    private val settings = UserSleepSettings()
    private val need = settings.sleepNeed
    private val zone = Fx.UTC

    /** 23:00 → 07:00 in bed (W = 07:00) with 7 h asleep: 1 h short every night. */
    private fun nights(days: Iterable<Long>): List<Night> =
        days.map { Fx.night(today.minusDays(it), "23:00", "07:00", total = Duration.ofHours(7)) }

    private fun at(time: String, day: LocalDate = today): Instant = LocalDateTime.of(day, LocalTime.parse(time)).toInstant(Fx.UTC)

    private fun compute(nights: List<Night>, now: Instant, s: UserSleepSettings = settings) =
        engine.compute(nights, s, habitCatalog.defaultSettings(), now, zone)

    @Test
    fun `last night in shows today's debt`() {
        val nights = nights(0L..20L)
        val byDate = nights.associateBy { it.wakeDate }

        val insights = compute(nights, at("10:00"))

        assertThat(insights.today).isEqualTo(today)
        assertThat(insights.lastNightState).isEqualTo(LastNightState.AVAILABLE)
        assertThat(insights.lastNight).isEqualTo(nights.first())
        assertThat(insights.debt).isEqualTo(debtCalculator.debtAsOf(today, byDate, need))
        assertThat(insights.debt.debt.toMinutes() / 60.0).isWithin(0.05).of((1 - Math.pow(0.85, 14.0)) / 0.15)
        assertThat(insights.debtChange).isEqualTo(Duration.ZERO)
        assertThat(insights.status).isEqualTo(DebtStatus.HIGH)
        assertThat(insights.energyPotential).isEqualTo(70)
        assertThat(insights.streak).isEqualTo(debtCalculator.streak(today, byDate, need, settings.streakThreshold))
        assertThat(insights.missingNights).isEmpty()
        assertThat(insights.needEstimate).isEqualTo(SleepNeedEstimator().estimate(nights, need, today))
    }

    @Test
    fun `schedule uses today's debt and shifts against yesterday's own data`() {
        // Last night was short and late; yesterday's schedule must not see it.
        val nights = listOf(Fx.night(today, "03:00", "07:00")) + nights(1L..20L)
        val byDate = nights.associateBy { it.wakeDate }
        val yesterday = today.minusDays(1)

        val insights = compute(nights, at("10:00"))

        val expected = scheduleCalculator.withShifts(
            today = scheduleCalculator.schedule(
                today, zone, nights, need, debtCalculator.debtAsOf(today, byDate, need).debt, settings.timing,
            ),
            yesterday = scheduleCalculator.schedule(
                yesterday, zone, nights.drop(1), need, debtCalculator.debtAsOf(yesterday, byDate, need).debt, settings.timing,
            ),
        )
        assertThat(insights.schedule).isEqualTo(expected)
        assertThat(insights.schedule.actualWake).isEqualTo(at("07:00"))
        assertThat(insights.schedule.phases.map { it.shiftVsYesterday }).doesNotContain(null)
        assertThat(insights.schedule.phase(PhaseType.MELATONIN_WINDOW)!!.shiftVsYesterday).isGreaterThan(Duration.ZERO)
    }

    @Test
    fun `phases and habits are rolled up for now`() {
        val nights = nights(0L..20L)
        val now = at("10:00")

        val insights = compute(nights, now)

        assertThat(insights.currentPhase).isEqualTo(insights.schedule.currentPhase(now))
        assertThat(insights.currentPhase?.type).isEqualTo(PhaseType.MORNING_PEAK)
        assertThat(insights.upcomingPhases).isEqualTo(insights.schedule.upcomingPhases(now))
        assertThat(insights.upcomingPhases.map { it.type }).containsExactly(
            PhaseType.AFTERNOON_DIP, PhaseType.EVENING_PEAK, PhaseType.WIND_DOWN, PhaseType.MELATONIN_WINDOW,
        ).inOrder()
        assertThat(insights.habits).isEqualTo(habitScheduler.schedule(insights.schedule, habitCatalog.defaultSettings()))
        assertThat(insights.nextHabit?.type).isEqualTo(HabitType.CAFFEINE_CUTOFF)
    }

    @Test
    fun `waiting for last night shows yesterday's debt`() {
        val nights = nights(1L..20L)
        val byDate = nights.associateBy { it.wakeDate }
        val yesterday = today.minusDays(1)

        val insights = compute(nights, at("06:30"))

        assertThat(insights.lastNightState).isEqualTo(LastNightState.WAITING)
        assertThat(insights.lastNight).isNull()
        assertThat(insights.debt.asOf).isEqualTo(yesterday)
        assertThat(insights.debt).isEqualTo(debtCalculator.debtAsOf(yesterday, byDate, need))
        assertThat(insights.debtChange).isEqualTo(
            debtCalculator.debtAsOf(yesterday, byDate, need).debt.minus(debtCalculator.debtAsOf(yesterday.minusDays(1), byDate, need).debt),
        )
        assertThat(insights.streak).isEqualTo(debtCalculator.streak(yesterday, byDate, need, settings.streakThreshold))
        assertThat(insights.schedule.actualWake).isNull()
        assertThat(insights.missingNights).doesNotContain(today)
    }

    @Test
    fun `before the expected wake window nothing is awaited yet`() {
        val insights = compute(nights(1L..20L), at("04:59"))

        assertThat(insights.lastNightState).isEqualTo(LastNightState.NOT_EXPECTED_YET)
        assertThat(insights.debt.asOf).isEqualTo(today.minusDays(1))
        assertThat(insights.missingNights).doesNotContain(today)
    }

    @Test
    fun `waiting starts two hours before typical wake and gives up at 14_00`() {
        val nights = nights(1L..20L)

        assertThat(compute(nights, at("05:00")).lastNightState).isEqualTo(LastNightState.WAITING)
        assertThat(compute(nights, at("13:59")).lastNightState).isEqualTo(LastNightState.WAITING)
        assertThat(compute(nights, at("14:00")).lastNightState).isEqualTo(LastNightState.MISSING)
        assertThat(compute(nights, at("14:00")).debt.asOf).isEqualTo(today.minusDays(1))
    }

    @Test
    fun `missing nights inside the window are listed but today is not while waiting`() {
        val withGap = nights((1L..20L).filterNot { it == 3L })

        val waiting = compute(withGap, at("08:00"))
        val available = compute(nights(listOf(0L)) + withGap, at("08:00"))

        assertThat(waiting.lastNightState).isEqualTo(LastNightState.WAITING)
        assertThat(waiting.missingNights).containsExactly(today.minusDays(3))
        assertThat(available.missingNights).containsExactly(today.minusDays(3))
    }

    @Test
    fun `nap-only day counts as last night not in`() {
        val napOnly = Fx.night(today, "23:00", "07:00").copy(main = null)

        val insights = compute(listOf(napOnly) + nights(1L..20L), at("09:00"))

        assertThat(insights.lastNight).isNull()
        assertThat(insights.lastNightState).isEqualTo(LastNightState.WAITING)
    }

    @Test
    fun `no previous day means no debt change`() {
        val insights = compute(nights(listOf(0L)), at("09:00"))

        assertThat(insights.lastNightState).isEqualTo(LastNightState.AVAILABLE)
        assertThat(insights.debtChange).isNull()
    }

    @Test
    fun `a new user without any nights gets the onboarding schedule`() {
        val insights = compute(emptyList(), at("08:00"))

        assertThat(insights.lastNight).isNull()
        assertThat(insights.lastNightState).isEqualTo(LastNightState.WAITING)
        assertThat(insights.debt.debt).isEqualTo(Duration.ZERO)
        assertThat(insights.debtChange).isNull()
        assertThat(insights.streak.days).isEqualTo(0)
        assertThat(insights.schedule.anchor.isFallback).isTrue()
        assertThat(insights.schedule.typicalWake.toLocalTime()).isEqualTo(settings.typicalWake)
        assertThat(insights.schedule.phases.map { it.shiftVsYesterday }).containsExactlyElementsIn(List(6) { Duration.ZERO })
        assertThat(insights.nextHabit?.type).isEqualTo(HabitType.CAFFEINE_CUTOFF)
        assertThat(insights.missingNights).isEmpty()
    }

    @Test
    fun `while waiting for last night the phases stay where yesterday put them`() {
        val nights = nights(1L..20L)

        val waiting = compute(nights, at("06:30"))
        val arrived = compute(nights(listOf(0L)) + nights, at("08:00"))

        assertThat(waiting.lastNightState).isEqualTo(LastNightState.WAITING)
        waiting.schedule.phases.forEach { phase ->
            assertThat(phase.shiftVsYesterday!!.abs()).isAtMost(Duration.ofMinutes(2))
        }
        // No snap-back once the night arrives.
        waiting.schedule.phases.zip(arrived.schedule.phases).forEach { (before, after) ->
            assertThat(Duration.between(before.start, after.start).abs()).isAtMost(Duration.ofMinutes(5))
            assertThat(Duration.between(before.end, after.end).abs()).isAtMost(Duration.ofMinutes(5))
        }
    }

    @Test
    fun `missing nights before the first night ever are not listed`() {
        val newUser = compute(nights(listOf(1L, 2L)), at("10:00"))
        val withGap = compute(nights(listOf(1L, 2L, 5L)), at("10:00"))

        assertThat(newUser.lastNightState).isEqualTo(LastNightState.WAITING)
        assertThat(newUser.missingNights).isEmpty()
        assertThat(withGap.missingNights).containsExactly(today.minusDays(3), today.minusDays(4)).inOrder()
        assertThat(withGap.debt.missingNights).hasSize(11)
    }

    @Test
    fun `a nap-only day before the first night doesn't count as the first night`() {
        val napOnly = Fx.night(today.minusDays(6), "23:00", "07:00").copy(main = null)

        val insights = compute(listOf(napOnly) + nights(listOf(1L, 2L)), at("10:00"))

        assertThat(insights.missingNights).isEmpty()
    }

    /** A night owl: in bed 02:30 → 10:30, so W = 10:30, bedtime 02:15, wind-down 00:45–01:45, melatonin 01:45–02:45. */
    private fun owlNights(days: Iterable<Long>): List<Night> = days.map { Fx.night(today.minusDays(it), "02:30", "10:30") }

    /** [day]'s schedule as the user saw it that day, with shifts vs the day before. */
    private fun expectedSchedule(nights: List<Night>, day: LocalDate): EnergySchedule {
        val byDate = nights.associateBy { it.wakeDate }
        fun on(d: LocalDate): EnergySchedule {
            val debtDate = if (byDate[d]?.isPresent == true) d else d.minusDays(1)
            val debt = debtCalculator.debtAsOf(debtDate, byDate, need).debt
            return scheduleCalculator.schedule(d, zone, nights.filter { !it.wakeDate.isAfter(d) }, need, debt, settings.timing)
        }
        return scheduleCalculator.withShifts(on(day), on(day.minusDays(1)))
    }

    @Test
    fun `after midnight a night owl still sees last evening's wind-down and bedtime`() {
        val nights = owlNights(1L..20L)
        val yesterday = today.minusDays(1)

        val insights = compute(nights, at("00:30"))

        val expected = expectedSchedule(nights, yesterday)
        assertThat(insights.schedule).isEqualTo(expected)
        assertThat(insights.schedule.date).isEqualTo(yesterday)
        assertThat(insights.schedule.targetBedtime).isEqualTo(at("02:15"))
        assertThat(insights.schedule.phases.map { it.shiftVsYesterday }).containsExactlyElementsIn(List(6) { Duration.ZERO })
        assertThat(insights.currentPhase).isNull()
        assertThat(insights.upcomingPhases.map { it.type })
            .containsExactly(PhaseType.WIND_DOWN, PhaseType.MELATONIN_WINDOW).inOrder()
        assertThat(insights.habits).isEqualTo(habitScheduler.schedule(expected, habitCatalog.defaultSettings()))
        assertThat(insights.nextHabit?.type).isEqualTo(HabitType.DIM_LIGHTS)
        assertThat(insights.nextHabit?.time).isEqualTo(at("00:45"))
        // Everything else stays on the calendar date.
        assertThat(insights.today).isEqualTo(today)
        assertThat(insights.lastNight).isNull()
        assertThat(insights.lastNightState).isEqualTo(LastNightState.NOT_EXPECTED_YET)
        assertThat(insights.debt.asOf).isEqualTo(yesterday)
        assertThat(insights.missingNights).isEmpty()

        val windDown = compute(nights, at("01:00"))
        assertThat(windDown.currentPhase).isEqualTo(expected.phase(PhaseType.WIND_DOWN))
        val bedtime = compute(nights, at("02:00"))
        assertThat(bedtime.currentPhase).isEqualTo(expected.phase(PhaseType.MELATONIN_WINDOW))
        assertThat(bedtime.nextHabit?.type).isEqualTo(HabitType.BEDTIME)
        assertThat(bedtime.nextHabit?.time).isEqualTo(at("01:45"))
    }

    @Test
    fun `at 00_30 a later owl is in the melatonin window with bedtime next`() {
        // In bed 01:00 → 09:00: W = 09:00, bedtime 00:45, melatonin window 00:15–01:15.
        val nights = (1L..20L).map { Fx.night(today.minusDays(it), "01:00", "09:00") }

        val insights = compute(nights, at("00:30"))

        assertThat(insights.schedule.date).isEqualTo(today.minusDays(1))
        assertThat(insights.currentPhase?.type).isEqualTo(PhaseType.MELATONIN_WINDOW)
        assertThat(insights.currentPhase).isEqualTo(insights.schedule.phase(PhaseType.MELATONIN_WINDOW))
        assertThat(insights.nextHabit?.type).isEqualTo(HabitType.BEDTIME)
        assertThat(insights.nextHabit?.time).isEqualTo(at("00:15"))
    }

    @Test
    fun `once last evening's melatonin window is over the owl gets today's schedule`() {
        val nights = owlNights(1L..20L)

        val atWindowEnd = compute(nights, at("02:45"))
        val insights = compute(nights, at("03:00"))

        assertThat(atWindowEnd.schedule.date).isEqualTo(today)
        assertThat(insights.schedule).isEqualTo(expectedSchedule(nights, today))
        assertThat(insights.schedule.date).isEqualTo(today)
        assertThat(insights.schedule.typicalWake.toLocalTime()).isEqualTo(LocalTime.of(10, 30))
        assertThat(insights.currentPhase).isNull()
        assertThat(insights.nextHabit?.type).isEqualTo(HabitType.MORNING_LIGHT)
        assertThat(insights.nextHabit?.time).isEqualTo(at("10:45"))
    }

    @Test
    fun `an early bird after midnight gets today's schedule`() {
        // In bed 22:00 → 06:00: bedtime 21:45, so yesterday's melatonin window ended at 22:15.
        val nights = (1L..20L).map { Fx.night(today.minusDays(it), "22:00", "06:00") }

        val insights = compute(nights, at("00:30"))

        assertThat(insights.schedule.date).isEqualTo(today)
        assertThat(insights.schedule).isEqualTo(expectedSchedule(nights, today))
        assertThat(insights.nextHabit?.type).isEqualTo(HabitType.MORNING_LIGHT)
        assertThat(compute(nights(1L..20L), at("00:30")).schedule.date).isEqualTo(today)
    }

    @Test
    fun `status thresholds come from the user's settings`() {
        val relaxed = settings.copy(lowDebtMax = Duration.ofHours(3), highDebtMin = Duration.ofHours(7))

        assertThat(compute(nights(0L..20L), at("10:00"), relaxed).status).isEqualTo(DebtStatus.MODERATE)
    }

    @Test
    fun `today is the date of now in the given zone`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val insights = engine.compute(nights(0L..20L), settings, emptyList(), Instant.parse("2026-09-30T20:00:00Z"), tokyo)

        assertThat(insights.today).isEqualTo(today.plusDays(1))
        assertThat(insights.schedule.date).isEqualTo(today.plusDays(1))
        assertThat(insights.zone).isEqualTo(tokyo)
    }
}
