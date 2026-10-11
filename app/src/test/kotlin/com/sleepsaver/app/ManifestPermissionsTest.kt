package com.sleepsaver.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.hooks.AlarmIntents
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The merged manifest's permissions (DECISIONS C, SPEC_V2 §5.6): no network, microphone or activity recognition, and
 * everything the alarm needs. A plain Application stands in for the Hilt one: only the manifest is read here.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ManifestPermissionsTest {

    private val requested: List<String> by lazy {
        val context = ApplicationProvider.getApplicationContext<Application>()
        @Suppress("DEPRECATION") // getPackageInfo(String, PackageInfoFlags) needs API 33; minSdk is 28
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.toList().orEmpty()
    }

    @Test
    fun `no network, microphone or activity recognition`() {
        assertThat(requested).containsNoneOf(
            Manifest.permission.INTERNET,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
    }

    @Test
    fun `everything the alarm and the night service need`() {
        assertThat(requested).containsAtLeast(
            Manifest.permission.USE_EXACT_ALARM,
            Manifest.permission.USE_FULL_SCREEN_INTENT,
            Manifest.permission.FOREGROUND_SERVICE_HEALTH,
            Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK,
            Manifest.permission.HIGH_SAMPLING_RATE_SENSORS,
        )
    }

    @Test
    fun `the ringing screen is merged in, not exported, and works before the first unlock`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val ringing = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
            .activities.orEmpty()
            .single { it.name == AlarmIntents.RINGING_ACTIVITY }

        assertThat(ringing.exported).isFalse()
        assertThat(ringing.directBootAware).isTrue()
    }
}
