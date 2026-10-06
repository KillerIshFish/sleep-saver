package com.sleepsaver.feature.settings.platform

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.feature.settings.NotificationAccess
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class AndroidSettingsPlatformTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val platform = AndroidSettingsPlatform(app)

    @Test
    fun `app labels come from the package manager`() {
        val info = ApplicationInfo().apply {
            packageName = "com.ouraring.oura"
            nonLocalizedLabel = "Oura"
        }
        shadowOf(app.packageManager).installPackage(
            PackageInfo().apply {
                packageName = "com.ouraring.oura"
                applicationInfo = info
            },
        )

        assertThat(platform.appLabel("com.ouraring.oura")).isEqualTo("Oura")
        assertThat(platform.appLabel("com.example.not.installed")).isNull()
    }

    @Test
    fun `version is read for this app`() {
        assertThat(platform.appVersion()).isNotNull()
    }

    @Test
    fun `notification access follows the permission and the system switch`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertThat(platform.notificationAccess()).isEqualTo(NotificationAccess.PERMISSION_NEEDED)

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(manager).setNotificationsEnabled(false)
        assertThat(platform.notificationAccess()).isEqualTo(NotificationAccess.BLOCKED)

        shadowOf(manager).setNotificationsEnabled(true)
        assertThat(platform.notificationAccess()).isEqualTo(NotificationAccess.ALLOWED)
    }
}
