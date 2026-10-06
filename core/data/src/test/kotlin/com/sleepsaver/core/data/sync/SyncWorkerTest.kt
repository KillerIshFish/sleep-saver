package com.sleepsaver.core.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.healthconnect.HealthConnectPermissions
import com.sleepsaver.core.data.repository.SyncTrigger
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class SyncWorkerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val today = LocalDate.of(2026, 10, 6)
    private lateinit var fx: SyncFixture

    @Before
    fun setUp() {
        fx = SyncFixture(tmp.newFolder())
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @After
    fun tearDown() = fx.close()

    @Test
    fun scheduler_enqueuesUniquePeriodicAndRetryWork() {
        val scheduler = WorkManagerSyncScheduler(context)
        val workManager = WorkManager.getInstance(context)

        scheduler.ensurePeriodic()
        val firstPeriodic = workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.PERIODIC_WORK_NAME).get().single()
        scheduler.ensurePeriodic()
        val periodic = workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.PERIODIC_WORK_NAME).get()
        assertThat(periodic.map { it.id }).containsExactly(firstPeriodic.id) // KEEP
        assertThat(periodic.single().state).isEqualTo(WorkInfo.State.ENQUEUED)

        scheduler.startWaitingRetries()
        val firstRetry = workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.RETRY_WORK_NAME).get().single()
        scheduler.startWaitingRetries()
        val pending = workManager.getWorkInfosForUniqueWork(WorkManagerSyncScheduler.RETRY_WORK_NAME).get()
            .filterNot { it.state.isFinished }
        assertThat(pending).hasSize(1)
        assertThat(pending.single().id).isNotEqualTo(firstRetry.id) // REPLACE
        // The replaced request is cancelled (and may already be pruned).
        assertThat(workManager.getWorkInfoById(firstRetry.id).get()?.state ?: WorkInfo.State.CANCELLED)
            .isEqualTo(WorkInfo.State.CANCELLED)
        assertThat(pending.single().initialDelayMillis).isEqualTo(WorkManagerSyncScheduler.RETRY_DELAY.toMillis())
    }

    @Test
    fun retryWorker_retriesUntilLastNightArrives() = runTest {
        val manager = fx.manager(backgroundScope)

        assertThat(worker(SyncTrigger.RETRY, manager).doWork()).isEqualTo(ListenableWorker.Result.retry())

        fx.source.upsert(hcSession("last-night", fx.zone, today.minusDays(1).atTime(23, 0), today.atTime(7, 0)))
        assertThat(worker(SyncTrigger.RETRY, manager).doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(fx.notifier.arrived).containsExactly(today)
    }

    @Test
    fun retryWorker_givesUpAfterTheCutoffOrWhenRetryingCantHelp() = runTest {
        val manager = fx.manager(backgroundScope)

        fx.time.setLocal(today.atTime(14, 5))
        assertThat(worker(SyncTrigger.RETRY, manager).doWork()).isEqualTo(ListenableWorker.Result.success())

        fx.time.setLocal(today.atTime(9, 0))
        fx.gateway.granted = setOf(HealthConnectPermissions.STEPS)
        assertThat(worker(SyncTrigger.RETRY, manager).doWork()).isEqualTo(ListenableWorker.Result.success())
    }

    @Test
    fun periodicWorker_succeedsEvenWhenSyncIsNotAllowed() = runTest {
        val manager = fx.manager(backgroundScope)
        fx.foreground = false

        assertThat(worker(SyncTrigger.PERIODIC, manager).doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(fx.source.calls).isEmpty()
    }

    private fun worker(trigger: SyncTrigger, manager: SyncManager): SyncWorker =
        TestListenableWorkerBuilder.from(context, SyncWorker::class.java)
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to trigger.name))
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = SyncWorker(appContext, workerParameters, manager)
                },
            )
            .build()
}
