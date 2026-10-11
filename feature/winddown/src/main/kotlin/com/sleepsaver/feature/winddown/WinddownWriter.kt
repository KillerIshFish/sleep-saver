package com.sleepsaver.feature.winddown

import android.util.Log
import com.sleepsaver.core.data.di.ApplicationScope
import com.sleepsaver.core.data.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Every write the Night steps screens make (check-offs, routine edits, marks, the Tomorrow list, the reminder).
 *
 * Writes run in the app scope on the IO dispatcher: leaving a screen right after a tap can't cancel a save half-way,
 * and nothing touches the disk on the main thread. They run one at a time, in the order they were made, so quick
 * stepper taps can't land out of order. Failures are logged, never thrown: a tap must not crash the app.
 */
internal class WinddownWriter @Inject constructor(
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val order = Mutex()

    /** Runs [write]; false when it failed (logged as "[what] failed"). */
    suspend fun save(what: String, write: suspend () -> Unit): Boolean = saveFor(what) { write() } != null

    /** Runs [write] and returns its result; null when it failed. */
    suspend fun <T : Any> saveFor(what: String, write: suspend () -> T): T? =
        // Started undispatched, so the lock is queued for on the caller's thread, in call order.
        appScope.async(start = CoroutineStart.UNDISPATCHED) {
            order.withLock { withContext(io) { safely(what, write) } }
        }.await()
}

/** Runs a repository call, logging instead of crashing. Returns null when it failed (or returned null). */
internal suspend fun <T : Any> safely(what: String, block: suspend () -> T?): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(LOG_TAG, "$what failed", e)
    null
}

internal const val LOG_TAG = "SleepSaverWinddown"
