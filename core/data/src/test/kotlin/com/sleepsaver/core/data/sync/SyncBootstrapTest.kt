package com.sleepsaver.core.data.sync

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
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
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class SyncBootstrapTest {

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
        val bootstrap = SyncBootstrap(repository, backgroundScope)

        bootstrap.start(owner.registry)
        bootstrap.start(owner.registry) // idempotent
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        assertThat(repository.triggers).containsExactly(SyncTrigger.APP_OPEN, SyncTrigger.APP_OPEN)
        assertThat(repository.periodic).isEqualTo(2)
    }
}
