package com.sleepsaver.core.data.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sleepsaver.core.data.db.SessionWithStages
import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.data.demo.DemoDataWriter
import com.sleepsaver.core.data.demo.DemoModeControllerImpl
import com.sleepsaver.core.data.demo.FakeSleepDataSource
import com.sleepsaver.core.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Provider

/**
 * Robolectric 4.17's Android 16 (SDK 36) runtime reflects into `jdk.internal.access`, which JDK 25 blocks unless
 * the test JVM gets `--add-exports java.base/jdk.internal.access=ALL-UNNAMED`. SDK 35 doesn't need it.
 */
const val ROBOLECTRIC_SDK = 35

/** In-memory Room + a private DataStore file + fakes around a real [SyncManager]. Robolectric only. */
class SyncFixture(dataStoreDir: File) {
    val zone: ZoneId = ZoneId.of("America/New_York")

    /** Tuesday 2026-10-06, 09:00 local. */
    val time = FakeTimeProvider(LocalDateTime.of(2026, 10, 6, 9, 0).atZone(zone).toInstant(), zone)

    val db: SleepSaverDatabase = Room
        .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SleepSaverDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    val source = FakeHealthConnectSource()
    val gateway = FakeGateway()
    val settings = FakeSettingsRepository()
    val insights = FakeInsightsRepository()
    val notifier = RecordingNotifier()
    val scheduler = RecordingScheduler()
    var foreground = true

    val store = SyncStateStore(
        PreferenceDataStoreFactory.create(produceFile = { File(dataStoreDir, "sync_state_test.preferences_pb") }),
    )
    val demoSource = FakeSleepDataSource(time)
    val demoWriter = DemoDataWriter(demoSource, db.sleepSessionDao(), db.vitalDao(), time)
    val demoMode = DemoModeControllerImpl(
        settingsRepository = settings,
        writer = demoWriter,
        overrideDao = db.nightOverrideDao(),
        addedAwakeDao = db.addedAwakeDao(),
        manualNapDao = db.manualNapDao(),
        reviewDao = db.morningReviewDao(),
        timeProvider = time,
    )

    fun manager(scope: CoroutineScope, settingsRepository: SettingsRepository = settings) = SyncManager(
        gateway = gateway,
        source = source,
        settingsRepository = settingsRepository,
        insightsRepository = Provider { insights },
        notifier = Provider { notifier },
        sessionDao = db.sleepSessionDao(),
        vitalDao = db.vitalDao(),
        store = store,
        demoMode = demoMode,
        timeProvider = time,
        scheduler = scheduler,
        appVisibility = { foreground },
        appScope = scope,
    )

    suspend fun storedSessions(): List<SessionWithStages> = db.sleepSessionDao().getWithStages(Long.MIN_VALUE, Long.MAX_VALUE)

    fun close() = db.close()
}
