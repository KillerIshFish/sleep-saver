@file:OptIn(ExperimentalCoroutinesApi::class)

package com.sleepsaver.core.notifications

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.notifications.testing.CountingReminderPlanner
import com.sleepsaver.core.notifications.testing.FakeHabitsRepository
import com.sleepsaver.core.notifications.testing.FakeInsightsRepository
import com.sleepsaver.core.notifications.testing.TestInsights.TODAY
import com.sleepsaver.core.notifications.testing.TestInsights.at
import com.sleepsaver.core.notifications.testing.TestInsights.habit
import com.sleepsaver.core.notifications.testing.TestInsights.insights
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class NotificationsBootstrapTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val insights = FakeInsightsRepository()
    private val habits = FakeHabitsRepository()
    private val planner = CountingReminderPlanner()

    private val todayHabits = listOf(
        habit(HabitType.CAFFEINE_CUTOFF, at(TODAY, "12:45")),
        habit(HabitType.BEDTIME, at(TODAY, "22:15")),
    )

    private fun TestScope.start() =
        NotificationsBootstrap(context, insights, habits, planner, backgroundScope).start()

    /** Past the debounce. advanceUntilIdle() would ignore the bootstrap's background collector. */
    private fun TestScope.settle() {
        advanceTimeBy(5_000)
        runCurrent()
    }

    @Test
    fun `creates every channel, with sync at low importance`() = runTest {
        start()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertThat(manager.notificationChannels.map { it.id }).containsExactly(
            "review", "morning_light", "cutoffs", "wind_down", "bedtime", "naps", "sync",
        )
        assertThat(manager.getNotificationChannel(NotificationChannels.SYNC).importance)
            .isEqualTo(NotificationManager.IMPORTANCE_LOW)
    }

    @Test
    fun `waits for insights, then replans once after the burst settles`() = runTest {
        start()
        settle()
        assertThat(planner.replans).isEqualTo(0)

        insights.state.value = InsightsState.Ready(insights(at(TODAY, "09:00"), todayHabits))
        insights.state.value = InsightsState.Ready(insights(at(TODAY, "09:00:01"), todayHabits.reversed()))
        settle()

        assertThat(planner.replans).isEqualTo(1)
    }

    @Test
    fun `the minute refresh doesn't replan, a habit change does`() = runTest {
        insights.state.value = InsightsState.Ready(insights(at(TODAY, "09:00"), todayHabits))
        start()
        settle()

        insights.state.value = InsightsState.Ready(insights(at(TODAY, "09:01"), todayHabits))
        settle()
        assertThat(planner.replans).isEqualTo(1)

        habits.setReminder(HabitType.BEDTIME, enabled = false)
        settle()
        assertThat(planner.replans).isEqualTo(2)

        val moved = todayHabits.map { it.copy(time = it.time.plus(Duration.ofMinutes(10))) }
        insights.state.value = InsightsState.Ready(insights(at(TODAY, "09:02"), moved))
        settle()
        assertThat(planner.replans).isEqualTo(3)
    }

    @Test
    fun `losing every night replans so the alarm is cancelled`() = runTest {
        insights.state.value = InsightsState.Ready(insights(at(TODAY, "09:00"), todayHabits))
        start()
        settle()

        insights.state.value = InsightsState.NoNights
        settle()

        assertThat(planner.replans).isEqualTo(2)
    }
}
