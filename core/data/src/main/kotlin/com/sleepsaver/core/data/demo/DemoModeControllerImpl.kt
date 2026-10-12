package com.sleepsaver.core.data.demo

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.data.di.IoDispatcher
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.DemoModeController
import com.sleepsaver.core.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Demo nights are separate rows (SourceKind.DEMO) and are simply deleted again, but the user's edits (night
 * overrides, added awake time, manual naps, morning reviews), journal (marks, Morning feel, Night steps logs,
 * Tomorrow lists) and prompt log (a prompt shows once per wake date) live in tables keyed by date that demo mode uses
 * too. So turning demo mode on moves those nine tables aside into [DemoEditBackupFile], empties them and fills the
 * journal with [DemoJournalWriter]'s synthetic rows; turning it off empties them again (dropping the demo journal,
 * the prompts shown for demo nights and whatever was edited during the demo) and puts the saved rows back exactly.
 * Configuration and device logs (week plan, routine activities, anchors, movement, alarm history, tuning, Hush plays)
 * are left alone. Health Connect is never touched.
 *
 * Both directions are idempotent and safe to interrupt at any point (every interrupted state has the flag on):
 *  - On: the flag first, then in one transaction (so no edit can slip in between) save the backup, unless one is
 *    already there, and empty the tables; then the demo nights, then the demo journal. A backup found while turning on
 *    comes from an interrupted switch and is kept as it is. Already on: nothing moves, unless the move never ran (no
 *    backup yet). If the move fails, nothing moved and real mode stays on.
 *  - Off: the backup is read first (nothing changes if it can't be), then the demo rows go, then in one transaction
 *    the tables are emptied and refilled from the backup, then the backup is deleted, then the flag cleared.
 *    Without a backup the tables are left alone: they hold real edits.
 *  - [extendToNow] (every sync in demo mode) finishes a switch on that died before it moved the edits aside.
 *
 * A switch runs off the main thread and to the end even if its caller is cancelled (half a switch would leave real
 * edits mixed with demo ones until the next one). It never throws: a failure is logged and the flag keeps
 * describing what is in the tables.
 */
@Singleton
class DemoModeControllerImpl internal constructor(
    private val settingsRepository: SettingsRepository,
    private val writer: DemoDataWriter,
    private val journalWriter: DemoJournalWriter,
    private val database: SleepSaverDatabase,
    private val timeProvider: TimeProvider,
    private val backup: DemoEditBackupFile,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DemoModeController {

    @Inject internal constructor(
        settingsRepository: SettingsRepository,
        writer: DemoDataWriter,
        journalWriter: DemoJournalWriter,
        database: SleepSaverDatabase,
        timeProvider: TimeProvider,
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(settingsRepository, writer, journalWriter, database, timeProvider, DemoEditBackupFile.inFilesDir(context), ioDispatcher)

    private val mutex = Mutex()

    override suspend fun setDemoMode(enabled: Boolean) {
        withContext(ioDispatcher + NonCancellable) {
            mutex.withLock {
                try {
                    if (enabled) enable() else disable()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Turning demo mode ${if (enabled) "on" else "off"} failed", e)
                }
            }
        }
    }

    /**
     * Adds the demo nights that ended since the last call (see [DemoDataWriter.extendToNow]). Checks the flag under
     * the same lock as [setDemoMode], so a sync racing with "turn demo mode off" can't write the demo rows back.
     */
    suspend fun extendToNow(): Int = withContext(ioDispatcher) {
        mutex.withLock {
            if (!settingsRepository.current().demoMode) return@withLock 0
            if (!backup.exists()) finishInterruptedEnable()
            writer.extendToNow()
        }
    }

    private suspend fun enable() {
        val wasOn = settingsRepository.current().demoMode
        if (!wasOn) settingsRepository.setDemoModeFlag(true, timeProvider.now())
        val moving = !wasOn || !backup.exists()
        if (moving) {
            try {
                moveEditsAside()
            } catch (e: Exception) {
                // Nothing moved: the real edits are still in place, so stay in (or go back to) real mode.
                if (!wasOn) settingsRepository.setDemoModeFlag(false, null)
                throw e
            }
        }
        writer.writeAll()
        // Only right after the tables were emptied: a second "on" keeps what was edited during the demo.
        if (moving) writeDemoJournal()
    }

    /** The flag is on but the real edits are still in the tables (no backup): moves them aside now. */
    private suspend fun finishInterruptedEnable() {
        try {
            withContext(NonCancellable) { moveEditsAside() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // The demo nights still roll forward; the next sync tries again.
            Log.e(TAG, "Setting the real edits aside failed", e)
            return
        }
        writeDemoJournal()
    }

    /** The demo journal is a nicety: demo mode works without it, so a failure is only logged. */
    private suspend fun writeDemoJournal() {
        try {
            journalWriter.writeAll()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Writing the demo journal failed", e)
        }
    }

    /**
     * Saves the nine tables to the backup (unless one is already there) and empties them, as one step: if it throws,
     * the tables are as they were and no backup written by this call is left behind. Must not be cancelled midway
     * (callers run it NonCancellable), so an exception always means the transaction rolled back.
     */
    private suspend fun moveEditsAside() {
        var wrote = false
        try {
            database.withTransaction {
                if (!backup.exists()) {
                    backup.write(
                        DemoEditBackup.of(
                            overrides = database.nightOverrideDao().getAll(),
                            addedAwake = database.addedAwakeDao().getAll(),
                            manualNaps = database.manualNapDao().getAll(),
                            reviews = database.morningReviewDao().getAll(),
                            nightMarks = database.nightMarkDao().getAll(),
                            morningFeel = database.morningFeelDao().getAll(),
                            nightStepLogs = database.nightStepLogDao().getAll(),
                            tomorrowLists = database.tomorrowListDao().getAll(),
                            prompts = database.promptLogDao().getAll(),
                        ),
                    )
                    wrote = true
                }
                clearEdits()
            }
        } catch (e: Exception) {
            if (wrote) backup.delete()
            throw e
        }
    }

    private suspend fun disable() {
        // Read first: an IOException (unreadable right now) propagates before anything changed, for a retry.
        val saved = try {
            backup.read()
        } catch (e: IllegalArgumentException) {
            // Damaged content (SerializationException). The tables only hold edits made during the demo, which must
            // not pass for real ones, so they are cleared all the same.
            Log.e(TAG, "Saved edits are unreadable and were dropped", e)
            DemoEditBackup()
        }
        writer.clear()
        if (saved != null) {
            database.withTransaction {
                clearEdits()
                database.nightOverrideDao().upsertAll(saved.overrides.map { it.toEntity() })
                database.addedAwakeDao().insertAll(saved.addedAwake.map { it.toEntity() })
                database.manualNapDao().insertAll(saved.manualNaps.map { it.toEntity() })
                database.morningReviewDao().upsertAll(saved.reviews.map { it.toEntity() })
                database.nightMarkDao().insertAll(saved.nightMarks.map { it.toEntity() })
                database.morningFeelDao().insertAll(saved.morningFeel.map { it.toEntity() })
                database.nightStepLogDao().insertAll(saved.nightStepLogs.map { it.toEntity() })
                database.tomorrowListDao().insertAll(saved.tomorrowLists.map { it.toEntity() })
                database.promptLogDao().insertAll(saved.prompts.map { it.toEntity() })
            }
        }
        backup.delete()
        settingsRepository.setDemoModeFlag(false, null)
    }

    private suspend fun clearEdits() {
        database.nightOverrideDao().deleteAll()
        database.addedAwakeDao().deleteAll()
        database.manualNapDao().deleteAll()
        database.morningReviewDao().deleteAll()
        database.nightMarkDao().deleteAll()
        database.morningFeelDao().deleteAll()
        database.nightStepLogDao().deleteAll()
        database.tomorrowListDao().deleteAll()
        database.promptLogDao().deleteAll()
    }

    private companion object {
        const val TAG = "DemoMode"
    }
}
