package com.sleepsaver.feature.settings.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import com.sleepsaver.feature.settings.NotificationAccess
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** The bits of the device the settings screen reads, behind an interface so the ViewModel stays testable. */
interface SettingsPlatform {
    /** "1.0.0 (1)". Empty when it can't be read. */
    fun appVersion(): String

    /** The installed app's display name, or null when it isn't installed (or isn't visible to us). */
    fun appLabel(packageName: String): String?

    fun notificationAccess(): NotificationAccess
}

class AndroidSettingsPlatform @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsPlatform {

    private val packageManager: PackageManager get() = context.packageManager

    override fun appVersion(): String = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, 0)
        }
        val name = info.versionName.orEmpty()
        val code = PackageInfoCompat.getLongVersionCode(info)
        if (name.isEmpty()) code.toString() else "$name ($code)"
    } catch (_: PackageManager.NameNotFoundException) {
        ""
    }

    override fun appLabel(packageName: String): String? = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
        packageManager.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    override fun notificationAccess(): NotificationAccess {
        val permissionMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        return when {
            permissionMissing -> NotificationAccess.PERMISSION_NEEDED
            !NotificationManagerCompat.from(context).areNotificationsEnabled() -> NotificationAccess.BLOCKED
            else -> NotificationAccess.ALLOWED
        }
    }
}

@Module
@InstallIn(ViewModelComponent::class)
abstract class SettingsPlatformModule {
    @Binds
    abstract fun bindSettingsPlatform(impl: AndroidSettingsPlatform): SettingsPlatform
}
