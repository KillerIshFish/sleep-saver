package com.sleepsaver.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.sleepsaver.core.data.db.AddedAwakeDao
import com.sleepsaver.core.data.db.HabitDao
import com.sleepsaver.core.data.db.ManualNapDao
import com.sleepsaver.core.data.db.MorningReviewDao
import com.sleepsaver.core.data.db.NightOverrideDao
import com.sleepsaver.core.data.db.RoutineActivityDao
import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.data.db.SleepSessionDao
import com.sleepsaver.core.data.db.VitalDao
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.DataResetter
import com.sleepsaver.core.data.repository.DataResetterImpl
import com.sleepsaver.core.data.repository.ExportRepository
import com.sleepsaver.core.data.repository.ExportRepositoryImpl
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.data.repository.HabitsRepositoryImpl
import com.sleepsaver.core.data.repository.HushSettingsRepository
import com.sleepsaver.core.data.repository.HushSettingsRepositoryImpl
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.InsightsRepositoryImpl
import com.sleepsaver.core.data.repository.JournalRepository
import com.sleepsaver.core.data.repository.JournalRepositoryImpl
import com.sleepsaver.core.data.repository.NightLogRepository
import com.sleepsaver.core.data.repository.NightLogRepositoryImpl
import com.sleepsaver.core.data.repository.NightStepsRepository
import com.sleepsaver.core.data.repository.NightStepsRepositoryImpl
import com.sleepsaver.core.data.repository.PatternsRepository
import com.sleepsaver.core.data.repository.PatternsRepositoryImpl
import com.sleepsaver.core.data.repository.ReviewRepository
import com.sleepsaver.core.data.repository.ReviewRepositoryImpl
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.data.repository.ScheduleRepositoryImpl
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SettingsRepositoryImpl
import com.sleepsaver.core.data.repository.SleepRepository
import com.sleepsaver.core.data.repository.SleepRepositoryImpl
import com.sleepsaver.core.data.time.SystemTimeProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** The app's "settings" preferences store (see SettingsRepositoryImpl). Other stores use their own qualifiers. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsDataStore

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): SleepSaverDatabase =
        Room.databaseBuilder(context, SleepSaverDatabase::class.java, SleepSaverDatabase.NAME)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides
    fun sleepSessionDao(database: SleepSaverDatabase): SleepSessionDao = database.sleepSessionDao()

    @Provides
    fun nightOverrideDao(database: SleepSaverDatabase): NightOverrideDao = database.nightOverrideDao()

    @Provides
    fun addedAwakeDao(database: SleepSaverDatabase): AddedAwakeDao = database.addedAwakeDao()

    @Provides
    fun manualNapDao(database: SleepSaverDatabase): ManualNapDao = database.manualNapDao()

    @Provides
    fun vitalDao(database: SleepSaverDatabase): VitalDao = database.vitalDao()

    @Provides
    fun morningReviewDao(database: SleepSaverDatabase): MorningReviewDao = database.morningReviewDao()

    @Provides
    fun habitDao(database: SleepSaverDatabase): HabitDao = database.habitDao()

    @Provides
    fun routineActivityDao(database: SleepSaverDatabase): RoutineActivityDao = database.routineActivityDao()

    @Provides
    @Singleton
    @SettingsDataStore
    fun settingsDataStore(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = CoroutineScope(ioDispatcher + SupervisorJob()),
        produceFile = { context.preferencesDataStoreFile(SettingsRepositoryImpl.STORE_NAME) },
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindingsModule {
    @Binds
    abstract fun timeProvider(impl: SystemTimeProvider): TimeProvider

    @Binds
    abstract fun settingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    abstract fun sleepRepository(impl: SleepRepositoryImpl): SleepRepository

    @Binds
    abstract fun insightsRepository(impl: InsightsRepositoryImpl): InsightsRepository

    @Binds
    abstract fun habitsRepository(impl: HabitsRepositoryImpl): HabitsRepository

    @Binds
    abstract fun reviewRepository(impl: ReviewRepositoryImpl): ReviewRepository

    @Binds
    abstract fun exportRepository(impl: ExportRepositoryImpl): ExportRepository

    @Binds
    abstract fun dataResetter(impl: DataResetterImpl): DataResetter

    // v2. Allowed repository → repository edges: §3.4 (each impl's KDoc lists its own).

    @Binds
    abstract fun scheduleRepository(impl: ScheduleRepositoryImpl): ScheduleRepository

    @Binds
    abstract fun nightStepsRepository(impl: NightStepsRepositoryImpl): NightStepsRepository

    @Binds
    abstract fun journalRepository(impl: JournalRepositoryImpl): JournalRepository

    @Binds
    abstract fun nightLogRepository(impl: NightLogRepositoryImpl): NightLogRepository

    @Binds
    abstract fun patternsRepository(impl: PatternsRepositoryImpl): PatternsRepository

    @Binds
    abstract fun hushSettingsRepository(impl: HushSettingsRepositoryImpl): HushSettingsRepository
}
