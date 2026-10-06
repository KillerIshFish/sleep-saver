package com.sleepsaver.core.data.repository

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs an engine computation inside a long-lived flow. A failure is logged and turned into null; pair it with
 * [orLastGood] so the flow still emits something instead of crashing (or starving) every screen that collects it.
 */
internal inline fun <T> guarded(tag: String, what: String, block: () -> T): T? = try {
    block()
} catch (e: Throwable) {
    logFailure(tag, what, e)
    null
}

/**
 * Replaces each null (a failed [guarded] computation) with the last good value, or with [initial] while there is
 * none yet. So a first computation that throws still produces a safe value and nothing waits forever.
 */
internal fun <T : Any> Flow<T?>.orLastGood(initial: T): Flow<T> = flow {
    var last = initial
    collect { value ->
        if (value != null) last = value
        emit(last)
    }
}

/**
 * For Room and DataStore flows that feed process-wide shared state (`stateIn`/`shareIn` in the application scope,
 * where an exception would crash the app): a failing query is retried a couple of times, then logged and the flow
 * ends quietly. If it never emitted, [fallback] is emitted first, so a `combine` or `first()` downstream still gets a
 * value; otherwise the last value simply stays.
 */
internal fun <T> Flow<T>.orFallback(tag: String, what: String, fallback: () -> T): Flow<T> = flow {
    var emitted = false
    emitAll(
        this@orFallback
            .retryWhen { cause, attempt ->
                if (cause is CancellationException || attempt >= MAX_RETRIES) return@retryWhen false
                Log.w(tag, "$what failed, retrying", cause)
                delay(RETRY_DELAY_MS * (attempt + 1))
                true
            }
            .onEach { emitted = true }
            .catch { e ->
                logFailure(tag, what, e)
                if (!emitted) emit(fallback())
            },
    )
}

/** Logs [e] unless it must propagate (cancellation, VM errors), in which case it is rethrown. */
internal fun logFailure(tag: String, what: String, e: Throwable) {
    if (e is CancellationException || e is VirtualMachineError) throw e
    Log.e(tag, "$what failed", e)
}

private const val MAX_RETRIES = 2L
private const val RETRY_DELAY_MS = 1_000L
