package com.sleepsaver.core.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands out the [HealthConnectClient], created lazily and only once Health Connect reports
 * [HealthConnectClient.SDK_AVAILABLE] (it can be installed or updated while the app runs). Never throws.
 */
@Singleton
open class HealthConnectClientProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var client: HealthConnectClient? = null

    /** One of the `HealthConnectClient.SDK_*` constants; SDK_UNAVAILABLE when the check itself fails. */
    open fun sdkStatus(): Int = catchingOrNull { HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE) }
        ?: HealthConnectClient.SDK_UNAVAILABLE

    /** The client, or null while Health Connect isn't available. */
    open fun get(): HealthConnectClient? {
        client?.let { return it }
        if (sdkStatus() != HealthConnectClient.SDK_AVAILABLE) return null
        return synchronized(this) {
            client ?: catchingOrNull { HealthConnectClient.getOrCreate(context, PROVIDER_PACKAGE) }?.also { client = it }
        }
    }

    companion object {
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
    }
}
