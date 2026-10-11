package com.sleepsaver.feature.habits

import android.util.Log
import com.sleepsaver.core.data.di.ApplicationScope
import com.sleepsaver.core.data.di.IoDispatcher
import com.sleepsaver.core.data.hooks.ReminderPlanner
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.domain.model.HabitType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import javax.inject.Inject

/**
 * Every write the habit screens make. Habit changes re-arm reminders right away, so a changed time or
 * reminder takes effect without waiting for the app-wide replan.
 *
 * Writes run in the app scope on the IO dispatcher: leaving a screen right after a tap can't cancel a save
 * half-way, and nothing touches the disk on the main thread. They run one at a time, in the order they were
 * made, so quick stepper taps can't land out of order. Failures are logged, never thrown: a tap must not
 * crash the app. Each call returns false when its write failed.
 */
internal class HabitSettingsWriter @Inject constructor(
    private val habits: HabitsRepository,
    private val planner: ReminderPlanner,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val order = Mutex()

    suspend fun setEnabled(type: HabitType, enabled: Boolean): Boolean =
        save("Saving $type") { habits.setEnabled(type, enabled) }

    suspend fun setReminder(type: HabitType, enabled: Boolean): Boolean =
        save("Saving $type reminder") { habits.setReminder(type, enabled) }

    suspend fun setOffset(type: HabitType, offset: Duration): Boolean =
        save("Saving $type timing") { habits.setOffset(type, offset) }

    suspend fun resetOffset(type: HabitType): Boolean =
        save("Resetting $type timing") { habits.resetOffset(type) }

    // Routine activities are edited in feature:winddown's routine editor (the app's only one).

    private suspend fun save(what: String, write: suspend () -> Unit): Boolean =
        // Started undispatched, so the lock is queued for on the caller's thread, in call order.
        appScope.async(start = CoroutineStart.UNDISPATCHED) {
            order.withLock {
                withContext(io) {
                    if (!safely(what, write)) return@withContext false
                    safely("Replanning reminders") { planner.replan() }
                    true
                }
            }
        }.await()
}

/** Runs a repository call, logging instead of crashing. Returns false when it failed. */
internal suspend fun safely(what: String, block: suspend () -> Unit): Boolean = try {
    block()
    true
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(LOG_TAG, "$what failed", e)
    false
}

internal const val LOG_TAG = "SleepSaverHabits"
