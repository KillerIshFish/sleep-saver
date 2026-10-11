package com.sleepsaver.feature.tonight

import android.util.Log
import com.sleepsaver.core.data.di.ApplicationScope
import com.sleepsaver.core.data.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every write the Tonight screens make (week plan days, one-offs, marks, the tuning reset), like habits'
 * HabitSettingsWriter: writes run in the app scope on the IO dispatcher, so leaving a screen right after a tap can't
 * cancel a save half-way; they run one at a time in the order they were made, so quick taps on a stepper can't land
 * out of order; and a failure is logged, never thrown, so a tap can't crash the app. One instance for the app, so the
 * order holds across screens too (a day plan's last scrub flushed on the way out, then the week plan's switch).
 *
 * Example: `writer.write("Saving Tuesday") { schedule.updateDay(TUESDAY) { … } }` returns false when it failed.
 */
@Singleton
internal class TonightWriter @Inject constructor(
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val order = Mutex()

    /** Runs [block] after every earlier write and waits for it; false when it threw. */
    suspend fun write(what: String, block: suspend () -> Unit): Boolean =
        // Started undispatched, so the lock is queued for on the caller's thread, in call order.
        appScope.async(start = CoroutineStart.UNDISPATCHED) { ordered(what, block) }.await()

    /** [write] without waiting: for a ViewModel that is going away (a pending scrub flushed in onCleared). */
    fun launch(what: String, block: suspend () -> Unit) {
        appScope.launch(start = CoroutineStart.UNDISPATCHED) { ordered(what, block) }
    }

    private suspend fun ordered(what: String, block: suspend () -> Unit): Boolean =
        order.withLock { withContext(io) { safely(what, block) } }
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

internal const val LOG_TAG = "SleepSaverTonight"
