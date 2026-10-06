package com.sleepsaver.feature.habits

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.testing.FakeHabitsRepository
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

@RunWith(AndroidJUnit4::class)
class EveningRoutineViewModelTest {

    private val habits = FakeHabitsRepository(activities = ActivitiesWithCustom)
    private val planner = RecordingReminderPlanner()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(repository: HabitsRepository = habits): EveningRoutineViewModel {
        val viewModel = EveningRoutineViewModel(repository, writer(repository, planner))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel
    }

    private suspend fun EveningRoutineViewModel.ready(): EveningRoutineUiState.Ready =
        uiState.first { it is EveningRoutineUiState.Ready } as EveningRoutineUiState.Ready

    private fun activity(id: Long) = habits.activitiesState.value.first { it.id == id }

    @Test
    fun `lists every activity with the routine's state`() = runTest {
        val state = viewModel().ready()

        assertThat(state.activities).isEqualTo(ActivitiesWithCustom)
        assertThat(state.routineEnabled).isTrue()
        assertThat(state.canSave).isTrue()
        assertThat(state.hasCustom).isTrue()
        assertThat(state.done).isFalse()
    }

    @Test
    fun `tapping a tile selects and deselects it`() = runTest {
        val viewModel = viewModel()

        viewModel.setSelected(2, true)
        assertThat(activity(2).selected).isTrue()

        viewModel.setSelected(1, false)
        assertThat(activity(1).selected).isFalse()
        assertThat(viewModel.ready().activities.first { it.id == 1L }.selected).isFalse()
    }

    @Test
    fun `custom activities are trimmed, capped and get a default emoji`() = runTest {
        val viewModel = viewModel()

        viewModel.addCustom("  Puzzle  ", "🧩")
        viewModel.addCustom("A very long activity name that keeps going", " ")

        val added = viewModel.ready().activities.filter { it.isCustom }.drop(1)
        assertThat(added.map { it.label }).containsExactly("Puzzle", "A very long activity nam").inOrder()
        assertThat(added.map { it.emoji }).containsExactly("🧩", EveningRoutineViewModel.DEFAULT_CUSTOM_EMOJI).inOrder()
        assertThat(added.all { it.selected }).isTrue()
    }

    @Test
    fun `a blank label adds nothing`() = runTest {
        val viewModel = viewModel()

        viewModel.addCustom("   ", "🧩")

        assertThat(viewModel.ready().activities).hasSize(ActivitiesWithCustom.size)
    }

    @Test
    fun `only custom activities can be deleted`() = runTest {
        val viewModel = viewModel()
        viewModel.ready()

        viewModel.delete(1)
        viewModel.delete(8)

        val ids = viewModel.ready().activities.map { it.id }
        assertThat(ids).contains(1L)
        assertThat(ids).doesNotContain(8L)
        assertThat(viewModel.ready().hasCustom).isFalse()
    }

    @Test
    fun `add to schedule turns the routine on, replans and finishes`() = runTest {
        val repository = FakeHabitsRepository(
            settings = SampleData.habits.map { if (it.type == HabitType.EVENING_ROUTINE) it.copy(enabled = false) else it },
        )
        val viewModel = viewModel(repository)
        assertThat(viewModel.ready().routineEnabled).isFalse()

        viewModel.addToSchedule()

        val state = viewModel.ready()
        assertThat(repository.settingsState.value.first { it.type == HabitType.EVENING_ROUTINE }.enabled).isTrue()
        assertThat(state.routineEnabled).isTrue()
        assertThat(state.done).isTrue()
        assertThat(planner.replans).isEqualTo(1)
    }

    @Test
    fun `add to schedule needs at least one activity`() = runTest {
        val viewModel = viewModel()
        ActivitiesWithCustom.filter { it.selected }.forEach { viewModel.setSelected(it.id, false) }
        assertThat(viewModel.ready().canSave).isFalse()

        viewModel.addToSchedule()

        assertThat(viewModel.ready().done).isFalse()
        assertThat(planner.replans).isEqualTo(0)
    }

    @Test
    fun `a failed save keeps the picker open`() = runTest {
        val viewModel = viewModel(FailingHabitsRepository(FakeHabitsRepository(activities = ActivitiesWithCustom)))

        viewModel.setSelected(2, true)
        viewModel.addCustom("Tea", "🍵")
        viewModel.addToSchedule()

        assertThat(viewModel.ready().done).isFalse()
        assertThat(planner.replans).isEqualTo(0)
    }
}
