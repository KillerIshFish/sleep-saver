package com.sleepsaver.feature.habits

import android.util.Log
import com.sleepsaver.core.data.hooks.ReminderPlanner
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.domain.model.HabitType
import kotlinx.coroutines.CancellationException
import java.time.Duration
import javax.inject.Inject

/**
 * Writes habit settings and then re-arms reminders right away, so a changed time or reminder takes effect
 * without waiting for the app-wide replan. Failures are logged, never thrown: a tap must not crash the app.
 */
internal class HabitSettingsWriter @Inject constructor(
    private val habits: HabitsRepository,
    private val planner: ReminderPlanner,
) {
    suspend fun setEnabled(type: HabitType, enabled: Boolean) = writeThenReplan { habits.setEnabled(type, enabled) }

    suspend fun setReminder(type: HabitType, enabled: Boolean) = writeThenReplan { habits.setReminder(type, enabled) }

    suspend fun setOffset(type: HabitType, offset: Duration) = writeThenReplan { habits.setOffset(type, offset) }

    suspend fun resetOffset(type: HabitType) = writeThenReplan { habits.resetOffset(type) }

    /** Returns false when the write failed (nothing to replan then). */
    private suspend fun writeThenReplan(write: suspend () -> Unit): Boolean {
        if (!safely("Saving habit settings", write)) return false
        safely("Replanning reminders") { planner.replan() }
        return true
    }
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
