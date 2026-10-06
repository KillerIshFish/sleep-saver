package com.sleepsaver.core.data.healthconnect

import android.os.RemoteException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Runs [block] and returns null on any exception except cancellation, which is rethrown. */
internal inline fun <T> catchingOrNull(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/** Health Connect signals an exhausted quota with a RemoteException/IOException whose message mentions the rate limit. */
internal fun Throwable.isRateLimit(): Boolean =
    (this is RemoteException || this is IOException) && message?.contains("rate", ignoreCase = true) == true
