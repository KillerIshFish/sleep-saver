package com.sleepsaver.core.ui.platform

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Each Alarm check against Robolectric's shadows (SDK 36), the blocker order, the resume re-check, the notification
 * request and the settings page each fix opens.
 */
@RunWith(AndroidJUnit4::class)
class AlarmAccessTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val activity get() = compose.activity
    private val notifications get() = activity.getSystemService(NotificationManager::class.java)
    private val audio get() = activity.getSystemService(AudioManager::class.java)

    /** A phone set up for alarms, as far as the shadows go. */
    @Before
    fun healthyPhone() {
        shadowOf(notifications).setNotificationsEnabled(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        shadowOf(activity.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(activity.packageName, true)
    }

    private fun passed(blocker: AlarmBlocker): Boolean =
        readAlarmChecks(activity, CHANNEL).single { it.blocker == blocker }.passed

    // ---------------------------------------------------------------- Checks

    @Test
    fun `checks come in severity order`() {
        assertThat(readAlarmChecks(activity, CHANNEL).map { it.blocker }).containsExactlyElementsIn(AlarmBlocker.entries).inOrder()
        assertThat(AlarmBlocker.entries.filterNot { it.blocking })
            .containsExactly(AlarmBlocker.BATTERY_OPTIMIZED, AlarmBlocker.BLUETOOTH_OUTPUT)
    }

    @Test
    fun `notifications off`() {
        assertThat(passed(AlarmBlocker.NOTIFICATIONS_OFF)).isTrue()
        shadowOf(notifications).setNotificationsEnabled(false)
        assertThat(passed(AlarmBlocker.NOTIFICATIONS_OFF)).isFalse()
    }

    @Test
    fun `the alarm category switched off`() {
        // Not created yet (core:alarm creates it at startup): nothing to complain about.
        assertThat(passed(AlarmBlocker.RING_CHANNEL_OFF)).isTrue()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Alarm", NotificationManager.IMPORTANCE_HIGH))
        assertThat(passed(AlarmBlocker.RING_CHANNEL_OFF)).isTrue()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Alarm", NotificationManager.IMPORTANCE_NONE))
        assertThat(passed(AlarmBlocker.RING_CHANNEL_OFF)).isFalse()
        // Another channel being off doesn't matter.
        assertThat(readAlarmChecks(activity, "night").single { it.blocker == AlarmBlocker.RING_CHANNEL_OFF }.passed).isTrue()
    }

    @Test
    fun `exact alarms not allowed`() {
        assertThat(passed(AlarmBlocker.EXACT_ALARMS_OFF)).isTrue()
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertThat(passed(AlarmBlocker.EXACT_ALARMS_OFF)).isFalse()
    }

    @Test
    fun `full-screen alarms follow the platform's answer`() {
        // Robolectric has no shadow for canUseFullScreenIntent (Android 14+): the check must simply mirror it.
        val platform = runCatching { notifications.canUseFullScreenIntent() }.getOrDefault(true)
        assertThat(passed(AlarmBlocker.FULL_SCREEN_OFF)).isEqualTo(platform)
    }

    @Test
    fun `battery use restricted`() {
        assertThat(passed(AlarmBlocker.BACKGROUND_RESTRICTED)).isTrue()
        shadowOf(activity.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(true)
        assertThat(passed(AlarmBlocker.BACKGROUND_RESTRICTED)).isFalse()
    }

    @Test
    fun `alarm volume at zero`() {
        assertThat(passed(AlarmBlocker.ALARM_VOLUME_ZERO)).isTrue()
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)
        assertThat(passed(AlarmBlocker.ALARM_VOLUME_ZERO)).isFalse()
        // Media at zero is not the alarm's problem.
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 3, 0)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        assertThat(passed(AlarmBlocker.ALARM_VOLUME_ZERO)).isTrue()
    }

    @Test
    fun `do not disturb on total silence, but not alarms only`() {
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        assertThat(passed(AlarmBlocker.TOTAL_SILENCE)).isTrue()
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        assertThat(passed(AlarmBlocker.TOTAL_SILENCE)).isFalse()
    }

    @Test
    fun `battery optimisation is information only`() {
        assertThat(passed(AlarmBlocker.BATTERY_OPTIMIZED)).isTrue()
        shadowOf(activity.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(activity.packageName, false)
        assertThat(passed(AlarmBlocker.BATTERY_OPTIMIZED)).isFalse()
    }

    @Test
    fun `a bluetooth speaker or headset`() {
        shadowOf(audio).setOutputDevices(listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()))
        assertThat(passed(AlarmBlocker.BLUETOOTH_OUTPUT)).isTrue()
        shadowOf(audio).setOutputDevices(
            listOf(
                AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build(),
                AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build(),
            ),
        )
        assertThat(passed(AlarmBlocker.BLUETOOTH_OUTPUT)).isFalse()
        shadowOf(audio).setOutputDevices(listOf(AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLE_HEADSET).build()))
        assertThat(passed(AlarmBlocker.BLUETOOTH_OUTPUT)).isFalse()
    }

    // ---------------------------------------------------------------- Blockers

    private fun access(vararg failing: AlarmBlocker) = AlarmAccess(
        checksState = mutableStateOf(AlarmBlocker.entries.map { AlarmCheck(it, passed = it !in failing) }),
        onFix = {},
        onRequestNotifications = {},
    )

    @Test
    fun `blockers are the failing blocking checks, most severe first`() {
        val access = access(AlarmBlocker.TOTAL_SILENCE, AlarmBlocker.BATTERY_OPTIMIZED, AlarmBlocker.NOTIFICATIONS_OFF)
        assertThat(access.blockers).containsExactly(AlarmBlocker.NOTIFICATIONS_OFF, AlarmBlocker.TOTAL_SILENCE).inOrder()
        assertThat(access.ready).isFalse()
        assertThat(access.checks.filterNot { it.passed }.map { it.blocker }).hasSize(3)
    }

    @Test
    fun `information-only checks never block`() {
        assertThat(access(AlarmBlocker.BATTERY_OPTIMIZED, AlarmBlocker.BLUETOOTH_OUTPUT).ready).isTrue()
        assertThat(access().ready).isTrue()
    }

    // ---------------------------------------------------------------- In a screen

    private fun setUp(): AlarmAccess {
        lateinit var access: AlarmAccess
        compose.setContent { access = rememberAlarmAccess(CHANNEL) }
        compose.waitForIdle()
        return access
    }

    @Test
    fun `checks are read again when the screen resumes`() {
        val access = setUp()
        assertThat(access.blockers).doesNotContain(AlarmBlocker.ALARM_VOLUME_ZERO)

        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)
        assertThat(access.blockers).doesNotContain(AlarmBlocker.ALARM_VOLUME_ZERO)

        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertThat(access.blockers).contains(AlarmBlocker.ALARM_VOLUME_ZERO)
    }

    @Test
    fun `requesting notifications answers at once when they are allowed`() {
        shadowOf(activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val access = setUp()
        var result: Boolean? = null

        access.requestNotifications { result = it }

        assertThat(result).isTrue()
        assertThat(shadowOf(activity).lastRequestedPermission).isNull()
    }

    @Test
    fun `requesting notifications asks for the permission and reports the answer`() {
        shadowOf(notifications).setNotificationsEnabled(false)
        val access = setUp()
        var result: Boolean? = null

        access.requestNotifications { result = it }
        compose.waitForIdle()

        val request = shadowOf(activity).lastRequestedPermission
        assertThat(request.requestedPermissions.toList()).containsExactly(Manifest.permission.POST_NOTIFICATIONS)
        assertThat(result).isNull()

        // The user taps Allow.
        shadowOf(activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifications).setNotificationsEnabled(true)
        @Suppress("DEPRECATION")
        activity.onRequestPermissionsResult(
            request.requestCode,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )
        compose.waitForIdle()

        assertThat(result).isTrue()
        assertThat(access.blockers).doesNotContain(AlarmBlocker.NOTIFICATIONS_OFF)
        // The settings page never opens on top of the prompt.
        val started = generateSequence { shadowOf(activity).nextStartedActivity }.map { it.action }.toList()
        assertThat(started).doesNotContain(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    }

    @Test
    fun `requesting notifications fails quietly when they are blocked in settings`() {
        shadowOf(activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifications).setNotificationsEnabled(false)
        val access = setUp()
        var result: Boolean? = null

        access.requestNotifications { result = it }

        assertThat(result).isFalse()
        assertThat(shadowOf(activity).lastRequestedPermission).isNull()
        assertThat(shadowOf(activity).nextStartedActivity).isNull()
    }

    // ---------------------------------------------------------------- Fixes

    private fun fixOpens(blocker: AlarmBlocker): Intent? {
        val access = setUp()
        access.fix(blocker)
        return shadowOf(activity).nextStartedActivity
    }

    private val packageUri get() = Uri.fromParts("package", activity.packageName, null)

    @Test
    fun `the notification fix prompts while it can`() {
        shadowOf(notifications).setNotificationsEnabled(false)
        fixOpens(AlarmBlocker.NOTIFICATIONS_OFF)
        assertThat(shadowOf(activity).lastRequestedPermission.requestedPermissions.toList())
            .containsExactly(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `the notification fix opens settings when the permission is there but blocked`() {
        shadowOf(activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifications).setNotificationsEnabled(false)
        val opened = fixOpens(AlarmBlocker.NOTIFICATIONS_OFF)
        assertThat(opened?.action).isEqualTo(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    }

    @Test
    fun `the alarm category fix opens that channel's settings`() {
        val opened = fixOpens(AlarmBlocker.RING_CHANNEL_OFF)
        assertThat(opened?.action).isEqualTo(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        assertThat(opened?.getStringExtra(Settings.EXTRA_CHANNEL_ID)).isEqualTo(CHANNEL)
        assertThat(opened?.getStringExtra(Settings.EXTRA_APP_PACKAGE)).isEqualTo(activity.packageName)
    }

    @Test
    fun `the exact alarm fix opens alarms and reminders for this app`() {
        val opened = fixOpens(AlarmBlocker.EXACT_ALARMS_OFF)
        assertThat(opened?.action).isEqualTo(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        assertThat(opened?.data).isEqualTo(packageUri)
    }

    @Test
    fun `the full-screen fix opens the full-screen permission for this app`() {
        val opened = fixOpens(AlarmBlocker.FULL_SCREEN_OFF)
        assertThat(opened?.action).isEqualTo(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
        assertThat(opened?.data).isEqualTo(packageUri)
    }

    @Test
    fun `the other fixes open their settings pages`() {
        val access = setUp()
        access.fix(AlarmBlocker.BACKGROUND_RESTRICTED)
        val details = shadowOf(activity).nextStartedActivity
        assertThat(details?.action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertThat(details?.data).isEqualTo(packageUri)
        access.fix(AlarmBlocker.ALARM_VOLUME_ZERO)
        assertThat(shadowOf(activity).nextStartedActivity?.action).isEqualTo(Settings.ACTION_SOUND_SETTINGS)
        access.fix(AlarmBlocker.TOTAL_SILENCE)
        assertThat(shadowOf(activity).nextStartedActivity?.action).isEqualTo(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)
        // The list of apps, never the dialog that exempts this one directly.
        access.fix(AlarmBlocker.BATTERY_OPTIMIZED)
        assertThat(shadowOf(activity).nextStartedActivity?.action).isEqualTo(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        access.fix(AlarmBlocker.BLUETOOTH_OUTPUT)
        assertThat(shadowOf(activity).nextStartedActivity).isNull()
    }

    @Test
    fun `total silence falls back to sound settings without a Do Not Disturb page`() {
        shadowOf(activity.application).checkActivities(true)
        @Suppress("DEPRECATION") // The simplest way to make one settings page resolvable.
        shadowOf(activity.packageManager).addResolveInfoForIntent(
            Intent(Settings.ACTION_SOUND_SETTINGS),
            listOf(ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "com.android.settings"
                    name = "SoundSettings"
                }
            }),
        )
        val opened = fixOpens(AlarmBlocker.TOTAL_SILENCE)
        assertThat(opened?.action).isEqualTo(Settings.ACTION_SOUND_SETTINGS)
    }

    private companion object {
        /** core:alarm's `AlarmIntents.CHANNEL_RING`. */
        const val CHANNEL = "alarm_ring"
    }
}
