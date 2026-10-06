package com.sleepsaver.core.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.content.getSystemService
import com.sleepsaver.core.data.hooks.ReminderPlanner
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.domain.habits.HabitScheduler
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.ScheduledHabit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Habit reminders on one inexact alarm (docs/PLAN.md §3.8): [replan] arms
 * `AlarmManager.setAndAllowWhileIdle(RTC_WAKEUP)` for the earliest pending reminder of yesterday's, today's and
 * tomorrow's schedules (yesterday's still owns a late bedtime that falls after midnight);
 * when it fires, [onAlarm] posts every reminder due by then (±2 min) and arms the next one.
 * No exact-alarm permission, so Doze may deliver a reminder a few minutes late.
 */
@Singleton
class ReminderPlannerImpl internal constructor(
    private val context: Context,
    private val insights: InsightsRepository,
    private val habits: HabitsRepository,
    private val time: TimeProvider,
    private val poster: NotificationPoster,
    private val scheduleHabits: (EnergySchedule, List<HabitSettings>) -> List<ScheduledHabit>,
) : ReminderPlanner {

    @Inject constructor(
        @ApplicationContext context: Context,
        insights: InsightsRepository,
        habits: HabitsRepository,
        time: TimeProvider,
        poster: NotificationPoster,
    ) : this(context, insights, habits, time, poster, HabitScheduler()::schedule)

    private val planning = ReminderPlanning()
    private val store = ReminderStore(context)
    private val alarmManager = checkNotNull(context.getSystemService<AlarmManager>())
    private val mutex = Mutex()

    override suspend fun replan() = mutex.withLock { plan(firing = false) }

    /** The alarm went off: post what's due, then arm the next one. */
    suspend fun onAlarm() = mutex.withLock { plan(firing = true) }

    override fun cancelAll() {
        alarmManager.cancel(alarmIntent())
        store.reset()
        HabitType.entries.forEach { poster.cancel(NotificationIds.habit(it)) }
    }

    private suspend fun plan(firing: Boolean) {
        val upcoming = upcomingHabits()
        val now = time.now()
        if (upcoming == null) {
            // Insights are still loading. Keep the armed alarm; if it just fired, look again shortly.
            if (firing) arm(now.plus(RETRY_WHILE_LOADING))
            return
        }
        var postedThrough = store.postedThrough ?: now.also { store.postedThrough = it }
        var lastPosted = store.lastPosted
        if (firing) {
            val due = planning.due(upcoming, now, postedThrough, lastPosted)
            val routine = if (due.any { it.type == HabitType.EVENING_ROUTINE }) chosenRoutine() else emptyList()
            due.forEach { post(it, routine) }
            store.markPosted(due)
            lastPosted = lastPosted + due.associate { it.type to it.time }
            postedThrough = planning.postedThroughAfterFiring(now)
            store.postedThrough = postedThrough
        }
        val next = planning.nextAlarm(upcoming, now, postedThrough, lastPosted)
        if (next == null) alarmManager.cancel(alarmIntent()) else arm(next)
    }

    /**
     * Habits from yesterday's, today's and tomorrow's schedules; empty without nights; null while insights are still
     * loading. The insights already carry one of those days: today's, or yesterday's for a night owl between
     * midnight and the end of yesterday's melatonin window.
     */
    private suspend fun upcomingHabits(): List<ScheduledHabit>? {
        val state = withTimeoutOrNull(STATE_WAIT) { insights.state.first { it !is InsightsState.Loading } }
            ?: return null
        val insightsToday = (state as? InsightsState.Ready)?.insights ?: return emptyList()
        val today = insightsToday.today
        val otherDays = withTimeoutOrNull(OTHER_DAYS_WAIT) {
            val settings = habits.habitSettings.first()
            coroutineScope {
                listOf(today.minusDays(1), today, today.plusDays(1))
                    .filter { it != insightsToday.schedule.date }
                    .map { date -> async { habitsOn(date, settings) } }
                    .awaitAll()
                    .flatten()
            }
        }
        return insightsToday.habits + otherDays.orEmpty()
    }

    /** Best effort: a day whose schedule can't be had adds no reminders, and today's are still planned. */
    private suspend fun habitsOn(date: LocalDate, settings: List<HabitSettings>): List<ScheduledHabit> = try {
        insights.schedule(date).first()?.let { scheduleHabits(it, settings) }.orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "No reminders from the $date schedule", e)
        emptyList()
    }

    /** "📖 Reading, 🎧 Podcast": the evening-routine activities the user picked, for its reminder. Best effort. */
    private suspend fun chosenRoutine(): List<String> = try {
        withTimeoutOrNull(OTHER_DAYS_WAIT) { habits.routineActivities.first() }.orEmpty()
            .filter { it.selected }
            .map { "${it.emoji} ${it.label}".trim() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "No routine activities for the reminder", e)
        emptyList()
    }

    private fun post(habit: ScheduledHabit, routine: List<String>) {
        val copy = HabitReminderCopy.of(habit.type)
        val body = if (habit.type == HabitType.EVENING_ROUTINE && routine.isNotEmpty()) {
            poster.text(R.string.reminder_evening_routine_body_with_activities, routine.joinToString(", "))
        } else {
            poster.text(copy.body)
        }
        val builder = poster.builder(NotificationChannels.forCategory(habit.category), DeepLinks.energy())
            .setContentTitle(poster.text(copy.title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setWhen(habit.time.toEpochMilli())
            .setShowWhen(true)
        // A window reminder (morning light, wind-down, bedtime, nap) is stale once its window has passed.
        habit.windowEnd?.let { end ->
            val left = Duration.between(time.now(), end)
            if (!left.isNegative && !left.isZero) builder.setTimeoutAfter(left.toMillis())
        }
        poster.post(NotificationIds.habit(habit.type), builder.build())
    }

    private fun arm(at: Instant) =
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), alarmIntent())

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        ReminderReceiver.REQUEST_HABIT_ALARM,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_HABIT_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "SleepSaverReminders"
        val STATE_WAIT = 5.seconds
        val OTHER_DAYS_WAIT = 2.seconds
        val RETRY_WHILE_LOADING: Duration = Duration.ofMinutes(1)
    }
}

/** Survives process death: what was already posted, so a replan never posts it twice. */
internal class ReminderStore(context: Context) {
    private val prefs = context.getSharedPreferences("sleepsaver_reminders", Context.MODE_PRIVATE)

    /** The latest reminder time a fire covered. */
    var postedThrough: Instant?
        get() = prefs.getLong(KEY_POSTED_THROUGH, -1L).takeIf { it >= 0 }?.let(Instant::ofEpochMilli)
        set(value) = prefs.edit(commit = true) {
            if (value == null) remove(KEY_POSTED_THROUGH) else putLong(KEY_POSTED_THROUGH, value.toEpochMilli())
        }

    /** Per habit, the scheduled time of its most recently posted reminder. */
    val lastPosted: Map<HabitType, Instant>
        get() = HabitType.entries.mapNotNull { type ->
            prefs.getLong(lastPostedKey(type), -1L).takeIf { it >= 0 }?.let { type to Instant.ofEpochMilli(it) }
        }.toMap()

    fun markPosted(habits: List<ScheduledHabit>) {
        if (habits.isEmpty()) return
        prefs.edit(commit = true) { habits.forEach { putLong(lastPostedKey(it.type), it.time.toEpochMilli()) } }
    }

    fun reset() = prefs.edit(commit = true) { clear() }

    private companion object {
        const val KEY_POSTED_THROUGH = "posted_through_ms"

        fun lastPostedKey(type: HabitType) = "last_posted_${type.name}_ms"
    }
}
