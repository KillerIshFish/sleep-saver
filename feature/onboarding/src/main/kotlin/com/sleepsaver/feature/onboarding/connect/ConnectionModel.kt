package com.sleepsaver.feature.onboarding.connect

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.sleepsaver.core.data.healthconnect.HealthConnectGateway
import com.sleepsaver.core.data.healthconnect.HealthConnectStatus
import com.sleepsaver.core.data.healthconnect.HealthPermissionState
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.domain.model.SourceSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Checks Health Connect for the Connect step and the fix-it screen: status, permissions, and whether the selected
 * source (Garmin Connect by default) has written sleep. Every gateway call is guarded, so a missing or broken
 * Health Connect shows up as a state, never as a crash. Lives in a ViewModel's [scope].
 */
internal class ConnectionModel(
    private val gateway: HealthConnectGateway,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private data class Checked(
        val status: HealthConnectStatus? = null,
        val permissions: HealthPermissionState? = null,
        /** null until read (or without the sleep permission). */
        val origins: List<String>? = null,
        val checkingOrigins: Boolean = false,
        val garminInstalled: Boolean = true,
    )

    private val checked = MutableStateFlow(Checked())
    private val asked = MutableStateFlow(false)
    private var refreshJob: Job? = null

    val state: StateFlow<ConnectionUiState> = combine(
        checked,
        asked,
        settingsRepository.settings.catch { emit(AppSettings()) },
    ) { checked, asked, settings ->
        ConnectionUiState(
            status = checked.status,
            permissions = checked.permissions,
            permissionAsked = asked,
            source = when {
                checked.permissions?.sleepGranted != true -> SourceCheck.NotChecked
                checked.origins == null || checked.checkingOrigins -> SourceCheck.Checking
                else -> sourceCheck(checked.origins, settings.sleep.source)
            },
            garminInstalled = checked.garminInstalled,
            demoMode = settings.demoMode,
        )
    }.stateIn(scope, SharingStarted.Eagerly, ConnectionUiState())

    /** Cached: the launcher only needs one. */
    val permissionContract: ActivityResultContract<Set<String>, Set<String>> by lazy { gateway.permissionContract() }

    /**
     * Re-reads everything. Runs on start and on every resume (the user may have just installed Health Connect or
     * changed a permission). [showProgress] puts the source check back into "checking" for a visible "Check again".
     */
    fun refresh(showProgress: Boolean = false): Job {
        refreshJob?.cancel()
        return scope.launch {
            val status = guarded(HealthConnectStatus.NOT_INSTALLED) { gateway.status() }
            val garminInstalled = guarded(false) { gateway.isGarminConnectInstalled() }
            if (status != HealthConnectStatus.AVAILABLE) {
                checked.value = Checked(status = status, garminInstalled = garminInstalled)
                return@launch
            }
            val permissions = guarded(NOTHING_GRANTED) { gateway.permissionState() }
            checked.update {
                it.copy(
                    status = status,
                    permissions = permissions,
                    garminInstalled = garminInstalled,
                    origins = if (permissions.sleepGranted) it.origins else null,
                    checkingOrigins = permissions.sleepGranted && showProgress,
                )
            }
            if (!permissions.sleepGranted) return@launch
            val origins = guarded(emptyList()) { gateway.sleepOrigins() }
            checked.update { it.copy(origins = origins, checkingOrigins = false) }
        }.also { refreshJob = it }
    }

    /** As of the last finished check. */
    val sleepGranted: Boolean get() = checked.value.permissions?.sleepGranted == true

    /** The permission screen closed. Whatever was granted, re-read the real state. */
    fun onPermissionsResult(): Job {
        asked.value = true
        return refresh(showProgress = true)
    }

    /** Health Connect couldn't show its permission screen at all. */
    fun onPermissionsUnavailable(): Job = onPermissionsResult()

    suspend fun permissionsToRequest(): Set<String> = guarded(emptySet()) { gateway.permissionsToRequest() }

    fun useSource(selection: SourceSelection) {
        scope.launch { guarded(Unit) { settingsRepository.setSource(selection) } }
    }

    fun installOrUpdateIntent(): Intent? = guarded(null) { gateway.installOrUpdateIntent() }

    fun manageDataIntent(): Intent? = guarded(null) { gateway.manageDataIntent() }

    /** Garmin Connect itself when installed, otherwise its store page. */
    fun garminConnectIntent(): Intent? =
        guarded(null) { gateway.garminConnectLaunchIntent() } ?: guarded(null) { gateway.garminConnectStoreIntent() }

    private companion object {
        val NOTHING_GRANTED = HealthPermissionState(
            granted = emptySet(),
            sleepGranted = false,
            optionalGranted = emptySet(),
            historyGranted = false,
            backgroundGranted = false,
            historyFeatureAvailable = false,
            backgroundFeatureAvailable = false,
        )
    }
}

/** Runs [block], turning any failure except cancellation into [default]. */
internal inline fun <T> guarded(default: T, block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    default
}
