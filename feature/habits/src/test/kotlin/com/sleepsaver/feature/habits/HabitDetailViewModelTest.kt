package com.sleepsaver.feature.habits

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.domain.model.HabitAnchor
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.testing.FakeHabitsRepository
import com.sleepsaver.core.testing.FakeInsightsRepository
import com.sleepsaver.core.testing.FakeScheduleRepository
import com.sleepsaver.core.testing.SampleData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.DayOfWeek
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class HabitDetailViewModelTest {

    private val habits = FakeHabitsRepository()
    private val planner = RecordingReminderPlanner()
    private val insights = SampleData.insights()
    private val schedule = FakeScheduleRepository()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        type: String,
        state: InsightsState = InsightsState.Ready(insights),
        repository: HabitsRepository = habits,
        scheduleRepository: ScheduleRepository = schedule,
    ): HabitDetailViewModel {
        val viewModel = HabitDetailViewModel(
            SavedStateHandle(mapOf("type" to type)),
            repository,
            FakeInsightsRepository(state),
            writer(repository, planner),
            scheduleRepository,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel
    }

    private fun TestScope.viewModel(type: HabitType) = viewModel(type.name)

    private suspend fun HabitDetailViewModel.ready(): HabitDetailUiState.Ready =
        uiState.first { it is HabitDetailUiState.Ready } as HabitDetailUiState.Ready

    private fun storedOffset(type: HabitType): Duration = habits.settingsState.value.first { it.type == type }.offset

    @Test
    fun `caffeine shows today's time measured from the target bedtime`() = runTest {
        val state = viewModel(HabitType.CAFFEINE_CUTOFF).ready()

        assertThat(state.enabled).isTrue()
        assertThat(state.reminderEnabled).isTrue()
        assertThat(state.anchor).isEqualTo(HabitAnchor.TARGET_BEDTIME)
        assertThat(state.anchorTime).isEqualTo(insights.schedule.targetBedtime)
        assertThat(state.time).isEqualTo(insights.schedule.targetBedtime.minus(Duration.ofHours(10)))
        assertThat(state.windowEnd).isNull()
        assertThat(state.zone).isEqualTo(SampleData.zone)
        assertThat(state.value).isEqualTo(600)
        assertThat(state.defaultValue).isEqualTo(600)
        assertThat(state.isDefaultTiming).isTrue()
        assertThat(state.timing).isEqualTo(TimingSpec(TimingKind.BEFORE_BEDTIME, min = 240, max = 840, step = 30))
        assertThat(state.activities).isEmpty()
    }

    @Test
    fun `phase-anchored habits are measured from their phase`() = runTest {
        val dim = viewModel(HabitType.DIM_LIGHTS).ready()
        val nap = viewModel(HabitType.NAP_WINDOW).ready()
        val light = viewModel(HabitType.MORNING_LIGHT).ready()

        assertThat(dim.anchorTime).isEqualTo(insights.schedule.phase(PhaseType.WIND_DOWN)!!.start)
        assertThat(dim.windowEnd).isNotNull()
        assertThat(nap.anchorTime).isEqualTo(insights.schedule.phase(PhaseType.AFTERNOON_DIP)!!.start)
        assertThat(light.anchorTime).isEqualTo(insights.schedule.typicalWake.toInstant())
        assertThat(light.time).isEqualTo(insights.schedule.typicalWake.toInstant().plus(Duration.ofMinutes(15)))
        assertThat(light.value).isEqualTo(15)
    }

    @Test
    fun `an unknown habit type is not found`() = runTest {
        assertThat(viewModel("SECOND_COFFEE").uiState.value).isEqualTo(HabitDetailUiState.NotFound)
    }

    @Test
    fun `without nights the habit still loads, with no time`() = runTest {
        val state = viewModel(HabitType.BEDTIME.name, InsightsState.NoNights).ready()

        assertThat(state.times).isEqualTo(TimesStatus.NO_NIGHTS)
        assertThat(state.time).isNull()
        assertThat(state.anchorTime).isNull()
    }

    @Test
    fun `toggles are saved and replanned`() = runTest {
        val viewModel = viewModel(HabitType.ALCOHOL_CUTOFF)

        viewModel.setEnabled(true)
        viewModel.setReminder(true)

        val state = viewModel.ready()
        assertThat(state.enabled).isTrue()
        assertThat(state.reminderEnabled).isTrue()
        assertThat(planner.replans).isEqualTo(2)
    }

    @Test
    fun `cutoff timing is hours before bedtime and moves today's time`() = runTest {
        val viewModel = viewModel(HabitType.CAFFEINE_CUTOFF)

        viewModel.setTimingValue(8 * 60)

        assertThat(storedOffset(HabitType.CAFFEINE_CUTOFF)).isEqualTo(Duration.ofHours(-8))
        val state = viewModel.ready()
        assertThat(state.value).isEqualTo(480)
        assertThat(state.isDefaultTiming).isFalse()
        assertThat(state.time).isEqualTo(insights.schedule.targetBedtime.minus(Duration.ofHours(8)))
        assertThat(planner.replans).isEqualTo(1)
    }

    @Test
    fun `caffeine timing is kept between 4 and 14 hours`() = runTest {
        val viewModel = viewModel(HabitType.CAFFEINE_CUTOFF)

        viewModel.setTimingValue(20 * 60)
        assertThat(storedOffset(HabitType.CAFFEINE_CUTOFF)).isEqualTo(Duration.ofHours(-14))

        viewModel.setTimingValue(30)
        assertThat(storedOffset(HabitType.CAFFEINE_CUTOFF)).isEqualTo(Duration.ofHours(-4))
    }

    @Test
    fun `meal, alcohol and exercise cutoffs have their own ranges`() = runTest {
        val meal = viewModel(HabitType.LARGE_MEAL_CUTOFF)
        val alcohol = viewModel(HabitType.ALCOHOL_CUTOFF)
        val exercise = viewModel(HabitType.EXERCISE_CUTOFF)

        meal.setTimingValue(10 * 60)
        alcohol.setTimingValue(0)
        exercise.setTimingValue(6 * 60)

        assertThat(storedOffset(HabitType.LARGE_MEAL_CUTOFF)).isEqualTo(Duration.ofHours(-6))
        assertThat(storedOffset(HabitType.ALCOHOL_CUTOFF)).isEqualTo(Duration.ofHours(-1))
        assertThat(storedOffset(HabitType.EXERCISE_CUTOFF)).isEqualTo(Duration.ofHours(-5))
    }

    @Test
    fun `morning light stays within 0 to 90 minutes after waking`() = runTest {
        val viewModel = viewModel(HabitType.MORNING_LIGHT)

        viewModel.setTimingValue(120)
        assertThat(storedOffset(HabitType.MORNING_LIGHT)).isEqualTo(Duration.ofMinutes(90))

        viewModel.setTimingValue(-15)
        assertThat(storedOffset(HabitType.MORNING_LIGHT)).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `phase habits shift at most an hour either way`() = runTest {
        val dim = viewModel(HabitType.DIM_LIGHTS)
        val nap = viewModel(HabitType.NAP_WINDOW)

        dim.setTimingValue(-90)
        nap.setTimingValue(75)

        assertThat(storedOffset(HabitType.DIM_LIGHTS)).isEqualTo(Duration.ofMinutes(-60))
        assertThat(storedOffset(HabitType.NAP_WINDOW)).isEqualTo(Duration.ofMinutes(60))
    }

    @Test
    fun `bedtime is the target bedtime itself and its timing can't be shifted`() = runTest {
        val viewModel = viewModel(HabitType.BEDTIME)

        viewModel.setTimingValue(45)
        viewModel.resetTiming()

        val state = viewModel.ready()
        assertThat(state.timing).isNull()
        assertThat(state.anchor).isEqualTo(HabitAnchor.TARGET_BEDTIME)
        assertThat(state.time).isEqualTo(insights.schedule.targetBedtime)
        assertThat(state.anchorTime).isEqualTo(insights.schedule.targetBedtime)
        assertThat(storedOffset(HabitType.BEDTIME)).isEqualTo(Duration.ZERO)
        assertThat(planner.replans).isEqualTo(0)
    }

    @Test
    fun `a bedtime offset stored by an older build is ignored`() = runTest {
        habits.setOffset(HabitType.BEDTIME, Duration.ofMinutes(-30))

        val state = viewModel(HabitType.BEDTIME).ready()

        // "Right at your target bedtime", not "30m before" it.
        assertThat(state.offset).isEqualTo(Duration.ZERO)
        assertThat(state.time).isEqualTo(insights.schedule.targetBedtime)
    }

    @Test
    fun `setting the current value writes nothing`() = runTest {
        val viewModel = viewModel(HabitType.CAFFEINE_CUTOFF)
        viewModel.ready()

        viewModel.setTimingValue(600)

        assertThat(planner.replans).isEqualTo(0)
    }

    @Test
    fun `reset restores the default timing and replans`() = runTest {
        val viewModel = viewModel(HabitType.EXERCISE_CUTOFF)
        viewModel.setTimingValue(4 * 60)

        viewModel.resetTiming()

        assertThat(storedOffset(HabitType.EXERCISE_CUTOFF)).isEqualTo(Duration.ofHours(-2))
        assertThat(viewModel.ready().isDefaultTiming).isTrue()
        assertThat(planner.replans).isEqualTo(2)
    }

    @Test
    fun `quick stepper taps each move one step before the database catches up`() = runTest {
        val slow = SlowOffsetHabitsRepository(habits)
        val viewModel = viewModel(HabitType.CAFFEINE_CUTOFF.name, repository = slow)
        viewModel.ready()

        viewModel.setTimingValue(viewModel.ready().value - 30)
        viewModel.setTimingValue(viewModel.ready().value - 30)

        assertThat(viewModel.ready().value).isEqualTo(540)
        assertThat(viewModel.ready().time).isEqualTo(insights.schedule.targetBedtime.minus(Duration.ofMinutes(540)))
        assertThat(storedOffset(HabitType.CAFFEINE_CUTOFF)).isEqualTo(Duration.ofHours(-10))

        slow.gate.complete(Unit)
        advanceUntilIdle()

        assertThat(storedOffset(HabitType.CAFFEINE_CUTOFF)).isEqualTo(Duration.ofMinutes(-540))
        assertThat(viewModel.ready().value).isEqualTo(540)
        assertThat(planner.replans).isEqualTo(2)
    }

    @Test
    fun `a failed timing change goes back to the stored value`() = runTest {
        val viewModel = viewModel(HabitType.CAFFEINE_CUTOFF.name, repository = FailingHabitsRepository())

        viewModel.setTimingValue(8 * 60)

        assertThat(viewModel.ready().value).isEqualTo(600)
    }

    @Test
    fun `reset shows the default right away`() = runTest {
        val slow = SlowOffsetHabitsRepository(habits)
        habits.setOffset(HabitType.EXERCISE_CUTOFF, Duration.ofHours(-4))
        val viewModel = viewModel(HabitType.EXERCISE_CUTOFF.name, repository = slow)
        assertThat(viewModel.ready().value).isEqualTo(240)

        viewModel.resetTiming()

        assertThat(viewModel.ready().isDefaultTiming).isTrue()
        assertThat(storedOffset(HabitType.EXERCISE_CUTOFF)).isEqualTo(Duration.ofHours(-4))

        slow.gate.complete(Unit)
        advanceUntilIdle()

        assertThat(storedOffset(HabitType.EXERCISE_CUTOFF)).isEqualTo(Duration.ofHours(-2))
        assertThat(viewModel.ready().isDefaultTiming).isTrue()
        assertThat(planner.replans).isEqualTo(1)
    }

    @Test
    fun `a stored offset outside the range is shown clamped`() = runTest {
        habits.setOffset(HabitType.CAFFEINE_CUTOFF, Duration.ofHours(-16))

        assertThat(viewModel(HabitType.CAFFEINE_CUTOFF).ready().value).isEqualTo(14 * 60)
    }

    @Test
    fun `the evening routine lists the picked activities`() = runTest {
        val state = viewModel(HabitType.EVENING_ROUTINE).ready()

        assertThat(state.activities.map { it.label }).containsExactly("Reading", "Podcast").inOrder()
        assertThat(state.stepsOffEvenings).isEmpty()
    }

    @Test
    fun `the evening routine names the evenings the week plan switches Night steps off`() = runTest {
        val viewModel = viewModel(HabitType.EVENING_ROUTINE)

        schedule.weekState.value = weekWithStepsOff(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)

        assertThat(viewModel.ready().stepsOffEvenings).containsExactly(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
    }

    @Test
    fun `other habits and an unreadable week plan leave the line out`() = runTest {
        schedule.weekState.value = weekWithStepsOff(DayOfWeek.FRIDAY)
        assertThat(viewModel(HabitType.DIM_LIGHTS).ready().stepsOffEvenings).isEmpty()

        val failing = object : ScheduleRepository by schedule {
            override val weekPlan: Flow<WeekPlan> = flow { throw IOException("disk") }
        }
        val state = viewModel(HabitType.EVENING_ROUTINE.name, scheduleRepository = failing).ready()
        assertThat(state.stepsOffEvenings).isEmpty()
        assertThat(state.activities).isNotEmpty()
    }

    @Test
    fun `failed writes don't crash or replan`() = runTest {
        val viewModel = viewModel(HabitType.CAFFEINE_CUTOFF.name, repository = FailingHabitsRepository())

        viewModel.setEnabled(false)
        viewModel.setTimingValue(300)
        viewModel.resetTiming()

        assertThat(planner.replans).isEqualTo(0)
        assertThat(viewModel.ready().enabled).isTrue()
    }
}
