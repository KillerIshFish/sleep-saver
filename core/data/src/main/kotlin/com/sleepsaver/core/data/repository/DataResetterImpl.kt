package com.sleepsaver.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.work.WorkManager
import androidx.work.await
import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.data.di.IoDispatcher
import com.sleepsaver.core.data.di.SettingsDataStore
import com.sleepsaver.core.data.hooks.MorningReviewNotifier
import com.sleepsaver.core.data.hooks.ReminderPlanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Reset all data": stops background work first so nothing writes while the tables are cleared, then wipes
 * the database and settings. Health Connect itself is never touched.
 */
@Singleton
class DataResetterImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: SleepSaverDatabase,
    @SettingsDataStore private val settingsStore: DataStore<Preferences>,
    private val reminderPlanner: ReminderPlanner,
    private val morningReviewNotifier: MorningReviewNotifier,
    private val syncRepository: SyncRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DataResetter {

    override suspend fun resetAll() {
        WorkManager.getInstance(context).cancelAllWork().await()
        reminderPlanner.cancelAll()
        morningReviewNotifier.cancel()
        syncRepository.resetSyncState()
        withContext(ioDispatcher) { database.clearAllTables() }
        settingsStore.edit { it.clear() }
    }
}
