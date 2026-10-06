package com.sleepsaver.feature.habits

import android.Manifest
import android.app.NotificationManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The permission prompt and the settings fallback behind reminders (Android 13+ rules, SDK 36). */
@RunWith(AndroidJUnit4::class)
class NotificationAccessTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val activity get() = compose.activity

    private fun setUp(): () -> Boolean {
        lateinit var access: NotificationAccess
        compose.setContent {
            access = rememberNotificationAccess()
            Column {
                Button(onClick = access::turnOn) { Text("turn on") }
                Button(onClick = access::onReminderTurnedOn) { Text("reminder on") }
            }
        }
        return { access.allowed }
    }

    private fun blockInSettings() {
        val manager = activity.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(false)
    }

    @Test
    fun `switching a reminder on asks for the permission when it's missing`() {
        blockInSettings()
        val allowed = setUp()

        compose.onNodeWithText("reminder on").performClick()

        val requested = shadowOf(activity).lastRequestedPermission
        assertThat(requested.requestedPermissions.toList()).containsExactly(Manifest.permission.POST_NOTIFICATIONS)
        assertThat(shadowOf(activity).nextStartedActivity).isNull()
        assertThat(allowed()).isFalse()
    }

    @Test
    fun `a reminder needs no prompt once notifications are allowed`() {
        shadowOf(activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val allowed = setUp()

        compose.onNodeWithText("reminder on").performClick()

        assertThat(allowed()).isTrue()
        assertThat(shadowOf(activity).lastRequestedPermission).isNull()
    }

    @Test
    fun `the notice opens the app's notification settings when the permission is there but blocked`() {
        shadowOf(activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        blockInSettings()
        setUp()

        compose.onNodeWithText("turn on").performClick()

        val started = shadowOf(activity).nextStartedActivity
        assertThat(started.action).isEqualTo(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        assertThat(started.getStringExtra(Settings.EXTRA_APP_PACKAGE)).isEqualTo(activity.packageName)
    }

    @Test
    fun `the notice asks for the permission first while Android still shows the prompt`() {
        blockInSettings()
        setUp()

        compose.onNodeWithText("turn on").performClick()

        assertThat(shadowOf(activity).lastRequestedPermission.requestedPermissions.toList())
            .containsExactly(Manifest.permission.POST_NOTIFICATIONS)
    }
}
