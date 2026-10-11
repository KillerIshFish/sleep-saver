package com.sleepsaver.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.testing.FakeHabitsRepository
import com.sleepsaver.core.data.testing.FakeScheduleRepository
import com.sleepsaver.core.data.testing.FakeSettingsRepository
import com.sleepsaver.core.data.testing.FakeSleepRepository
import com.sleepsaver.core.data.testing.FakeTimeProvider
import com.sleepsaver.core.data.testing.GARMIN
import com.sleepsaver.core.data.testing.assumeInsightsEngineImplemented
import com.sleepsaver.core.data.testing.instant
import com.sleepsaver.core.data.testing.latencySource
import com.sleepsaver.core.data.testing.napOnlyDay
import com.sleepsaver.core.data.testing.testNight
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.energy.EnergyScheduleCalculator
import com.sleepsaver.core.domain.journal.Anchor
import com.sleepsaver.core.domain.journal.AnchorSource
import com.sleepsaver.core.domain.model.BedtimeMode
import com.sleepsaver.core.domain.model.BlockKind
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.LastNightState
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepBlock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class InsightsRepositoryImplTest {

    private val today = LocalDate.of(2026, 10, 6)
    private val sleep = FakeSleepRepository()
    private val settings = FakeSettingsRepository()
    private val habits = FakeHabitsRepository()
    private val time = FakeTimeProvider(now = instant("2026-10-06T09:00:00Z"))
    private val schedule = FakeScheduleRepository()
    private val anchors = MutableStateFlow<List<Anchor>>(emptyList())

    private fun TestScope.repository(
        sleepRepository: SleepRepository = sleep,
        habitsRepository: HabitsRepository = habits,
    ) = InsightsRepositoryImpl(
        sleepRepository,
        settings,
        habitsRepository,
        schedule,
        latencySource(sleepRepository, anchors, time),
        time,
        backgroundScope,
        StandardTestDispatcher(testScheduler),
    )

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
        // Tomorrow's is planned from what is known today, and a night ending tomorrow isn't known yet.
        assertThat(repository().schedule(today.plusDays(1)).first()).isNull()
    }

    @Test
    fun `a failing nights flow ends in NoNights, and nothing waits forever`() = runTest {
        val broken = object : SleepRepository by sleep {
            override val nights: Flow<List<Night>> = flow { throw IllegalStateException("database closed") }
        }
        val repository = repository(sleepRepository = broken)

        // After the retries (virtual time) the state settles instead of staying on Loading.
        assertThat(repository.state.first { it != InsightsState.Loading }).isEqualTo(InsightsState.NoNights)
        assertThat(repository.schedule(today).first()).isNull()
        assertThat(repository.schedule(today.plusDays(1)).first()).isNull()
        assertThat(repository.debtSeries(today, today).first().map { it.asOf }).containsExactly(today)
        val breakdown = repository.breakdown(today)
        assertThat(breakdown.wakeDate).isEqualTo(today)
        assertThat(breakdown.slept).isEqualTo(Duration.ZERO)
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

    @Test
    fun `a failing habits flow falls back to the default habits`() = runTest {
        assumeInsightsEngineImplemented()
        sleep.state.value = (0L..6L).map { testNight(today.minusDays(it)) }.reversed()
        val broken = object : HabitsRepository by habits {
            override val habitSettings: Flow<List<HabitSettings>> = flow { throw IllegalStateException("database closed") }
        }
        val repository = repository(habitsRepository = broken)

        val state = repository.state.first { it != InsightsState.Loading }
        assertThat(state).isInstanceOf(InsightsState.Ready::class.java)
        assertThat((state as InsightsState.Ready).insights.habits).isNotEmpty()
    }

    @Test
    fun `tomorrow's schedule is planned from today's nights and today's displayed debt`() = runTest {
        assumeInsightsEngineImplemented()
        val tomorrow = today.plusDays(1)
        // Last night is still missing, so the home screen shows debt(yesterday); 7 h nights against an 8 h need.
        val history = (1L..10L).map { testNight(today.minusDays(it), fellAsleep = "23:30", wokeUp = "06:30") }.reversed()
        sleep.state.value = history
        val repository = repository()
        runCurrent()
        val home = (repository.state.value as InsightsState.Ready).insights
        assertThat(home.lastNightState).isEqualTo(LastNightState.WAITING)

        val schedule = repository.schedule(tomorrow).first()!!

        val sleepSettings = settings.state.value.sleep
        val calculator = EnergyScheduleCalculator(SleepConfig.DEFAULT)
        val expected = calculator.withShifts(
            calculator.schedule(tomorrow, ZoneOffset.UTC, history, sleepSettings.sleepNeed, home.debt.debt, sleepSettings.timing),
            home.schedule,
        )
        assertThat(schedule).isEqualTo(expected)
        assertThat(schedule.date).isEqualTo(tomorrow)
        assertThat(schedule.actualWake).isNull()
        assertThat(schedule.typicalWake.toLocalDate()).isEqualTo(tomorrow)
        assertThat(schedule.phases.all { it.shiftVsYesterday != null }).isTrue()

        // An evening doze tonight already belongs to tomorrow's wake date, but says nothing about tomorrow's wake.
        val dozeStart = today.atTime(20, 30).toInstant(ZoneOffset.UTC)
        val dozeEnd = today.atTime(21, 40).toInstant(ZoneOffset.UTC)
        val doze = testNight(tomorrow).copy(
            main = SleepBlock(BlockKind.MAIN, dozeStart, dozeEnd, ZoneOffset.UTC, ZoneOffset.UTC, Duration.ofMinutes(70), origin = GARMIN),
            fellAsleep = dozeStart,
            wokeUp = dozeEnd,
            mainSleep = Duration.ofMinutes(70),
            totalSleep = Duration.ofMinutes(70),
        )
        sleep.state.value = history + doze
        assertThat(repository.schedule(tomorrow).first()).isEqualTo(expected)
    }

    @Test
    fun `tomorrow's schedule follows the day change`() = runTest {
        assumeInsightsEngineImplemented()
        val tomorrow = today.plusDays(1)
        sleep.state.value = (0L..6L).map { testNight(today.minusDays(it)) }.reversed()
        val repository = repository()
        val schedules = mutableListOf<EnergySchedule?>()
        backgroundScope.launch { repository.schedule(tomorrow).collect { schedules += it } }
        runCurrent()
        assertThat(schedules.last()?.date).isEqualTo(tomorrow)

        // Midnight passes and tomorrow's night comes in: it now counts like any day's own night.
        sleep.state.value = sleep.state.value + testNight(tomorrow, wokeUp = "06:10")
        time.now = instant("2026-10-07T08:00:00Z")
        time.ticks.value = time.now
        runCurrent()

        assertThat(schedules.last()?.actualWake).isEqualTo(instant("2026-10-07T06:10:00Z"))
    }

    @Test
    fun `the schedule follows the week plan, its Night steps switch and the personal latency`() = runTest {
        assumeInsightsEngineImplemented()
        val tomorrow = today.plusDays(1)
        sleep.state.value = (0L..6L).map { testNight(today.minusDays(it)) }.reversed()
        settings.state.update { it.copy(sleep = it.sleep.copy(bedtimeMode = BedtimeMode.SLEEP_NEED, sleepNeed = Duration.ofHours(8))) }
        // Wednesday morning at 05:30; Tuesday evening without Night steps.
        schedule.weekState.update { week ->
            val wednesday = week.day(DayOfWeek.WEDNESDAY)
            val tuesday = week.day(DayOfWeek.TUESDAY)
            week.with(wednesday.copy(morning = wednesday.morning.copy(wakeTime = LocalTime.of(5, 30))))
                .with(tuesday.copy(evening = tuesday.evening.copy(nightStepsOn = false)))
        }
        val repository = repository()

        val planned = repository.schedule(today).first()!!
        assertThat(repository.schedule(tomorrow).first()!!.typicalWake.toLocalTime()).isEqualTo(LocalTime.of(5, 30))
        assertThat(planned.nightStepsOn).isFalse()
        // B = Wednesday 05:30 − 8 h need − the default 15 min to fall asleep.
        assertThat(planned.targetBedtime).isEqualTo(instant("2026-10-06T21:15:00Z"))

        // Seven Drop anchors 40 min before the watch's onset: the personal latency (40 min) replaces the default.
        anchors.value = (0L..6L).map { back ->
            val wakeDate = today.minusDays(back)
            Anchor(back + 1, wakeDate.minusDays(1).atTime(22, 20).toInstant(ZoneOffset.UTC), ZoneOffset.UTC, wakeDate, AnchorSource.TONIGHT, false)
        }
        assertThat(repository.schedule(today).first { it?.targetBedtime != planned.targetBedtime }!!.targetBedtime)
            .isEqualTo(instant("2026-10-06T20:50:00Z"))
    }
}
