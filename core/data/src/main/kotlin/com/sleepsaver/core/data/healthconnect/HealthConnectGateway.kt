package com.sleepsaver.core.data.healthconnect

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

enum class HealthConnectStatus {
    AVAILABLE,

    /** Android 9–13 without the Health Connect app. */
    NOT_INSTALLED,

    /** Installed but the provider needs an update. */
    UPDATE_REQUIRED,

    /** Device can't run Health Connect at all. */
    NOT_SUPPORTED,
}

data class HealthPermissionState(
    val granted: Set<String>,
    /** READ_SLEEP: the app is useless without it. */
    val sleepGranted: Boolean,
    /** HR, resting HR, HRV, steps: optional. */
    val optionalGranted: Set<String>,
    val historyGranted: Boolean,
    val backgroundGranted: Boolean,
    /** Whether the device's Health Connect supports the feature at all (HealthConnectFeatures). */
    val historyFeatureAvailable: Boolean,
    val backgroundFeatureAvailable: Boolean,
)

/** Everything Health-Connect-specific the UI needs: status, permissions, intents. */
interface HealthConnectGateway {
    fun status(): HealthConnectStatus

    /** All permissions we ask for (sleep + optional + history/background when the feature is available). */
    suspend fun permissionsToRequest(): Set<String>

    /** Never throws; returns "nothing granted" when Health Connect is unavailable. */
    suspend fun permissionState(): HealthPermissionState

    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>>

    /** Play Store page for the Health Connect app (install or update). */
    fun installOrUpdateIntent(): Intent

    /** Health Connect's own settings / data screen (to review permissions or app sync). */
    fun manageDataIntent(): Intent

    fun isGarminConnectInstalled(): Boolean

    /** Opens Garmin Connect so the watch syncs. null when Garmin Connect isn't installed. */
    fun garminConnectLaunchIntent(): Intent?

    /** Play Store page for Garmin Connect. */
    fun garminConnectStoreIntent(): Intent

    /** Packages that wrote sleep sessions in the last [lookbackDays] (live read, not the cache). Empty on error. */
    suspend fun sleepOrigins(lookbackDays: Long = 30): List<String>
}
