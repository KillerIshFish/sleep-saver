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
import com.sleepsaver.core.data.db.MorningReviewEntity
import com.sleepsaver.core.data.db.NightOverrideEntity
import com.sleepsaver.core.data.hooks.MorningReviewNotifier
import com.sleepsaver.core.data.hooks.ReminderPlanner
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
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DataResetterImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = inMemoryDatabase(context)
    private val planner = RecordingPlanner()
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
    fun `reset wipes data and settings and stops background work`() = runTest {
        val store = preferencesStore(backgroundScope, tmp.root)
        val settings = SettingsRepositoryImpl(store)
        settings.setOnboardingDone(true)
        settings.setSleepNeed(Duration.ofHours(9))
        db.nightOverrideDao().upsert(NightOverrideEntity("2026-10-06", null, null, 10, null, false, 0, 0))
        db.morningReviewDao().upsert(MorningReviewEntity("2026-10-06", "CONFIRMED", 0, null, null))
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<NoOpWorker>().setInitialDelay(1, TimeUnit.HOURS).build()
        workManager.enqueue(request).result.get()

        DataResetterImpl(context, db, store, planner, notifier, sync, StandardTestDispatcher(testScheduler)).resetAll()

        assertThat(db.nightOverrideDao().getAll()).isEmpty()
        assertThat(db.morningReviewDao().getAll()).isEmpty()
        assertThat(settings.current()).isEqualTo(AppSettings())
        assertThat(workManager.getWorkInfoById(request.id).get()?.state).isEqualTo(WorkInfo.State.CANCELLED)
        assertThat(planner.cancelled).isTrue()
        assertThat(notifier.cancelled).isTrue()
        assertThat(sync.reset).isTrue()
    }

    class NoOpWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result = Result.success()
    }

    private class RecordingPlanner : ReminderPlanner {
        var cancelled = false
        override suspend fun replan() = Unit
        override fun cancelAll() {
            cancelled = true
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
