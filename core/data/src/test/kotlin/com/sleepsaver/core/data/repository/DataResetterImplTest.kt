package com.sleepsaver.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.db.AnchorEntity
import com.sleepsaver.core.data.db.MorningReviewEntity
import com.sleepsaver.core.data.db.NightMarkEntity
import com.sleepsaver.core.data.db.NightOverrideEntity
import com.sleepsaver.core.data.demo.DemoEditBackup
import com.sleepsaver.core.data.demo.DemoEditBackupFile
import com.sleepsaver.core.data.hooks.MorningReviewNotifier
import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.hooks.ReminderPlanner
import com.sleepsaver.core.data.testing.RecordingAlarmScheduler
import com.sleepsaver.core.data.testing.RecordingNightController
import com.sleepsaver.core.data.testing.inMemoryDatabase
import com.sleepsaver.core.data.testing.preferencesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DataResetterImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = inMemoryDatabase(context)
    private val calls = mutableListOf<String>()
    private val planner = RecordingPlanner(calls)
    private val alarms = RecordingAlarmScheduler(calls)
    private val night = RecordingNightController(calls)
    private val notifier = RecordingNotifier()
    private val sync = RecordingSync()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `reset stops the night and the alarms first, then wipes data and settings and stops background work`() = runTest {
        val store = preferencesStore(backgroundScope, tmp.root)
        val settings = SettingsRepositoryImpl(store)
        settings.setOnboardingDone(true)
        settings.setSleepNeed(Duration.ofHours(9))
        db.nightOverrideDao().upsert(NightOverrideEntity("2026-10-06", null, null, 10, null, false, 0, 0))
        db.morningReviewDao().upsert(MorningReviewEntity("2026-10-06", "CONFIRMED", 0, null, null))
        // A v2 table: clearAllTables covers it.
        db.nightMarkDao().insert(NightMarkEntity("2026-10-06", "ALCOHOL", 0, 0))
        db.anchorDao().insert(AnchorEntity(atEpochMs = 0, offsetSec = 0, wakeDate = "2026-10-06", source = "TONIGHT", wholeNightLog = false))
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<NoOpWorker>().setInitialDelay(1, TimeUnit.HOURS).build()
        workManager.enqueue(request).result.get()
        // Real edits set aside while demo mode was on: they must not come back when it is turned off after the reset.
        val demoBackup = File(context.filesDir, DemoEditBackupFile.FILE_NAME)
        DemoEditBackupFile(demoBackup).write(DemoEditBackup())

        DataResetterImpl(context, db, store, planner, notifier, sync, { alarms }, { night }, StandardTestDispatcher(testScheduler)).resetAll()

        assertThat(demoBackup.exists()).isFalse()
        assertThat(db.nightOverrideDao().getAll()).isEmpty()
        assertThat(db.morningReviewDao().getAll()).isEmpty()
        assertThat(db.nightMarkDao().getAll()).isEmpty()
        assertThat(db.anchorDao().getAll()).isEmpty()
        assertThat(settings.current()).isEqualTo(AppSettings())
        assertThat(workManager.getWorkInfoById(request.id).get()?.state).isEqualTo(WorkInfo.State.CANCELLED)
        // The night services stop before the alarms (and their mirror) go, and both before anything else.
        assertThat(calls).containsExactly("stopEverything", "cancelAll", "reminders").inOrder()
        assertThat(notifier.cancelled).isTrue()
        assertThat(sync.reset).isTrue()
    }

    @Test
    fun `reset still wipes the data when stopping the night services fails`() = runTest {
        val store = preferencesStore(backgroundScope, tmp.root)
        db.nightMarkDao().insert(NightMarkEntity("2026-10-06", "ALCOHOL", 0, 0))
        val broken = object : NightController by night {
            override fun stopEverything() = throw IllegalStateException("service gone")
        }

        DataResetterImpl(context, db, store, planner, notifier, sync, { alarms }, { broken }, StandardTestDispatcher(testScheduler)).resetAll()

        assertThat(db.nightMarkDao().getAll()).isEmpty()
        assertThat(calls).containsExactly("cancelAll", "reminders").inOrder()
    }

    class NoOpWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result = Result.success()
    }

    private class RecordingPlanner(private val calls: MutableList<String>) : ReminderPlanner {
        override suspend fun replan() = Unit
        override fun cancelAll() {
            calls += "reminders"
        }
    }

    private class RecordingNotifier : MorningReviewNotifier {
        var cancelled = false
        override fun onLastNightArrived(wakeDate: java.time.LocalDate) = Unit
        override fun scheduleFallbackReminder(at: Instant) = Unit
        override fun cancel() {
            cancelled = true
        }
    }

    private class RecordingSync : SyncRepository {
        var reset = false
        override val state: StateFlow<SyncState> = MutableStateFlow(SyncState())
        override suspend fun sync(trigger: SyncTrigger) = SyncResult(success = true)
        override suspend fun importHistory() = SyncResult(success = true)
        override fun ensurePeriodicSync() = Unit
        override fun startWaitingRetries() = Unit
        override suspend fun resetSyncState() {
            reset = true
        }
    }
}
