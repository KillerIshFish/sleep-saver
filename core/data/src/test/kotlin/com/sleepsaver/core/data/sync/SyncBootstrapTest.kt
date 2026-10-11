package com.sleepsaver.core.data.sync

import android.app.Activity
import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.SyncRepository
import com.sleepsaver.core.data.repository.SyncResult
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.data.repository.SyncTrigger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class SyncBootstrapTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private class RecordingSyncRepository : SyncRepository {
        val triggers = mutableListOf<SyncTrigger>()
        var periodic = 0
        override val state: StateFlow<SyncState> = MutableStateFlow(SyncState())
        override suspend fun sync(trigger: SyncTrigger): SyncResult = SyncResult(success = true).also { triggers += trigger }
        override suspend fun importHistory(): SyncResult = SyncResult(success = true)
        override fun ensurePeriodicSync() {
            periodic++
        }
        override fun startWaitingRetries() = Unit
        override suspend fun resetSyncState() = Unit
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun everyForegroundStartSyncsAndKeepsThePeriodicJob() = runTest(UnconfinedTestDispatcher()) {
        val repository = RecordingSyncRepository()
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val bootstrap = SyncBootstrap(repository, backgroundScope, AppForeground())

        bootstrap.start(owner.registry)
        bootstrap.start(owner.registry) // idempotent
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        assertThat(repository.triggers).containsExactly(SyncTrigger.APP_OPEN, SyncTrigger.APP_OPEN)
        assertThat(repository.periodic).isEqualTo(2)
    }

    /**
     * After a reboot the alarm rings before the first unlock: the ringing screen starts over the lock screen, the user
     * unlocks (SyncBootstrap starts), opens the morning review and leaves the ringing screen. Every later app open must
     * still sync (ProcessLifecycleOwner, attached only at the unlock, lost count here and never synced again).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aScreenStartedBeforeTheUnlockCountsAndLaterAppOpensStillSync() = runTest(UnconfinedTestDispatcher()) {
        val repository = RecordingSyncRepository()
        val foreground = AppForeground().apply { install(application) } // Application.onCreate, still locked
        val visibility = ProcessAppVisibility(foreground)
        val ringing = Robolectric.buildActivity(Activity::class.java).setup()

        SyncBootstrap(repository, backgroundScope, foreground).start() // the first unlock
        assertThat(repository.triggers).containsExactly(SyncTrigger.APP_OPEN)
        assertThat(visibility.isInForeground()).isTrue()

        val review = Robolectric.buildActivity(Activity::class.java).setup()
        ringing.pause().stop().destroy()
        assertThat(repository.triggers).hasSize(1) // still in the app
        review.pause().stop().destroy()
        assertThat(visibility.isInForeground()).isFalse()

        Robolectric.buildActivity(Activity::class.java).setup() // the next app open
        assertThat(repository.triggers).containsExactly(SyncTrigger.APP_OPEN, SyncTrigger.APP_OPEN)
        assertThat(visibility.isInForeground()).isTrue()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aRotationIsNotAnAppOpen() = runTest(UnconfinedTestDispatcher()) {
        val repository = RecordingSyncRepository()
        val foreground = AppForeground().apply { install(application) }
        SyncBootstrap(repository, backgroundScope, foreground).start()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()

        activity.recreate()

        assertThat(repository.triggers).containsExactly(SyncTrigger.APP_OPEN)
        assertThat(foreground.isInForeground).isTrue()
    }
}
