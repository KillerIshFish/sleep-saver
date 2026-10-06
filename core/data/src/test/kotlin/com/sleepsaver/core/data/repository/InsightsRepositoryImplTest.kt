package com.sleepsaver.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.testing.FakeHabitsRepository
import com.sleepsaver.core.data.testing.FakeSettingsRepository
import com.sleepsaver.core.data.testing.FakeSleepRepository
import com.sleepsaver.core.data.testing.FakeTimeProvider
import com.sleepsaver.core.data.testing.assumeInsightsEngineImplemented
import com.sleepsaver.core.data.testing.instant
import com.sleepsaver.core.data.testing.napOnlyDay
import com.sleepsaver.core.data.testing.testNight
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.LastNightState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class InsightsRepositoryImplTest {

    private val today = LocalDate.of(2026, 10, 6)
    private val sleep = FakeSleepRepository()
    private val settings = FakeSettingsRepository()
    private val habits = FakeHabitsRepository()
    private val time = FakeTimeProvider(now = instant("2026-10-06T09:00:00Z"))

    private fun TestScope.repository() =
        InsightsRepositoryImpl(sleep, settings, habits, time, backgroundScope, StandardTestDispatcher(testScheduler))

    @Test
    fun `starts loading, then reports no nights while none is present`() = runTest {
        val repository = repository()
        assertThat(repository.state.value).isEqualTo(InsightsState.Loading)

        runCurrent()
        assertThat(repository.state.value).isEqualTo(InsightsState.NoNights)

        sleep.state.value = listOf(napOnlyDay(today))
        runCurrent()
        assertThat(repository.state.value).isEqualTo(InsightsState.NoNights)
    }

    @Test
    fun `schedule is null without a present night on or before the date`() = runTest {
        sleep.state.value = listOf(napOnlyDay(today.minusDays(1)), testNight(today.plusDays(1)))

        assertThat(repository().schedule(today).first()).isNull()
    }

    // ------------------------------------------------ Needs the core:domain engine (skipped until it is merged)

    @Test
    fun `state is ready once a night is present`() = runTest {
        assumeInsightsEngineImplemented()
        sleep.state.value = (0L..6L).map { testNight(today.minusDays(it)) }.reversed()
        val repository = repository()

        runCurrent()

        val state = repository.state.value
        assertThat(state).isInstanceOf(InsightsState.Ready::class.java)
        assertThat((state as InsightsState.Ready).insights.today).isEqualTo(today)
    }

    @Test
    fun `schedule, debt series and breakdown use the stored nights`() = runTest {
        assumeInsightsEngineImplemented()
        sleep.state.value = (0L..6L).map { testNight(today.minusDays(it), wokeUp = "06:30") }.reversed()
        val repository = repository()

        val schedule = repository.schedule(today).first()
        val series = repository.debtSeries(today.minusDays(2), today).first()
        val breakdown = repository.breakdown(today)

        assertThat(schedule?.date).isEqualTo(today)
        assertThat(series.map { it.asOf }).containsExactly(today.minusDays(2), today.minusDays(1), today).inOrder()
        assertThat(breakdown.wakeDate).isEqualTo(today)
        assertThat(breakdown.need).isEqualTo(settings.state.value.sleep.sleepNeed)
    }

    @Test
    fun `today's schedule matches the home screen while last night is still missing`() = runTest {
        assumeInsightsEngineImplemented()
        // 7 h nights against an 8 h need: enough debt for it to shape grogginess, but below the inertia cap.
        sleep.state.value = (1L..10L).map { testNight(today.minusDays(it), fellAsleep = "23:30", wokeUp = "06:30") }.reversed()
        val repository = repository()
        runCurrent()
        val home = (repository.state.value as InsightsState.Ready).insights
        assertThat(home.lastNightState).isEqualTo(LastNightState.WAITING)

        assertThat(repository.schedule(today).first()).isEqualTo(home.schedule)
    }

    @Test
    fun `schedule follows a time zone change`() = runTest {
        assumeInsightsEngineImplemented()
        sleep.state.value = (0L..6L).map { testNight(today.minusDays(it)) }.reversed()
        val repository = repository()
        val schedules = mutableListOf<EnergySchedule?>()
        backgroundScope.launch { repository.schedule(today).collect { schedules += it } }
        runCurrent()
        assertThat(schedules.last()?.zone).isEqualTo(ZoneOffset.UTC)

        time.zone = ZoneId.of("Asia/Tokyo")
        time.ticks.value = time.now.plusSeconds(1)
        runCurrent()

        assertThat(schedules.last()?.zone).isEqualTo(ZoneId.of("Asia/Tokyo"))
    }

    @Test
    fun `a new day starts with the first minute tick after midnight`() = runTest {
        assumeInsightsEngineImplemented()
        sleep.state.value = (0L..6L).map { testNight(today.minusDays(it)) }.reversed()
        val repository = repository()
        runCurrent()

        time.now = instant("2026-10-07T00:00:30Z")
        time.ticks.value = time.now
        runCurrent()

        val insights = (repository.state.value as InsightsState.Ready).insights
        assertThat(insights.today).isEqualTo(today.plusDays(1))
        assertThat(insights.lastNightState).isNotEqualTo(LastNightState.AVAILABLE)
    }
}
