package com.sleepsaver.core.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.ScheduledHabit
import com.sleepsaver.core.notifications.testing.FakeHabitsRepository
import com.sleepsaver.core.notifications.testing.FakeInsightsRepository
import com.sleepsaver.core.notifications.testing.FakeTimeProvider
import com.sleepsaver.core.notifications.testing.TestInsights.TODAY
import com.sleepsaver.core.notifications.testing.TestInsights.at
import com.sleepsaver.core.notifications.testing.TestInsights.habit
import com.sleepsaver.core.notifications.testing.TestInsights.insights
import com.sleepsaver.core.notifications.testing.TestInsights.schedule
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ReminderPlannerImplTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val alarms = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val notifications = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)

    private val tomorrow = TODAY.plusDays(1)
    private val insights = FakeInsightsRepository()
    private val time = FakeTimeProvider(at(TODAY, "09:00"))

    /** Tomorrow's habits come from here instead of HabitScheduler. */
    private var tomorrowHabits: List<ScheduledHabit> = emptyList()
    private val scheduleHabits: (EnergySchedule, List<HabitSettings>) -> List<ScheduledHabit> = { _, _ -> tomorrowHabits }

    private val planner = ReminderPlannerImpl(
        context, insights, FakeHabitsRepository(), time, NotificationPoster(context), scheduleHabits,
    )

    private val todayHabits = listOf(
        habit(HabitType.MORNING_LIGHT, at(TODAY, "07:15")),
        habit(HabitType.CAFFEINE_CUTOFF, at(TODAY, "12:45")),
        habit(HabitType.DIM_LIGHTS, at(TODAY, "21:00"), windowEnd = at(TODAY, "22:00")),
        habit(HabitType.EVENING_ROUTINE, at(TODAY, "21:00"), windowEnd = at(TODAY, "22:00")),
        habit(HabitType.BEDTIME, at(TODAY, "22:15"), windowEnd = at(TODAY, "23:15")),
    )

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        insights.schedules[tomorrow] = schedule(tomorrow)
        tomorrowHabits = listOf(habit(HabitType.MORNING_LIGHT, at(tomorrow, "07:20")))
        insights.state.value = InsightsState.Ready(insights(time.now, todayHabits))
    }

    @Test
    fun `arms one inexact wake-up alarm for the earliest pending reminder`() = runTest {
        planner.replan()

        val alarm = alarms.scheduledAlarms.single()
        assertThat(alarm.triggerAtMs).isEqualTo(at(TODAY, "12:45").toEpochMilli())
        assertThat(alarm.type).isEqualTo(AlarmManager.RTC_WAKEUP)
        assertThat(alarm.isAllowWhileIdle).isTrue()
    }

    @Test
    fun `replanning replaces the alarm instead of adding another`() = runTest {
        planner.replan()
        time.now = at(TODAY, "13:00")
        planner.replan()

        assertThat(alarms.scheduledAlarms.map { it.triggerAtMs }).containsExactly(at(TODAY, "21:00").toEpochMilli())
    }

    @Test
    fun `after today's last reminder the alarm moves to tomorrow's first`() = runTest {
        time.now = at(TODAY, "22:40")

        planner.replan()

        assertThat(nextAlarmAt()).isEqualTo(at(tomorrow, "07:20"))
    }

    @Test
    fun `nothing pending cancels the alarm`() = runTest {
        planner.replan()
        insights.state.value = InsightsState.NoNights

        planner.replan()

        assertThat(alarms.scheduledAlarms).isEmpty()
    }

    @Test
    fun `while insights are loading the armed alarm is left alone`() = runTest {
        planner.replan()
        insights.state.value = InsightsState.Loading

        planner.replan()

        assertThat(nextAlarmAt()).isEqualTo(at(TODAY, "12:45"))
    }

    @Test
    fun `firing posts every reminder due together and arms the next one`() = runTest {
        planner.replan()
        time.now = at(TODAY, "21:00:20")

        planner.onAlarm()

        val posted = notifications.allNotifications
        assertThat(posted.map { it.channelId }).containsExactly(NotificationChannels.WIND_DOWN, NotificationChannels.WIND_DOWN)
        assertThat(notifications.getNotification(NotificationIds.habit(HabitType.DIM_LIGHTS))).isNotNull()
        assertThat(notifications.getNotification(NotificationIds.habit(HabitType.EVENING_ROUTINE))).isNotNull()
        assertThat(nextAlarmAt()).isEqualTo(at(TODAY, "22:15"))
    }

    @Test
    fun `a replan right after firing doesn't post or arm the same reminders again`() = runTest {
        time.now = at(TODAY, "20:59")
        planner.replan()
        time.now = at(TODAY, "21:00")
        planner.onAlarm()

        time.now = at(TODAY, "21:00:30")
        planner.replan()

        assertThat(nextAlarmAt()).isEqualTo(at(TODAY, "22:15"))
        assertThat(notifications.allNotifications).hasSize(2)
    }

    @Test
    fun `reminder copy and deep link`() = runTest {
        time.now = at(TODAY, "12:44")
        planner.replan()
        time.now = at(TODAY, "12:45")

        planner.onAlarm()

        val notification = notifications.getNotification(NotificationIds.habit(HabitType.CAFFEINE_CUTOFF))
        assertThat(notification.channelId).isEqualTo(NotificationChannels.CUTOFFS)
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            .isEqualTo(context.getString(R.string.reminder_caffeine_title))
        val launched = shadowOf(notification.contentIntent).savedIntent
        assertThat(launched.dataString).isEqualTo("sleepsaver://energy")
        assertThat(launched.`package`).isEqualTo(context.packageName)
    }

    @Test
    fun `nothing is posted when notifications are off, but reminders stay armed`() = runTest {
        time.now = at(TODAY, "12:40")
        planner.replan()
        notifications.setNotificationsEnabled(false)
        time.now = at(TODAY, "12:45")

        planner.onAlarm()

        assertThat(notifications.allNotifications).isEmpty()
        assertThat(nextAlarmAt()).isEqualTo(at(TODAY, "21:00"))
    }

    @Test
    fun `cancelAll cancels the alarm`() = runTest {
        planner.replan()

        planner.cancelAll()

        assertThat(alarms.scheduledAlarms).isEmpty()
    }

    private fun nextAlarmAt(): Instant? = alarms.peekNextScheduledAlarm()?.let { Instant.ofEpochMilli(it.triggerAtMs) }
}
