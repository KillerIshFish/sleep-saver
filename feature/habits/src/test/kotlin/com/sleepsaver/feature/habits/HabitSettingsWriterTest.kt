package com.sleepsaver.feature.habits

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.HabitType
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class HabitSettingsWriterTest {

    private val slow = SlowOffsetHabitsRepository()
    private val planner = RecordingReminderPlanner()

    private fun caffeineOffset(): Duration = slow.delegate.settingsState.value.first { it.type == HabitType.CAFFEINE_CUTOFF }.offset

    @Test
    fun `a save outlives the screen that started it`() = runTest {
        val writer = writer(slow, planner)
        val screen = launch(UnconfinedTestDispatcher(testScheduler)) {
            writer.setOffset(HabitType.CAFFEINE_CUTOFF, Duration.ofHours(-8))
        }

        screen.cancel()
        slow.gate.complete(Unit)
        advanceUntilIdle()

        assertThat(caffeineOffset()).isEqualTo(Duration.ofHours(-8))
        assertThat(planner.replans).isEqualTo(1)
    }

    @Test
    fun `writes land in the order they were made`() = runTest {
        val writer = writer(slow, planner)
        val eager = UnconfinedTestDispatcher(testScheduler)
        launch(eager) { writer.setOffset(HabitType.CAFFEINE_CUTOFF, Duration.ofHours(-9)) }
        launch(eager) { writer.setOffset(HabitType.CAFFEINE_CUTOFF, Duration.ofHours(-8)) }
        launch(eager) { writer.setReminder(HabitType.CAFFEINE_CUTOFF, false) }

        slow.gate.complete(Unit)
        advanceUntilIdle()

        assertThat(caffeineOffset()).isEqualTo(Duration.ofHours(-8))
        assertThat(slow.delegate.settingsState.value.first { it.type == HabitType.CAFFEINE_CUTOFF }.reminderEnabled).isFalse()
        assertThat(planner.replans).isEqualTo(3)
    }

    @Test
    fun `routine activity changes save without replanning`() = runTest {
        val writer = writer(slow, planner)

        assertThat(writer.setActivitySelected(2, true)).isTrue()
        assertThat(writer.addCustomActivity("Tea", "🍵")).isTrue()
        assertThat(writer.deleteActivity(1)).isTrue()

        val activities = slow.delegate.activitiesState.value
        assertThat(activities.first { it.id == 2L }.selected).isTrue()
        assertThat(activities.map { it.label }).contains("Tea")
        assertThat(activities.map { it.id }).doesNotContain(1L)
        assertThat(planner.replans).isEqualTo(0)
    }

    @Test
    fun `a failed write reports false and leaves reminders alone`() = runTest {
        val writer = writer(FailingHabitsRepository(), planner)

        assertThat(writer.setEnabled(HabitType.NAP_WINDOW, true)).isFalse()
        assertThat(writer.addCustomActivity("Tea", "🍵")).isFalse()
        assertThat(planner.replans).isEqualTo(0)
    }
}
