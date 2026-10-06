package com.sleepsaver.core.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.data.repository.SyncTrigger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Background sync. PERIODIC runs once an hour (and no-ops without the background-read permission while the
 * app isn't visible). RETRY runs while last night is missing: it asks WorkManager for another attempt with
 * exponential backoff until the night shows up or SleepConfig.waitingGiveUpAt passes.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncManager: SyncManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER)
            ?.let { name -> SyncTrigger.entries.firstOrNull { it.name == name } }
            ?: SyncTrigger.PERIODIC
        val result = syncManager.sync(trigger)
        if (trigger != SyncTrigger.RETRY) return Result.success()
        // Errors that another attempt can't fix end the chain; the next app start syncs anyway.
        val worthRetrying = result.error == null || result.error == SyncError.RATE_LIMITED || result.error == SyncError.UNKNOWN
        return if (worthRetrying && syncManager.isWaitingForLastNight()) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
    }
}

/** Enqueues the sync workers. Abstracted so the sync logic can be tested without WorkManager. */
interface SyncWorkScheduler {
    /** Unique periodic work, kept if already enqueued. */
    fun ensurePeriodic()

    /** (Re)starts the "last night is missing" retry chain. */
    fun startWaitingRetries()
}

@Singleton
class WorkManagerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncWorkScheduler {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override fun ensurePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_INTERVAL)
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to SyncTrigger.PERIODIC.name))
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun startWaitingRetries() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(RETRY_DELAY)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_DELAY)
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to SyncTrigger.RETRY.name))
            .build()
        workManager.enqueueUniqueWork(RETRY_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val PERIODIC_WORK_NAME = "sleepsaver-periodic-sync"
        const val RETRY_WORK_NAME = "sleepsaver-sync-retry"
        val PERIODIC_INTERVAL: Duration = Duration.ofHours(1)
        val RETRY_DELAY: Duration = Duration.ofMinutes(15)
    }
}
