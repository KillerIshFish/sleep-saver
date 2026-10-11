package com.sleepsaver.feature.winddown.editor

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.NightStepsRepository
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.testing.FakeHabitsRepository
import com.sleepsaver.core.testing.FakeNightStepsRepository
import com.sleepsaver.core.testing.FakeScheduleRepository
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.feature.winddown.RecordingReminderPlanner
import com.sleepsaver.feature.winddown.richActivities
import com.sleepsaver.feature.winddown.writer
import kotlinx.coroutines.CompletableDeferred
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
import java.time.DayOfWeek
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class RoutineEditorViewModelTest {

    private val nightSteps = FakeNightStepsRepository(activities = richActivities())
    private val schedule = FakeScheduleRepository(week = WeekPlan.DEFAULT)
    private val habits = FakeHabitsRepository(settings = SampleData.habits)
    private val planner = RecordingReminderPlanner()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** [day] as RoutineEditorRoute carries it: DayOfWeek.value, 0 = every evening. */
    private fun TestScope.viewModel(day: Int = 0, repository: NightStepsRepository = nightSteps): RoutineEditorViewModel {
        val viewModel = RoutineEditorViewModel(
            savedStateHandle = SavedStateHandle(mapOf("day" to day)),
            nightSteps = repository,
            schedule = schedule,
            habits = habits,
            planner = planner,
            writer = writer(),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel
    }

    private suspend fun RoutineEditorViewModel.ready(): RoutineEditorUiState.Ready =
        uiState.first { it is RoutineEditorUiState.Ready } as RoutineEditorUiState.Ready

    private fun included(state: RoutineEditorUiState.Ready) = state.rows.filter { it.included }.map { it.activity.id }

    // ---------------------------------------------------------------- Every evening

    @Test
    fun `every evening lists every activity, the selected ones included`() = runTest {
        val state = viewModel().ready()

        assertThat(state.day).isNull()
        assertThat(state.editsEveryEvening).isTrue()
        assertThat(state.rows.map { it.activity }).isEqualTo(richActivities())
        assertThat(included(state)).containsExactly(1L, 2L, 3L, 8L, 9L, 10L).inOrder()
        assertThat(state.leadMaxMinutes).isEqualTo(240)
        assertThat(state.durationMaxMinutes).isEqualTo(60)
        // The EVENING_ROUTINE habit is on with its reminder by default.
        assertThat(state.reminderOn).isTrue()
    }

    @Test
    fun `including and leaving out edit the every-evening list`() = runTest {
        val viewModel = viewModel()
        viewModel.ready()

        viewModel.setIncluded(4, true)
        viewModel.setIncluded(1, false)

        assertThat(nightSteps.included).containsExactly(4L to true, 1L to false).inOrder()
        assertThat(included(viewModel.ready())).containsExactly(2L, 3L, 4L, 8L, 9L, 10L).inOrder()
        assertThat(schedule.dayRoutines).isEmpty()
    }

    @Test
    fun `lead and length steppers save the activity's timing, clamped to their ranges`() = runTest {
        val viewModel = viewModel()
        viewModel.ready()

        viewModel.setLead(3, 35)
        viewModel.setDuration(3, 0)
        viewModel.setLead(3, 500)

        assertThat(nightSteps.timings).containsExactly(
            Triple(3L, Duration.ofMinutes(35), Duration.ofMinutes(10)),
            Triple(3L, Duration.ofMinutes(35), Duration.ZERO),
            Triple(3L, Duration.ofMinutes(240), Duration.ZERO),
        ).inOrder()
    }

    @Test
    fun `quick stepper taps build on each other before storage catches up`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slow = object : NightStepsRepository by nightSteps {
            override suspend fun setTiming(activityId: Long, lead: Duration, duration: Duration) {
                gate.await()
                nightSteps.setTiming(activityId, lead, duration)
            }
        }
        val viewModel = viewModel(repository = slow)
        viewModel.ready()

        viewModel.setLead(3, 35)
        viewModel.setLead(3, 40)
        assertThat(viewModel.ready().rows.first { it.activity.id == 3L }.activity.lead).isEqualTo(Duration.ofMinutes(40))

        gate.complete(Unit)
        assertThat(nightSteps.timings.map { it.second }).containsExactly(Duration.ofMinutes(35), Duration.ofMinutes(40)).inOrder()
        assertThat(viewModel.ready().rows.first { it.activity.id == 3L }.activity.lead).isEqualTo(Duration.ofMinutes(40))
    }

    @Test
    fun `a custom activity is added at the custom placement`() = runTest {
        val viewModel = viewModel()
        viewModel.ready()

        viewModel.addCustom("  Puzzle  ", "🧩")
        viewModel.addCustom("   ", "🧩")
        viewModel.addCustom("Tea", " ")

        val steps = SleepConfig.DEFAULT.steps
        assertThat(nightSteps.customsAdded.map { Triple(it.label, it.emoji, it.lead to it.duration) }).containsExactly(
            Triple("Puzzle", "🧩", steps.customLead to steps.customDuration),
            Triple("Tea", RoutineEditorViewModel.DEFAULT_CUSTOM_EMOJI, steps.customLead to steps.customDuration),
        ).inOrder()
    }

    @Test
    fun `only custom activities can be removed`() = runTest {
        val viewModel = viewModel()
        viewModel.ready()

        viewModel.remove(1)
        viewModel.remove(10)

        assertThat(nightSteps.deletedCustoms).containsExactly(10L)
        assertThat(viewModel.ready().rows.map { it.activity.id }).doesNotContain(10L)
    }

    @Test
    fun `the reminder switch drives the EVENING_ROUTINE habit and replans`() = runTest {
        val viewModel = viewModel()
        viewModel.ready()

        viewModel.setReminder(false)
        val off = habits.settingsState.value.first { it.type == HabitType.EVENING_ROUTINE }
        assertThat(off.enabled).isTrue()
        assertThat(off.reminderEnabled).isFalse()
        assertThat(viewModel.ready().reminderOn).isFalse()

        habits.setEnabled(HabitType.EVENING_ROUTINE, false)
        viewModel.setReminder(true)
        val on = habits.settingsState.value.first { it.type == HabitType.EVENING_ROUTINE }
        assertThat(on.enabled).isTrue()
        assertThat(on.reminderEnabled).isTrue()
        assertThat(planner.replans).isEqualTo(2)
    }

    // ---------------------------------------------------------------- One evening

    @Test
    fun `a day without its own list follows every evening`() = runTest {
        val viewModel = viewModel(day = DayOfWeek.FRIDAY.value)
        val state = viewModel.ready()

        assertThat(state.day).isEqualTo(DayOfWeek.FRIDAY)
        assertThat(state.editsEveryEvening).isTrue()
        assertThat(included(state)).containsExactly(1L, 2L, 3L, 8L, 9L, 10L).inOrder()

        // Same list: toggles change the every-evening list.
        viewModel.setIncluded(5, true)
        assertThat(nightSteps.included).containsExactly(5L to true)
    }

    @Test
    fun `switching to an own list pre-fills it with the current routine`() = runTest {
        val viewModel = viewModel(day = DayOfWeek.FRIDAY.value)
        viewModel.ready()

        viewModel.setSameAsEveryEvening(false)

        assertThat(schedule.dayRoutines).containsExactly(DayOfWeek.FRIDAY to listOf(1L, 2L, 3L, 8L, 9L, 10L))
        val state = viewModel.ready()
        assertThat(state.editsEveryEvening).isFalse()
        assertThat(state.ownList).containsExactly(1L, 2L, 3L, 8L, 9L, 10L).inOrder()
    }

    @Test
    fun `an own list changes only that day, and can go back to every evening`() = runTest {
        val friday = WeekPlan.DEFAULT.day(DayOfWeek.FRIDAY)
        schedule.weekState.value = WeekPlan.DEFAULT.with(friday.copy(evening = friday.evening.copy(routine = listOf(3L, 1L))))
        val viewModel = viewModel(day = DayOfWeek.FRIDAY.value)
        assertThat(included(viewModel.ready())).containsExactly(1L, 3L)

        viewModel.setIncluded(7, true)
        viewModel.setIncluded(3, false)
        viewModel.addCustom("Puzzle", "🧩")

        // The new custom step is on Friday's list only, not on every evening's.
        assertThat(nightSteps.included).containsExactly(11L to false)
        assertThat(nightSteps.activitiesState.value.first { it.id == 11L }.selected).isFalse()
        assertThat(schedule.dayRoutines).containsExactly(
            DayOfWeek.FRIDAY to listOf(3L, 1L, 7L),
            DayOfWeek.FRIDAY to listOf(1L, 7L),
            DayOfWeek.FRIDAY to listOf(1L, 7L, 11L),
        ).inOrder()

        viewModel.setSameAsEveryEvening(true)
        assertThat(schedule.dayRoutines.last()).isEqualTo(DayOfWeek.FRIDAY to null)
        assertThat(viewModel.ready().editsEveryEvening).isTrue()
    }

    @Test
    fun `an unknown day in the route edits every evening`() = runTest {
        assertThat(viewModel(day = 9).ready().day).isNull()
    }
}
