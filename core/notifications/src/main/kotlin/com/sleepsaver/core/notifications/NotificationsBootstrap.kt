package com.sleepsaver.core.notifications

import android.content.Context
import android.util.Log
import com.sleepsaver.core.data.di.ApplicationScope
import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.hooks.ReminderPlanner
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.ScheduledHabit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/** Call [start] once from `Application.onCreate`. */
@Singleton
class NotificationsBootstrap @Inject constructor(
    @ApplicationContext private val context: Context,
    private val insights: InsightsRepository,
    private val habits: HabitsRepository,
    private val planner: ReminderPlanner,
    private val nightController: NightController,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    /**
     * Creates the notification channels, then replans reminders whenever the habit times or settings change, and when
     * an anchor is dropped or lifted (overnight quiet). The minute-by-minute insights refresh doesn't replan: only what
     * the reminders depend on is compared.
     */
    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        NotificationChannels.create(context)
        scope.launch {
            combine(insights.state, habits.habitSettings) { state, settings -> reminderInputs(state, settings) }
                .filterNotNull()
                .debounce(2.seconds)
                .distinctUntilChanged()
                .catch { Log.w(TAG, "Reminder inputs failed", it) }
                .collectLatest { replanSafely() }
        }
        scope.launch {
            nightController.state.map { it.night?.anchorId }
                .distinctUntilChanged()
                .drop(1) // the value at start needs no replan of its own
                .catch { Log.w(TAG, "Night state failed", it) }
                .collectLatest { replanSafely() }
        }
    }

    private suspend fun replanSafely() {
        try {
            planner.replan()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Replanning reminders failed", e)
        }
    }

    private companion object {
        const val TAG = "SleepSaverReminders"
    }
}

/** What the reminders depend on. Equal inputs → same plan. */
internal data class ReminderInputs(
    val today: LocalDate?,
    val habits: List<ScheduledHabit>,
    val settings: List<HabitSettings>,
)

/** Null while insights are loading (nothing to plan yet, and the armed alarm stays as it is). */
internal fun reminderInputs(state: InsightsState, settings: List<HabitSettings>): ReminderInputs? = when (state) {
    InsightsState.Loading -> null
    InsightsState.NoNights -> ReminderInputs(today = null, habits = emptyList(), settings = settings)
    is InsightsState.Ready -> ReminderInputs(state.insights.today, state.insights.habits, settings)
}
