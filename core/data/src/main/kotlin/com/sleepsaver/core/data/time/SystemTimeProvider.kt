package com.sleepsaver.core.data.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.sleepsaver.core.data.hooks.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device clock and zone. [minuteTicks] also restarts (and emits at once) when the user changes the
 * time or the time zone, so "today" and the schedule follow travel without waiting for the next minute,
 * and when the screen turns on: `delay` runs on the monotonic clock, which stops while the device sleeps,
 * so after standby the pending tick (and with it "now" and "today") would otherwise lag behind.
 */
@Singleton
class SystemTimeProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : TimeProvider {

    override fun now(): Instant = Instant.now()

    /** Android resets the JVM default zone when the system zone changes. */
    override fun zone(): ZoneId = ZoneId.systemDefault()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val minuteTicks: Flow<Instant> =
        clockChanges().onStart { emit(Unit) }.flatMapLatest { minuteTicker(::now) }

    private fun clockChanges(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }
}

/** Emits [now] immediately, then right after every minute boundary. */
internal fun minuteTicker(now: () -> Instant): Flow<Instant> = flow {
    while (true) {
        val current = now()
        emit(current)
        val nextMinute = current.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES)
        delay((nextMinute.toEpochMilli() - now().toEpochMilli()).coerceAtLeast(1))
    }
}
