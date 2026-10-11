package com.sleepsaver.feature.widget

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.ClockFormat
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.domain.model.CircadianAnchor
import com.sleepsaver.core.domain.model.DailyInsights
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.EnergyCurve
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.LastNightState
import com.sleepsaver.core.domain.model.Phase
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.domain.model.SleepDebt
import com.sleepsaver.core.domain.model.SleepNeedEstimateResult
import com.sleepsaver.core.domain.model.Streak
import com.sleepsaver.core.domain.model.TimeSpan
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class DebtWidgetModelTest {

    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 10, 6)

    private fun at(time: String): Instant = today.atTime(LocalTime.parse(time)).atZone(zone).toInstant()

    private val phases = listOf(
        Phase(PhaseType.GROGGINESS, at("07:00"), at("08:10")),
        Phase(PhaseType.MORNING_PEAK, at("09:00"), at("11:20:30")),
        Phase(PhaseType.AFTERNOON_DIP, at("13:30"), at("16:00")),
        Phase(PhaseType.EVENING_PEAK, at("17:00"), at("20:00")),
        Phase(PhaseType.WIND_DOWN, at("21:15"), at("22:15")),
        Phase(PhaseType.MELATONIN_WINDOW, at("22:15"), at("23:15")),
    )

    @Test
    fun `shows the debt in tenths of an hour with its status`() {
        val model = DebtWidgetModel.from(insights(at("10:00"), debt = Duration.ofMinutes(193), status = DebtStatus.MODERATE), ClockFormat.H12)

        assertThat(model.debtTenths).isEqualTo(32)
        assertThat(model.status).isEqualTo(DebtStatus.MODERATE)
    }

    @Test
    fun `inside a phase it shows that phase and when it ends`() {
        val model = DebtWidgetModel.from(insights(at("10:00")), ClockFormat.H12)

        assertThat(model.phase).isEqualTo(PhaseLine(PhaseType.MORNING_PEAK, isCurrent = true, time = LocalTime.of(11, 20)))
    }

    @Test
    fun `between phases it shows the next one and when it starts`() {
        val model = DebtWidgetModel.from(insights(at("12:00")), ClockFormat.H12)

        assertThat(model.phase).isEqualTo(PhaseLine(PhaseType.AFTERNOON_DIP, isCurrent = false, time = LocalTime.of(13, 30)))
    }

    @Test
    fun `no phase line once the day's phases are over`() {
        assertThat(DebtWidgetModel.from(insights(at("23:30")), ClockFormat.H12).phase).isNull()
    }

    @Test
    fun `asks to connect until insights are ready`() {
        assertThat(DebtWidgetModel.from(InsightsState.NoNights, ClockFormat.SYSTEM)).isEqualTo(DebtWidgetModel.Disconnected)
        assertThat(DebtWidgetModel.from(InsightsState.Loading, ClockFormat.SYSTEM)).isEqualTo(DebtWidgetModel.Disconnected)
    }

    @Test
    fun `skips loading and ignores refreshes that change nothing on screen`() = runTest {
        val states = flowOf(
            InsightsState.Loading,
            InsightsState.Ready(insights(at("10:00"))),
            InsightsState.Ready(insights(at("10:01"))),
        )

        val models = debtWidgetModels(states, flowOf(AppSettings())).toList()

        assertThat(models).containsExactly(DebtWidgetModel.from(insights(at("10:00")), ClockFormat.SYSTEM))
    }

    private fun insights(
        now: Instant,
        debt: Duration = Duration.ofMinutes(84),
        status: DebtStatus = DebtStatus.LOW,
    ): DailyInsights {
        val wake = today.atTime(7, 0).atZone(zone)
        val schedule = EnergySchedule(
            date = today,
            zone = zone,
            typicalWake = wake,
            actualWake = wake.toInstant(),
            wakeZone = TimeSpan(at("06:30"), at("07:30")),
            tomorrowWakeZone = TimeSpan(at("06:30").plus(Duration.ofDays(1)), at("07:30").plus(Duration.ofDays(1))),
            targetBedtime = at("22:45"),
            lastNightBedtime = at("22:45").minus(Duration.ofDays(1)),
            phases = phases,
            curve = EnergyCurve(emptyList()),
            anchor = CircadianAnchor(180.0, LocalTime.of(7, 0), 10, isFallback = false),
        )
        return DailyInsights(
            today = today,
            now = now,
            zone = zone,
            lastNight = null,
            lastNightState = LastNightState.WAITING,
            debt = SleepDebt(today, debt, debt, emptyList(), emptyList()),
            debtChange = null,
            status = status,
            streak = Streak(0, null),
            energyPotential = 90,
            schedule = schedule,
            currentPhase = schedule.currentPhase(now),
            upcomingPhases = schedule.upcomingPhases(now),
            habits = emptyList(),
            nextHabit = null,
            missingNights = emptyList(),
            needEstimate = SleepNeedEstimateResult.NotEnoughData(0, 14, 0, 4),
        )
    }
}
