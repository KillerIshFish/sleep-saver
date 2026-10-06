package com.sleepsaver.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.sleepsaver.core.data.sync.SyncBootstrap
import com.sleepsaver.core.notifications.NotificationsBootstrap
import com.sleepsaver.feature.widget.WidgetUpdater
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SleepSaverApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncBootstrap: SyncBootstrap
    @Inject lateinit var notificationsBootstrap: NotificationsBootstrap
    @Inject lateinit var widgetUpdater: WidgetUpdater

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Sync on every app open + periodic background sync; notification channels + reminder planning;
        // keep the home-screen widget in step with the data.
        syncBootstrap.start()
        notificationsBootstrap.start()
        widgetUpdater.start()
    }
}
