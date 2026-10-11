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
import com.sleepsaver.core.domain.nightsteps.NightStep
import com.sleepsaver.core.domain.nightsteps.NightStepsSession
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.domain.model.ScheduledHabit
import com.sleepsaver.core.notifications.testing.FakeHabitsRepository
import com.sleepsaver.core.notifications.testing.FakeInsightsRepository
import com.sleepsaver.core.notifications.testing.FakeNightController
import com.sleepsaver.core.notifications.testing.FakeNightStepsRepository
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
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ReminderPlannerImplTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val alarms = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val notifications = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)

    private val tomorrow = TODAY.plusDays(1)
    private val insights = FakeInsightsRepository()
    private val time = FakeTimeProvider(at(TODAY, "09:00"))

    /** Other days' habits come from here (by schedule date) instead of HabitScheduler. */
    private val habitsByDate = mutableMapOf<LocalDate, List<ScheduledHabit>>()
    private val scheduleHabits: (EnergySchedule, List<HabitSettings>) -> List<ScheduledHabit> =
        { schedule, _ -> habitsByDate[schedule.date].orEmpty() }

    private val habits = FakeHabitsRepository()
    private val nightSteps = FakeNightStepsRepository()
    private val nightController = FakeNightController()
    private val planner = ReminderPlannerImpl(
        context, insights, habits, nightSteps, nightController, time, NotificationPoster(context), scheduleHabits,
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
        ShadowAlarmManager.setCanScheduleExactAlarms(true) // Robolectric's default is false
        insights.schedules[tomorrow] = schedule(tomorrow)
        habitsByDate[tomorrow] = listOf(habit(HabitType.MORNING_LIGHT, at(tomorrow, "07:20")))
        insights.state.value = InsightsState.Ready(insights(time.now, todayHabits))
    }

    @Test
    fun `arms one exact wake-up alarm for the earliest pending reminder`() = runTest {
        planner.replan()

        val alarm = alarms.scheduledAlarms.single()
        assertThat(alarm.triggerAtMs).isEqualTo(at(TODAY, "12:45").toEpochMilli())
        assertThat(alarm.type).isEqualTo(AlarmManager.RTC_WAKEUP)
        assertThat(alarm.isAllowWhileIdle).isTrue()
        assertThat(alarm.windowLengthMs).isEqualTo(ShadowAlarmManager.WINDOW_EXACT)
    }

    @Test
    fun `without exact-alarm access the reminder alarm is inexact`() = runTest {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        planner.replan()

        val alarm = alarms.scheduledAlarms.single()
        assertThat(alarm.triggerAtMs).isEqualTo(at(TODAY, "12:45").toEpochMilli())
        assertThat(alarm.isAllowWhileIdle).isTrue()
        assertThat(alarm.windowLengthMs).isEqualTo(ShadowAlarmManager.WINDOW_HEURISTIC)
    }

    @Test
    fun `after Drop anchor wind-down, bedtime and nap reminders stay quiet but count as posted`() = runTest {
        planner.replan()
        nightController.anchor(anchorId = 7, at = at(TODAY, "20:55"))
        time.now = at(TODAY, "21:00:20")

        planner.onAlarm()

        assertThat(notifications.allNotifications).isEmpty()
        assertThat(ReminderStore(context).lastPosted.keys).containsAtLeast(HabitType.DIM_LIGHTS, HabitType.EVENING_ROUTINE)
        // The bedtime reminder that follows is quiet too, and a replan doesn't bring the evening ones back.
        time.now = at(TODAY, "22:15:10")
        planner.onAlarm()
        planner.replan()
        assertThat(notifications.allNotifications).isEmpty()
        assertThat(ReminderStore(context).lastPosted.keys).contains(HabitType.BEDTIME)
    }

    @Test
    fun `reminders outside the evening still post while anchored`() = runTest {
        nightController.anchor(anchorId = 7, at = at(TODAY, "00:30"))
        time.now = at(TODAY, "12:44")
        planner.replan()
        time.now = at(TODAY, "12:45")

        planner.onAlarm()

        assertThat(notifications.getNotification(NotificationIds.habit(HabitType.CAFFEINE_CUTOFF))).isNotNull()
    }

    @Test
    fun `after an early Drop anchor tonight's cutoffs stay quiet, another evening's still post`() = runTest {
        // Bedtime 00:30: the exercise cutoff (2 h before it) falls at 22:30, after dropping anchor at 21:45.
        insights.state.value = InsightsState.Ready(insights(time.now, todayHabits + habit(HabitType.EXERCISE_CUTOFF, at(TODAY, "22:30"))))
        habitsByDate[tomorrow] = listOf(habit(HabitType.CAFFEINE_CUTOFF, at(tomorrow, "12:45")))
        planner.replan()
        nightController.anchor(anchorId = 7, at = at(TODAY, "21:45"))
        time.now = at(TODAY, "22:30:10")

        planner.onAlarm()

        assertThat(notifications.getNotification(NotificationIds.habit(HabitType.EXERCISE_CUTOFF))).isNull()
        assertThat(ReminderStore(context).lastPosted.keys).contains(HabitType.EXERCISE_CUTOFF)

        // Tomorrow's caffeine cutoff leads up to tomorrow's evening: it posts even while the night state holds.
        time.now = at(tomorrow, "12:45:10")
        planner.onAlarm()
        assertThat(notifications.getNotification(NotificationIds.habit(HabitType.CAFFEINE_CUTOFF))).isNotNull()
    }

    @Test
    fun `the Night steps reminder lists tonight's unchecked steps`() = runTest {
        val bedtime = at(TODAY, "22:45")
        nightSteps.tonightState.value = NightStepsSession(
            nightOf = TODAY,
            windowStart = at(TODAY, "21:00"),
            bedtime = bedtime,
            steps = listOf(
                NightStep(RoutineActivity(1, "Screens down", "S", isCustom = false, selected = true), bedtime, bedtime, doneAt = at(TODAY, "21:01")),
                NightStep(RoutineActivity(2, "Slow tide breathing", "T", isCustom = false, selected = true), bedtime, bedtime, doneAt = null),
                NightStep(RoutineActivity(3, "Tomorrow list", "L", isCustom = false, selected = true), bedtime, bedtime, doneAt = null),
            ),
            enabled = true,
        )
        habits.activities.value = listOf(RoutineActivity(9, "Reading", "R", isCustom = false, selected = true))
        planner.replan()
        time.now = at(TODAY, "21:00:20")

        planner.onAlarm()

        val notification = notifications.getNotification(NotificationIds.habit(HabitType.EVENING_ROUTINE))
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("Night steps start now")
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo("Tonight: T Slow tide breathing, L Tomorrow list")
    }

    @Test
    fun `without steps to list the Night steps reminder says it plainly`() = runTest {
        planner.replan()
        time.now = at(TODAY, "21:00:20")

        planner.onAlarm()

        val text = notifications.getNotification(NotificationIds.habit(HabitType.EVENING_ROUTINE))
            .extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertThat(text).isEqualTo("Time to start easing toward bed.")
    }

    @Test
    fun `deep links per category`() = runTest {
        planner.replan()
        time.now = at(TODAY, "21:00:20")
        planner.onAlarm()
        time.now = at(TODAY, "22:15:20")
        planner.onAlarm()

        fun linkOf(type: HabitType) = shadowOf(notifications.getNotification(NotificationIds.habit(type)).contentIntent).savedIntent.dataString
        assertThat(linkOf(HabitType.DIM_LIGHTS)).isEqualTo("sleepsaver://nightsteps")
        assertThat(linkOf(HabitType.EVENING_ROUTINE)).isEqualTo("sleepsaver://nightsteps")
        assertThat(linkOf(HabitType.BEDTIME)).isEqualTo("sleepsaver://tonight")
        HabitType.entries.forEach { type ->
            val expected = when (type) {
                HabitType.DIM_LIGHTS, HabitType.EVENING_ROUTINE -> "sleepsaver://nightsteps"
                HabitType.BEDTIME -> "sleepsaver://tonight"
                else -> "sleepsaver://energy"
            }
            assertThat(DeepLinks.forHabit(type).toString()).isEqualTo(expected)
        }
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

    @Test
    fun `cancelAll removes posted reminders and forgets what was posted`() = runTest {
        time.now = at(TODAY, "12:44")
        planner.replan()
        time.now = at(TODAY, "12:45")
        planner.onAlarm()

        planner.cancelAll()

        assertThat(notifications.allNotifications).isEmpty()
        assertThat(ReminderStore(context).postedThrough).isNull()
        assertThat(ReminderStore(context).lastPosted).isEmpty()
    }

    @Test
    fun `a bedtime after midnight is still reminded once the date has rolled over`() = runTest {
        // Late sleeper: TODAY's schedule puts the sleep window at 00:30, but from midnight on insights.today is tomorrow.
        val dayAfter = tomorrow.plusDays(1)
        insights.schedules[TODAY] = schedule(TODAY)
        habitsByDate[TODAY] = listOf(
            habit(HabitType.DIM_LIGHTS, at(TODAY, "23:00"), windowEnd = at(TODAY, "23:59")),
            habit(HabitType.BEDTIME, at(tomorrow, "00:30"), windowEnd = at(tomorrow, "01:30")),
        )
        insights.schedules[dayAfter] = schedule(dayAfter)
        habitsByDate[dayAfter] = listOf(habit(HabitType.MORNING_LIGHT, at(dayAfter, "09:15")))
        time.now = at(tomorrow, "00:01")
        val tomorrowsOwn = listOf(habit(HabitType.MORNING_LIGHT, at(tomorrow, "09:15")), habit(HabitType.BEDTIME, at(dayAfter, "00:30")))
        insights.state.value = InsightsState.Ready(insights(time.now, tomorrowsOwn, today = tomorrow))

        planner.replan()

        assertThat(nextAlarmAt()).isEqualTo(at(tomorrow, "00:30"))
    }

    @Test
    fun `a reminder nudged a few minutes later after it was posted isn't posted again`() = runTest {
        time.now = at(TODAY, "07:00")
        insights.state.value = InsightsState.Ready(insights(time.now, todayHabits))
        planner.replan()
        time.now = at(TODAY, "07:15:10")
        planner.onAlarm()
        assertThat(notifications.allNotifications).hasSize(1)

        // Last night syncs at 07:16 and moves typical wake (and every habit) 6 minutes later.
        time.now = at(TODAY, "07:16")
        val moved = todayHabits.map { it.copy(time = it.time.plusSeconds(6 * 60)) }
        insights.state.value = InsightsState.Ready(insights(time.now, moved))
        planner.replan()

        assertThat(nextAlarmAt()).isEqualTo(at(TODAY, "12:51"))
        time.now = at(TODAY, "07:21:30")
        planner.onAlarm()
        assertThat(notifications.allNotifications).hasSize(1)
    }

    @Test
    fun `today's reminders are still planned when another day's schedule can't be read`() = runTest {
        insights.failingSchedules += tomorrow

        planner.replan()

        assertThat(nextAlarmAt()).isEqualTo(at(TODAY, "12:45"))
    }

    @Test
    fun `without tonight's steps the Night steps reminder names the chosen activities`() = runTest {
        habits.activities.value = listOf(
            RoutineActivity(1, "Reading", "R", isCustom = false, selected = true),
            RoutineActivity(2, "Gaming", "G", isCustom = false, selected = false),
        )
        planner.replan()
        time.now = at(TODAY, "21:00:20")

        planner.onAlarm()

        val text = notifications.getNotification(NotificationIds.habit(HabitType.EVENING_ROUTINE))
            .extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertThat(text).isEqualTo("Tonight: R Reading")
    }

    @Test
    fun `a night owl after midnight still gets today's reminders`() = runTest {
        // 00:30, before yesterday's late melatonin window ends: the insights still carry yesterday's evening schedule.
        time.now = at(TODAY, "00:30")
        insights.schedules[TODAY] = schedule(TODAY)
        habitsByDate[TODAY] = todayHabits
        val lateBedtime = habit(HabitType.BEDTIME, at(TODAY, "00:45"), windowEnd = at(TODAY, "01:45"))
        insights.state.value = InsightsState.Ready(
            insights(time.now, listOf(lateBedtime)).copy(schedule = schedule(TODAY.minusDays(1))),
        )

        planner.replan()
        assertThat(nextAlarmAt()).isEqualTo(at(TODAY, "00:45"))

        time.now = at(TODAY, "00:45:10")
        planner.onAlarm()
        assertThat(nextAlarmAt()).isEqualTo(at(TODAY, "07:15"))
    }

    private fun nextAlarmAt(): Instant? = alarms.peekNextScheduledAlarm()?.let { Instant.ofEpochMilli(it.triggerAtMs) }
}
