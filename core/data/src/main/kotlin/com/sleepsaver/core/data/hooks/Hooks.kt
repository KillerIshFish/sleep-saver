package com.sleepsaver.core.data.hooks

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Wall clock + zone, injectable for tests. */
interface TimeProvider {
    fun now(): Instant
    fun zone(): ZoneId

    /** Emits now() immediately and then at the start of every minute. */
    val minuteTicks: Flow<Instant>
}

/**
 * Implemented in :core:notifications. The sync layer calls it when last night's data shows up,
 * so the morning-review notification can be posted (if enabled and not already reviewed).
 */
interface MorningReviewNotifier {
    fun onLastNightArrived(wakeDate: LocalDate)

    /** Without background reads we can't see data arrive; remind at a fixed time instead. */
    fun scheduleFallbackReminder(at: Instant)

    fun cancel()
}

/**
 * Implemented in :core:notifications. Recomputes and re-arms habit reminders from the current schedule.
 * Safe to call often.
 */
interface ReminderPlanner {
    suspend fun replan()
    fun cancelAll()
}
