package com.sleepsaver.feature.winddown.library

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.nightsteps.StepCategory
import com.sleepsaver.core.domain.nightsteps.StepSuggestion
import com.sleepsaver.core.domain.nightsteps.SuggestionReason
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.testing.FakeNightStepsRepository
import com.sleepsaver.core.testing.FakeScheduleRepository
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.feature.winddown.writer
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

@RunWith(AndroidJUnit4::class)
class StepLibraryViewModelTest {

    private val nightSteps = FakeNightStepsRepository(activities = PlanSamples.activities(), suggestions = PlanSamples.suggestions)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val schedule = FakeScheduleRepository(week = WeekPlan.DEFAULT)

    /** [day] as StepLibraryRoute carries it: DayOfWeek.value, 0 = every evening. */
    private fun TestScope.viewModel(day: Int = 0): StepLibraryViewModel {
        val viewModel = StepLibraryViewModel(SavedStateHandle(mapOf("day" to day)), nightSteps, schedule, writer())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel
    }

    private suspend fun StepLibraryViewModel.ready(): StepLibraryUiState.Ready =
        uiState.first { it is StepLibraryUiState.Ready } as StepLibraryUiState.Ready

    @Test
    fun `groups every technique but the screen-based ones`() = runTest {
        val state = viewModel().ready()

        assertThat(state.sections.map { it.category }).isEqualTo(StepCategory.entries)
        val listed = state.sections.flatMap { it.techniques }
        assertThat(listed).containsExactlyElementsIn(Technique.entries.filter { !it.screenBased })
        assertThat(listed).containsNoneOf(Technique.TV, Technique.GAMING)
        assertThat(state.sections.first { it.category == StepCategory.MIND }.techniques)
            .containsExactly(Technique.TOMORROW_LIST, Technique.BODY_SCAN, Technique.JOURNALING)
            .inOrder()
    }

    @Test
    fun `suggestions come first and the every-evening list shows as in your steps`() = runTest {
        val state = viewModel().ready()

        assertThat(state.suggestions).isEqualTo(PlanSamples.suggestions)
        // PlanSamples selects Screens down, Tomorrow list and Slow tide.
        assertThat(state.inSteps).containsExactly(Technique.SCREENS_DOWN, Technique.TOMORROW_LIST, Technique.SLOW_TIDE)
    }

    @Test
    fun `a screen-based suggestion is never shown`() = runTest {
        nightSteps.suggestionsState.value = listOf(StepSuggestion(Technique.TV, SuggestionReason.STARTER)) + PlanSamples.suggestions

        assertThat(viewModel().ready().suggestions).isEqualTo(PlanSamples.suggestions)
    }

    @Test
    fun `Add puts the technique in the steps with the screen's label`() = runTest {
        val viewModel = viewModel()
        viewModel.ready()

        viewModel.add(Technique.BODY_SCAN, "Body scan")

        assertThat(nightSteps.techniquesAdded).containsExactly(Technique.BODY_SCAN to "Body scan")
        assertThat(viewModel.ready().inSteps).contains(Technique.BODY_SCAN)
        assertThat(schedule.dayRoutines).isEmpty()
    }

    @Test
    fun `from a day with its own list, Add puts the step on that list only`() = runTest {
        val friday = WeekPlan.DEFAULT.day(DayOfWeek.FRIDAY)
        val slowTide = nightSteps.activitiesState.value.first { it.technique == Technique.SLOW_TIDE }.id
        schedule.weekState.value = WeekPlan.DEFAULT.with(friday.copy(evening = friday.evening.copy(routine = listOf(slowTide))))
        val viewModel = viewModel(day = DayOfWeek.FRIDAY.value)
        assertThat(viewModel.ready().inSteps).containsExactly(Technique.SLOW_TIDE)

        viewModel.add(Technique.BODY_SCAN, "Body scan")

        val bodyScan = nightSteps.activitiesState.value.first { it.technique == Technique.BODY_SCAN }
        assertThat(bodyScan.selected).isFalse()
        assertThat(schedule.dayRoutines).containsExactly(DayOfWeek.FRIDAY to listOf(slowTide, bodyScan.id))
        assertThat(viewModel.ready().inSteps).containsExactly(Technique.SLOW_TIDE, Technique.BODY_SCAN)
    }

    @Test
    fun `from a day with its own list, a step already on every evening stays there`() = runTest {
        val friday = WeekPlan.DEFAULT.day(DayOfWeek.FRIDAY)
        schedule.weekState.value = WeekPlan.DEFAULT.with(friday.copy(evening = friday.evening.copy(routine = emptyList())))
        val viewModel = viewModel(day = DayOfWeek.FRIDAY.value)

        viewModel.add(Technique.TOMORROW_LIST, "Tomorrow list")

        val tomorrowList = nightSteps.activitiesState.value.first { it.technique == Technique.TOMORROW_LIST }
        assertThat(tomorrowList.selected).isTrue()
        assertThat(nightSteps.included).isEmpty()
        assertThat(schedule.dayRoutines).containsExactly(DayOfWeek.FRIDAY to listOf(tomorrowList.id))
    }

    @Test
    fun `from a day that follows every evening, Add works on every evening`() = runTest {
        val viewModel = viewModel(day = DayOfWeek.FRIDAY.value)

        viewModel.add(Technique.BODY_SCAN, "Body scan")

        assertThat(nightSteps.activitiesState.value.first { it.technique == Technique.BODY_SCAN }.selected).isTrue()
        assertThat(schedule.dayRoutines).isEmpty()
    }
}
