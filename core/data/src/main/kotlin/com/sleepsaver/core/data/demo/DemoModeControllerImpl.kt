package com.sleepsaver.core.data.demo

import com.sleepsaver.core.data.db.AddedAwakeDao
import com.sleepsaver.core.data.db.ManualNapDao
import com.sleepsaver.core.data.db.MorningReviewDao
import com.sleepsaver.core.data.db.NightOverrideDao
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.DemoModeController
import com.sleepsaver.core.data.repository.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enabling sets the flag (remembering when demo mode started) and loads the demo nights. Disabling deletes
 * every demo row plus the edits made since demo mode started (overrides, manual naps, added awake time,
 * morning reviews), so the real data comes back exactly as it was. Health Connect rows are never touched.
 */
@Singleton
class DemoModeControllerImpl @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val writer: DemoDataWriter,
    private val overrideDao: NightOverrideDao,
    private val addedAwakeDao: AddedAwakeDao,
    private val manualNapDao: ManualNapDao,
    private val reviewDao: MorningReviewDao,
    private val timeProvider: TimeProvider,
) : DemoModeController {

    private val mutex = Mutex()

    override suspend fun setDemoMode(enabled: Boolean) = mutex.withLock {
        val current = settingsRepository.current()
        if (enabled) {
            // Re-enabling keeps the original start so edits made since then are still cleaned up later.
            if (!current.demoMode) settingsRepository.setDemoModeFlag(true, timeProvider.now())
            writer.writeAll()
        } else {
            writer.clear()
            current.demoStartedAt?.toEpochMilli()?.let { since ->
                overrideDao.deleteUpdatedSince(since)
                manualNapDao.deleteCreatedSince(since)
                addedAwakeDao.deleteCreatedSince(since)
                reviewDao.deleteSince(since)
            }
            settingsRepository.setDemoModeFlag(false, null)
        }
        Unit
    }
}
