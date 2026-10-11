package com.sleepsaver.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.work.WorkManager
import androidx.work.await
import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.data.demo.DemoEditBackupFile
import com.sleepsaver.core.data.di.IoDispatcher
import com.sleepsaver.core.data.di.SettingsDataStore
import com.sleepsaver.core.data.hooks.AlarmScheduler
import com.sleepsaver.core.data.hooks.MorningReviewNotifier
import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.hooks.ReminderPlanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * "Reset all data": stops the night services and every alarm first (stopEverything, then cancelAll), then background
 * work, so nothing writes while the tables are cleared, then wipes the database (every table, the v2 ones included)
 * and settings. Health Connect itself is never touched.
 *
 * The alarm hooks come through [Provider]s: they are implemented in :core:alarm, whose classes depend on this module's
 * repositories.
 */
@Singleton
class DataResetterImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: SleepSaverDatabase,
    @SettingsDataStore private val settingsStore: DataStore<Preferences>,
    private val reminderPlanner: ReminderPlanner,
    private val morningReviewNotifier: MorningReviewNotifier,
    private val syncRepository: SyncRepository,
    private val alarmScheduler: Provider<AlarmScheduler>,
    private val nightController: Provider<NightController>,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DataResetter {

    override suspend fun resetAll() {
        // A ring, Hush or movement log in progress stops before the alarms (and the mirror they arm from) go.
        nightController.get().stopEverything()
        alarmScheduler.get().cancelAll()
        WorkManager.getInstance(context).cancelAllWork().await()
        reminderPlanner.cancelAll()
        morningReviewNotifier.cancel()
        syncRepository.resetSyncState()
        withContext(ioDispatcher) {
            database.clearAllTables()
            // Real edits set aside by demo mode: they would come back the next time demo mode is turned off.
            DemoEditBackupFile.inFilesDir(context).delete()
        }
        settingsStore.edit { it.clear() }
    }
}
