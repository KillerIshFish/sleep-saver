package com.sleepsaver.feature.onboarding.connect

import com.sleepsaver.core.data.healthconnect.HealthConnectStatus
import com.sleepsaver.core.data.healthconnect.HealthPermissionState
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.SourceSelection

/** Where the user is in connecting Health Connect. Decides what the Connect step and the fix-it screen show. */
enum class ConnectStage {
    /** First check still running. */
    CHECKING,

    /** Android 9–13 without the Health Connect app. */
    NOT_INSTALLED,

    /** Installed, but too old. */
    UPDATE_REQUIRED,

    /** This device can't run Health Connect: only demo data is possible. */
    NOT_SUPPORTED,

    /** Health Connect is ready but sleep hasn't been allowed (and we haven't asked yet). */
    NEEDS_PERMISSION,

    /** We asked and sleep is still not allowed. */
    DENIED,

    /** Sleep is allowed. */
    CONNECTED,
}

/** Whether the selected sleep source has actually written sleep to Health Connect recently. */
sealed interface SourceCheck {
    /** Can't look without the sleep permission. */
    data object NotChecked : SourceCheck

    data object Checking : SourceCheck

    /** The selected source (any app, for "all sources") wrote sleep in the last 30 days. */
    data class Sharing(val selection: SourceSelection) : SourceCheck

    /** Nothing from the selected source; [alternatives] are other apps that did write sleep. */
    data class Missing(val selection: SourceSelection, val alternatives: List<String>) : SourceCheck
}

/** Everything known about the Health Connect connection, as shown by the Connect step and the fix-it screen. */
data class ConnectionUiState(
    /** null until the first check finishes. */
    val status: HealthConnectStatus? = null,
    /** null until checked (or when Health Connect isn't available). */
    val permissions: HealthPermissionState? = null,
    /** True once a permission request came back in this session: a refusal is then shown as "denied". */
    val permissionAsked: Boolean = false,
    val source: SourceCheck = SourceCheck.NotChecked,
    val garminInstalled: Boolean = true,
    val demoMode: Boolean = false,
) {
    val stage: ConnectStage
        get() = when (status) {
            null -> ConnectStage.CHECKING
            HealthConnectStatus.NOT_INSTALLED -> ConnectStage.NOT_INSTALLED
            HealthConnectStatus.UPDATE_REQUIRED -> ConnectStage.UPDATE_REQUIRED
            HealthConnectStatus.NOT_SUPPORTED -> ConnectStage.NOT_SUPPORTED
            HealthConnectStatus.AVAILABLE -> when {
                permissions == null -> ConnectStage.CHECKING
                permissions.sleepGranted -> ConnectStage.CONNECTED
                permissionAsked -> ConnectStage.DENIED
                else -> ConnectStage.NEEDS_PERMISSION
            }
        }

    val isConnected: Boolean get() = stage == ConnectStage.CONNECTED
}

/** Optional data, as one row: granted, refused, or not offered by this phone's Health Connect. */
enum class Grant { GRANTED, NOT_GRANTED, UNAVAILABLE }

val HealthPermissionState.heartData: Grant
    get() = if (optionalGranted.isNotEmpty()) Grant.GRANTED else Grant.NOT_GRANTED

val HealthPermissionState.history: Grant
    get() = when {
        !historyFeatureAvailable -> Grant.UNAVAILABLE
        historyGranted -> Grant.GRANTED
        else -> Grant.NOT_GRANTED
    }

val HealthPermissionState.background: Grant
    get() = when {
        !backgroundFeatureAvailable -> Grant.UNAVAILABLE
        backgroundGranted -> Grant.GRANTED
        else -> Grant.NOT_GRANTED
    }

/**
 * Compares the apps that wrote sleep ([origins]) with the user's [selection]. The demo origin never counts:
 * it isn't a Health Connect app.
 */
fun sourceCheck(origins: List<String>, selection: SourceSelection): SourceCheck {
    val real = origins.filter { it.isNotBlank() && it != SleepConfig.DEMO_ORIGIN }.distinct()
    val sharing = when (selection) {
        SourceSelection.All -> real.isNotEmpty()
        is SourceSelection.Single -> selection.packageName in real
    }
    if (sharing) return SourceCheck.Sharing(selection)
    val alternatives = when (selection) {
        SourceSelection.All -> emptyList()
        is SourceSelection.Single -> real.filter { it != selection.packageName }
    }
    // Garmin first (it's what the app is built around), then alphabetical.
    return SourceCheck.Missing(
        selection = selection,
        alternatives = alternatives.sortedWith(compareBy({ it != SleepConfig.GARMIN_CONNECT_PACKAGE }, { it })),
    )
}
