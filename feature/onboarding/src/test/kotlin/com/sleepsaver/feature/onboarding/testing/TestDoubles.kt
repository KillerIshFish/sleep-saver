package com.sleepsaver.feature.onboarding.testing

import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.SyncRepository
import com.sleepsaver.core.data.repository.SyncResult
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.data.repository.SyncStatus
import com.sleepsaver.core.data.repository.SyncTrigger
import com.sleepsaver.core.testing.SampleData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId

class FakeTimeProvider(var now: Instant = SampleData.now, var zone: ZoneId = SampleData.zone) : TimeProvider {
    override fun now(): Instant = now
    override fun zone(): ZoneId = zone
    override val minuteTicks: Flow<Instant> get() = flowOf(now)
}

/** A sync repository whose import waits for [release], reporting progress like the real one. */
class GatedSyncRepository : SyncRepository {
    private val gate = CompletableDeferred<SyncResult>()
    private val mutableState = MutableStateFlow(SyncState())
    var importCalls = 0
    override val state: StateFlow<SyncState> = mutableState.asStateFlow()

    fun progress(value: Float) = mutableState.update { it.copy(status = SyncStatus.Importing(value)) }

    fun release(result: SyncResult = SyncResult(success = true)) {
        mutableState.update { it.copy(status = SyncStatus.Idle) }
        gate.complete(result)
    }

    override suspend fun sync(trigger: SyncTrigger): SyncResult = SyncResult(success = true)
    override suspend fun importHistory(): SyncResult {
        importCalls++
        mutableState.update { it.copy(status = SyncStatus.Importing(0f)) }
        return gate.await()
    }
    override fun ensurePeriodicSync() = Unit
    override fun startWaitingRetries() = Unit
    override suspend fun resetSyncState() = Unit
}
