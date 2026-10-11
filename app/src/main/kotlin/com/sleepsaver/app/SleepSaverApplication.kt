package com.sleepsaver.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.sleepsaver.core.alarm.AlarmBootstrap
import com.sleepsaver.core.data.sync.SyncBootstrap
import com.sleepsaver.core.notifications.NotificationsBootstrap
import com.sleepsaver.feature.widget.WidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Direct-boot aware: after a reboot the process can start before the first unlock (an alarm, a boot broadcast), when
 * credential-encrypted storage (Room, DataStore, normal SharedPreferences) can't be opened. Only the alarm machinery
 * starts then ([AlarmBootstrap.startLocked], which reads only the device-protected mirror). Everything else is a
 * dagger.Lazy that is created and started once, after unlock: at once when the user is already unlocked, else on
 * ACTION_USER_UNLOCKED ([DirectBootStartup]).
 */
@HiltAndroidApp
class SleepSaverApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var alarmBootstrap: AlarmBootstrap
    @Inject lateinit var syncBootstrap: dagger.Lazy<SyncBootstrap>
    @Inject lateinit var notificationsBootstrap: dagger.Lazy<NotificationsBootstrap>
    @Inject lateinit var widgetUpdater: dagger.Lazy<WidgetUpdater>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        DirectBootStartup(
            context = this,
            locked = { alarmBootstrap.startLocked() }, // safe before unlock
            unlocked = ::startUnlocked,
        ).start()
    }

    /**
     * Main thread, once, after unlock. Sync on every app open + periodic background sync; notification channels +
     * reminder planning; the home-screen widget in step with the data; then the alarm follows the plan.
     */
    private fun startUnlocked() {
        syncBootstrap.get().start()
        notificationsBootstrap.get().start()
        widgetUpdater.get().start()
        alarmBootstrap.startUnlocked()
    }
}
