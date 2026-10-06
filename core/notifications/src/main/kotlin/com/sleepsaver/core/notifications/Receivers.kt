package com.sleepsaver.core.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sleepsaver.core.data.di.ApplicationScope
import com.sleepsaver.core.data.repository.SyncRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds

/** What the receivers need from the Hilt graph. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotificationsEntryPoint {
    fun reminderPlanner(): ReminderPlannerImpl
    fun morningReviewNotifier(): MorningReviewNotifierImpl
    fun syncRepository(): SyncRepository

    @ApplicationScope
    fun applicationScope(): CoroutineScope
}

private fun Context.notificationsEntryPoint(): NotificationsEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, NotificationsEntryPoint::class.java)

/** Fires for the habit-reminder alarm and the morning-review fallback alarm. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val entry = context.notificationsEntryPoint()
        when (intent.action) {
            ACTION_HABIT_ALARM -> launchAsync(entry.applicationScope()) { entry.reminderPlanner().onAlarm() }
            ACTION_REVIEW_FALLBACK -> {
                val wakeDate = intent.getStringExtra(EXTRA_WAKE_DATE)?.let(LocalDate::parse) ?: return
                launchAsync(entry.applicationScope()) { entry.morningReviewNotifier().onFallbackAlarm(wakeDate) }
            }
        }
    }

    internal companion object {
        const val ACTION_HABIT_ALARM = "com.sleepsaver.core.notifications.HABIT_ALARM"
        const val ACTION_REVIEW_FALLBACK = "com.sleepsaver.core.notifications.REVIEW_FALLBACK"
        const val EXTRA_WAKE_DATE = "wakeDate"
        const val REQUEST_HABIT_ALARM = 1
        const val REQUEST_REVIEW_FALLBACK = 2
    }
}

/**
 * Re-arms reminders when alarms were wiped (reboot, app update) or local times moved (clock or time zone change),
 * and makes sure the periodic sync is enqueued.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val entry = context.notificationsEntryPoint()
        entry.syncRepository().ensurePeriodicSync()
        launchAsync(entry.applicationScope()) { entry.reminderPlanner().replan() }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}

/** Runs [block] off the main thread, keeping the broadcast alive until it's done (bounded well below the receiver timeout). */
private fun BroadcastReceiver.launchAsync(scope: CoroutineScope, block: suspend () -> Unit) {
    val pending = goAsync()
    scope.launch {
        try {
            withTimeoutOrNull(9.seconds) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SleepSaverReminders", "Handling ${this@launchAsync.javaClass.simpleName} failed", e)
        } finally {
            pending.finish()
        }
    }
}
