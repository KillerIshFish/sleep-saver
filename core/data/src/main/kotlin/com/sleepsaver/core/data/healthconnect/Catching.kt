package com.sleepsaver.core.data.healthconnect

import android.health.connect.HealthConnectException
import android.os.Build
import android.os.RemoteException
import androidx.annotation.RequiresApi
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

/**
 * Whether Health Connect refused the call because the app's quota is used up. The Android 9–13 app reports it as a
 * RemoteException/IOException whose message mentions the rate limit; the Android 14+ framework throws a
 * HealthConnectException(ERROR_RATE_LIMIT_EXCEEDED), which the client library wraps in an IllegalStateException.
 */
internal fun Throwable.isRateLimit(): Boolean = generateSequence(this) { it.cause }.any { e ->
    ((e is RemoteException || e is IOException) && e.message?.contains("rate", ignoreCase = true) == true) ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && e.isFrameworkRateLimit())
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun Throwable.isFrameworkRateLimit(): Boolean =
    this is HealthConnectException && errorCode == HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED
