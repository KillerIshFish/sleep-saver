package com.sleepsaver.core.data.repository

import android.util.Log
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs an engine computation inside a long-lived flow. A failure is logged and turned into null so the flow
 * keeps its previous value instead of crashing every screen that collects it.
 */
internal inline fun <T> guarded(tag: String, what: String, block: () -> T): T? = try {
    block()
} catch (e: Throwable) {
    if (e is CancellationException || e is VirtualMachineError) throw e
    Log.e(tag, "$what failed", e)
    null
}
