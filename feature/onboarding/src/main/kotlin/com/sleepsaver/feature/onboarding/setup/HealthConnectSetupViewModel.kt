package com.sleepsaver.feature.onboarding.setup

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.healthconnect.HealthConnectGateway
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.DemoModeController
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SyncRepository
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.data.repository.SyncTrigger
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.feature.onboarding.connect.ConnectionModel
import com.sleepsaver.feature.onboarding.connect.ConnectionUiState
import com.sleepsaver.feature.onboarding.connect.guarded
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

data class HealthConnectSetupUiState(
    val connection: ConnectionUiState = ConnectionUiState(),
    val sync: SyncState = SyncState(),
    /** For "12 min ago". */
    val now: Instant = Instant.EPOCH,
    val zone: ZoneId = ZoneId.of("UTC"),
    /** Switching demo mode: the buttons that would race with it are disabled. */
    val busy: Boolean = false,
)

/** The Health Connect fix-it screen (from home or settings): every part of the connection, each with its fix. */
@HiltViewModel
class HealthConnectSetupViewModel @Inject constructor(
    gateway: HealthConnectGateway,
    settingsRepository: SettingsRepository,
    private val syncRepository: SyncRepository,
    private val demoModeController: DemoModeController,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val connection = ConnectionModel(gateway, settingsRepository, viewModelScope)
    private val busy = MutableStateFlow(false)
    private var syncJob: Job? = null
    private val now = MutableStateFlow(currentTime())
    private val zone: ZoneId = guarded(ZoneId.systemDefault()) { timeProvider.zone() }

    val uiState: StateFlow<HealthConnectSetupUiState> = combine(
        connection.state,
        syncRepository.state.catch { emit(SyncState()) },
        now,
        busy,
    ) { connection, sync, now, busy ->
        HealthConnectSetupUiState(connection = connection, sync = sync, now = now, zone = zone, busy = busy)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HealthConnectSetupUiState(now = now.value, zone = zone))

    val permissionContract: ActivityResultContract<Set<String>, Set<String>> get() = connection.permissionContract

    init {
        connection.refresh()
    }

    /** On every resume: the user may be back from the Play Store, Health Connect or Garmin Connect. */
    fun refresh() {
        now.value = currentTime()
        connection.refresh()
    }

    fun checkAgain() {
        connection.refresh(showProgress = true)
    }

    suspend fun permissionsToRequest(): Set<String> = connection.permissionsToRequest()

    fun onPermissionsResult() {
        connection.onPermissionsResult()
        // A newly granted permission (sleep, history) is worth a sync straight away. The resume that follows the
        // permission screen restarts the check, so wait for the newest one rather than the job started here.
        viewModelScope.launch {
            connection.awaitLatestCheck()
            if (connection.sleepGranted) syncNow()
        }
    }

    fun onPermissionsUnavailable() {
        connection.onPermissionsUnavailable()
    }

    fun useSource(selection: SourceSelection) = connection.useSource(selection)

    fun installOrUpdateIntent(): Intent? = connection.installOrUpdateIntent()

    fun manageDataIntent(): Intent? = connection.manageDataIntent()

    /** Garmin Connect, then its store page as the fallback. */
    fun garminConnectIntents(): List<Intent> = connection.garminConnectIntents()

    /** A manual sync, then a fresh look at the connection. A second tap while one runs is ignored. */
    fun syncNow() {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            guarded(null) { syncRepository.sync(SyncTrigger.MANUAL) }
            now.value = currentTime()
            connection.refresh()
        }
    }

    fun exploreDemo() = switchDemo(enabled = true)

    /** Back to the user's own Health Connect nights. */
    fun leaveDemo() = switchDemo(enabled = false)

    private fun switchDemo(enabled: Boolean) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            guarded(Unit) { demoModeController.setDemoMode(enabled) }
            busy.update { false }
            if (!enabled) syncNow() else connection.refresh()
        }
    }

    private fun currentTime(): Instant = guarded(Instant.now()) { timeProvider.now() }
}
