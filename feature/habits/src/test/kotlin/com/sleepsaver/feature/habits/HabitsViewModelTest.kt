package com.sleepsaver.feature.habits

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.testing.FakeHabitsRepository
import com.sleepsaver.core.testing.FakeInsightsRepository
import com.sleepsaver.core.testing.SampleData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class HabitsViewModelTest {

    private val habits = FakeHabitsRepository()
    private val planner = RecordingReminderPlanner()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        insights: InsightsState = SampleData.readyState(),
        repository: HabitsRepository = habits,
    ): HabitsViewModel {
        val viewModel = HabitsViewModel(repository, FakeInsightsRepository(insights), writer(repository, planner))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel
    }

    private suspend fun HabitsViewModel.ready(): HabitsUiState.Ready =
        uiState.first { it is HabitsUiState.Ready } as HabitsUiState.Ready

    @Test
    fun `default habits are split into scheduled and available`() = runTest {
        val state = viewModel().ready()

        assertThat(state.scheduled.map { it.type }).containsExactly(
            HabitType.MORNING_LIGHT,
            HabitType.CAFFEINE_CUTOFF,
            HabitType.DIM_LIGHTS,
            HabitType.EVENING_ROUTINE,
            HabitType.BEDTIME,
        ).inOrder()
        // By today's time: afternoon nap, then the evening cutoffs (equal times keep the stored order).
        assertThat(state.available.map { it.type }).containsExactly(
            HabitType.NAP_WINDOW,
            HabitType.LARGE_MEAL_CUTOFF,
            HabitType.ALCOHOL_CUTOFF,
            HabitType.EXERCISE_CUTOFF,
        ).inOrder()
        assertThat(state.times).isEqualTo(TimesStatus.READY)
        assertThat(state.zone).isEqualTo(SampleData.zone)
    }

    @Test
    fun `rows carry today's times in day order`() = runTest {
        val insights = SampleData.insights()
        val state = viewModel(InsightsState.Ready(insights)).ready()

        val times = state.scheduled.map { it.time }
        assertThat(times).doesNotContain(null)
        assertThat(times.filterNotNull()).isInOrder()
        val caffeine = state.scheduled.first { it.type == HabitType.CAFFEINE_CUTOFF }
        assertThat(caffeine.time).isEqualTo(insights.schedule.targetBedtime.minus(Duration.ofHours(10)))
        assertThat(caffeine.windowEnd).isNull()
        val bedtime = state.scheduled.first { it.type == HabitType.BEDTIME }
        assertThat(bedtime.windowEnd).isNotNull()
    }

    @Test
    fun `without nights rows keep their stored order and have no times`() = runTest {
        val state = viewModel(InsightsState.NoNights).ready()

        assertThat(state.times).isEqualTo(TimesStatus.NO_NIGHTS)
        assertThat(state.zone).isNull()
        assertThat((state.scheduled + state.available).map { it.time }.toSet()).containsExactly(null)
        assertThat(state.scheduled.map { it.type }).containsExactly(
            HabitType.MORNING_LIGHT,
            HabitType.CAFFEINE_CUTOFF,
            HabitType.DIM_LIGHTS,
            HabitType.EVENING_ROUTINE,
            HabitType.BEDTIME,
        ).inOrder()
    }

    @Test
    fun `while insights load the list still shows`() = runTest {
        val state = viewModel(InsightsState.Loading).ready()

        assertThat(state.times).isEqualTo(TimesStatus.LOADING)
        assertThat(state.scheduled).isNotEmpty()
    }

    @Test
    fun `switching a habit on moves it into the schedule and replans`() = runTest {
        val viewModel = viewModel()

        viewModel.setEnabled(HabitType.NAP_WINDOW, true)

        val state = viewModel.ready()
        assertThat(state.scheduled.map { it.type }).contains(HabitType.NAP_WINDOW)
        assertThat(state.available.map { it.type }).doesNotContain(HabitType.NAP_WINDOW)
        assertThat(planner.replans).isEqualTo(1)
    }

    @Test
    fun `switching a habit off moves it out of the schedule and replans`() = runTest {
        val viewModel = viewModel()

        viewModel.setEnabled(HabitType.CAFFEINE_CUTOFF, false)

        assertThat(viewModel.ready().available.map { it.type }).contains(HabitType.CAFFEINE_CUTOFF)
        assertThat(planner.replans).isEqualTo(1)
    }

    @Test
    fun `reminder toggles are saved and replanned`() = runTest {
        val viewModel = viewModel()

        viewModel.setReminder(HabitType.CAFFEINE_CUTOFF, false)
        viewModel.setReminder(HabitType.BEDTIME, false)
        viewModel.setReminder(HabitType.BEDTIME, true)

        val settings = habits.settingsState.value.associateBy { it.type }
        assertThat(settings.getValue(HabitType.CAFFEINE_CUTOFF).reminderEnabled).isFalse()
        assertThat(settings.getValue(HabitType.BEDTIME).reminderEnabled).isTrue()
        assertThat(planner.replans).isEqualTo(3)
        assertThat(viewModel.ready().scheduled.first { it.type == HabitType.CAFFEINE_CUTOFF }.reminderEnabled).isFalse()
    }

    @Test
    fun `any reminder on is reported for the notifications notice`() = runTest {
        val viewModel = viewModel()
        assertThat(viewModel.ready().anyReminderOn).isTrue()

        HabitType.entries.forEach { viewModel.setReminder(it, false) }

        assertThat(viewModel.ready().anyReminderOn).isFalse()
    }

    @Test
    fun `a failed write neither crashes nor replans`() = runTest {
        val viewModel = viewModel(repository = FailingHabitsRepository())

        viewModel.setEnabled(HabitType.NAP_WINDOW, true)
        viewModel.setReminder(HabitType.BEDTIME, false)

        assertThat(planner.replans).isEqualTo(0)
        assertThat(viewModel.ready().available.map { it.type }).contains(HabitType.NAP_WINDOW)
    }

    @Test
    fun `habit types missing from storage fall back to their defaults`() = runTest {
        val partial = FakeHabitsRepository(settings = SampleData.habits.filter { it.type != HabitType.BEDTIME })

        val state = viewModel(repository = partial).ready()

        assertThat(state.scheduled.map { it.type }).contains(HabitType.BEDTIME)
        assertThat(state.scheduled.size + state.available.size).isEqualTo(HabitType.entries.size)
    }
}
