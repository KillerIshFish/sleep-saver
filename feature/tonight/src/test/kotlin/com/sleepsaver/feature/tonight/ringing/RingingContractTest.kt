package com.sleepsaver.feature.tonight.ringing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.hooks.AlarmIntents
import com.sleepsaver.core.ui.platform.AlarmBlocker
import com.sleepsaver.feature.tonight.AlarmGate
import com.sleepsaver.feature.tonight.RING_CHANNEL
import com.sleepsaver.feature.tonight.rememberAlarmGate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The names core:alarm relies on: the ringing Activity's class and manifest entry, and the alarm channel. */
@RunWith(AndroidJUnit4::class)
class RingingContractTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the ringing activity is where core alarm launches it`() {
        assertThat(RingingActivity::class.java.name).isEqualTo(AlarmIntents.RINGING_ACTIVITY)
    }

    @Test
    fun `the ringing activity is private, shows over the lock screen and works before unlock`() {
        val activity = compose.activity
        @Suppress("DEPRECATION")
        val info = activity.packageManager.getActivityInfo(
            ComponentName(activity.packageName, AlarmIntents.RINGING_ACTIVITY),
            PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
        )
        assertThat(info.exported).isFalse()
        assertThat(info.directBootAware).isTrue()
        assertThat(info.launchMode).isEqualTo(ActivityInfo.LAUNCH_SINGLE_INSTANCE)
        assertThat(info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS).isNotEqualTo(0)
        // No own theme: it inherits the application's.
        assertThat(info.theme).isEqualTo(0)
    }

    @Test
    fun `features check the alarm ring channel by default`() {
        assertThat(RING_CHANNEL).isEqualTo(AlarmIntents.CHANNEL_RING)

        val notifications = compose.activity.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(AlarmIntents.CHANNEL_RING, "Alarm", NotificationManager.IMPORTANCE_NONE))
        lateinit var gate: AlarmGate
        compose.setContent { gate = rememberAlarmGate() }
        compose.waitForIdle()

        assertThat(gate.access.blockers).contains(AlarmBlocker.RING_CHANNEL_OFF)
    }
}
