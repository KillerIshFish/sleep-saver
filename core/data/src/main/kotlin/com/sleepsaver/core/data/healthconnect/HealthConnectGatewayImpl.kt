package com.sleepsaver.core.data.healthconnect

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.time.TimeRangeFilter
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.domain.config.SleepConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthConnectGatewayImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clientProvider: HealthConnectClientProvider,
    private val timeProvider: TimeProvider,
) : HealthConnectGateway {

    override fun status(): HealthConnectStatus = when (clientProvider.sdkStatus()) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectStatus.AVAILABLE
        // Below Android 14 the library also reports a missing app as "update required".
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !isInstalled(HEALTH_CONNECT_PACKAGE)) {
                HealthConnectStatus.NOT_INSTALLED
            } else {
                HealthConnectStatus.UPDATE_REQUIRED
            }
        else ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                HealthConnectStatus.NOT_INSTALLED
            } else {
                HealthConnectStatus.NOT_SUPPORTED
            }
    }

    override suspend fun permissionsToRequest(): Set<String> {
        val features = clientProvider.get()?.let { client -> catchingOrNull { client.features } }
        return buildSet {
            add(HealthConnectPermissions.SLEEP)
            addAll(HealthConnectPermissions.OPTIONAL)
            if (features.isAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)) {
                add(HealthConnectPermissions.HISTORY)
            }
            if (features.isAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)) {
                add(HealthConnectPermissions.BACKGROUND)
            }
        }
    }

    override suspend fun permissionState(): HealthPermissionState {
        val client = clientProvider.get() ?: return NOTHING_GRANTED
        val granted = catchingOrNull { client.permissionController.getGrantedPermissions() } ?: emptySet()
        val features = catchingOrNull { client.features }
        return HealthPermissionState(
            granted = granted,
            sleepGranted = HealthConnectPermissions.SLEEP in granted,
            optionalGranted = HealthConnectPermissions.OPTIONAL intersect granted,
            historyGranted = HealthConnectPermissions.HISTORY in granted,
            backgroundGranted = HealthConnectPermissions.BACKGROUND in granted,
            historyFeatureAvailable = features.isAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY),
            backgroundFeatureAvailable = features.isAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND),
        )
    }

    override fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    override fun installOrUpdateIntent(): Intent = storeIntent(
        packageName = HEALTH_CONNECT_PACKAGE,
        marketUri = "market://details?id=$HEALTH_CONNECT_PACKAGE&url=healthconnect%3A%2F%2Fonboarding",
    )

    override fun manageDataIntent(): Intent =
        catchingOrNull { HealthConnectClient.getHealthConnectManageDataIntent(context) }
            ?: Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)

    override fun isGarminConnectInstalled(): Boolean = isInstalled(SleepConfig.GARMIN_CONNECT_PACKAGE)

    override fun garminConnectLaunchIntent(): Intent? =
        catchingOrNull { context.packageManager.getLaunchIntentForPackage(SleepConfig.GARMIN_CONNECT_PACKAGE) }

    override fun garminConnectStoreIntent(): Intent = storeIntent(
        packageName = SleepConfig.GARMIN_CONNECT_PACKAGE,
        marketUri = "market://details?id=${SleepConfig.GARMIN_CONNECT_PACKAGE}",
    )

    override suspend fun sleepOrigins(lookbackDays: Long): List<String> {
        val client = clientProvider.get() ?: return emptyList()
        val now = timeProvider.now()
        val filter = TimeRangeFilter.between(now.minus(Duration.ofDays(lookbackDays)), now)
        val records = catchingOrNull { client.readAllRecords(SleepSessionRecord::class, filter) } ?: return emptyList()
        return records.map { it.metadata.dataOrigin.packageName }.filter { it.isNotEmpty() }.distinct().sorted()
    }

    /** The Play Store app when it can handle [marketUri], otherwise the Play web page. */
    private fun storeIntent(packageName: String, marketUri: String): Intent {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse(marketUri))
            .setPackage(PLAY_STORE_PACKAGE)
            .putExtra("overlay", true)
            .putExtra("callerId", context.packageName)
        val resolvable = catchingOrNull { market.resolveActivity(context.packageManager) } != null
        return if (resolvable) {
            market
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
        }
    }

    @Suppress("DEPRECATION") // The PackageInfoFlags overload needs API 33.
    private fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun HealthConnectFeatures?.isAvailable(feature: Int): Boolean =
        this != null && catchingOrNull { getFeatureStatus(feature) } == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    companion object {
        const val HEALTH_CONNECT_PACKAGE = HealthConnectClientProvider.PROVIDER_PACKAGE
        private const val PLAY_STORE_PACKAGE = "com.android.vending"

        private val NOTHING_GRANTED = HealthPermissionState(
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
