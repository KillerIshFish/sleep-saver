package com.sleepsaver.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.os.UserManagerCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.sleepsaver.core.alarm.AlarmBootstrap
import com.sleepsaver.core.data.sync.SyncBootstrap
import com.sleepsaver.core.notifications.NotificationsBootstrap
import com.sleepsaver.feature.widget.WidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Direct-boot aware: after a reboot the process can start before the first unlock (an alarm, a boot broadcast), when
 * credential-encrypted storage (Room, DataStore, normal SharedPreferences) can't be opened. Only the alarm machinery
 * starts then ([AlarmBootstrap.startLocked], which reads only the device-protected mirror). Everything else is a
 * dagger.Lazy that is created and started once, after unlock: at once when the user is already unlocked, else on
 * ACTION_USER_UNLOCKED.
 */
@HiltAndroidApp
class SleepSaverApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var alarmBootstrap: AlarmBootstrap
    @Inject lateinit var syncBootstrap: dagger.Lazy<SyncBootstrap>
    @Inject lateinit var notificationsBootstrap: dagger.Lazy<NotificationsBootstrap>
    @Inject lateinit var widgetUpdater: dagger.Lazy<WidgetUpdater>
    private val unlockedStarted = AtomicBoolean(false)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        alarmBootstrap.startLocked() // safe before unlock
        if (UserManagerCompat.isUserUnlocked(this)) startUnlocked() else startWhenUnlocked()
    }

    private fun startWhenUnlocked() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching { unregisterReceiver(this) }
                startUnlocked()
            }
        }
        ContextCompat.registerReceiver(this, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
        // The unlock may have happened between the check and the registration.
        if (UserManagerCompat.isUserUnlocked(this)) {
            runCatching { unregisterReceiver(receiver) }
            startUnlocked()
        }
    }

    /**
     * Main thread, once. Sync on every app open + periodic background sync; notification channels + reminder
     * planning; the home-screen widget in step with the data; then the alarm follows the plan.
     */
    private fun startUnlocked() {
        if (!unlockedStarted.compareAndSet(false, true)) return
        syncBootstrap.get().start()
        notificationsBootstrap.get().start()
        widgetUpdater.get().start()
        alarmBootstrap.startUnlocked()
    }
}
