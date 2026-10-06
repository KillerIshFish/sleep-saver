package com.sleepsaver.feature.habits

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Whether reminders can actually be shown, plus the two ways to fix it: the Android 13+ runtime permission
 * and the system's notification settings. [allowed] is re-checked whenever the screen resumes, so it
 * follows changes made in system settings.
 */
@Stable
internal class NotificationAccess(
    private val allowedState: MutableState<Boolean>,
    private val requestPermission: () -> Unit,
    private val openSettingsAction: () -> Unit,
) {
    val allowed: Boolean get() = allowedState.value

    /** Call after a reminder was switched on: asks for POST_NOTIFICATIONS when it's missing (Android 13+). */
    fun onReminderTurnedOn() {
        if (!allowed) requestPermission()
    }

    fun openSettings() = openSettingsAction()
}

@Composable
internal fun rememberNotificationAccess(): NotificationAccess {
    val context = LocalContext.current
    val allowed = remember { mutableStateOf(notificationsAllowed(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed.value = notificationsAllowed(context) }
    // Denied: `allowed` stays false and the screens show the "notifications are off" notice with a settings link.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed.value = notificationsAllowed(context)
    }
    return remember(context, launcher) {
        NotificationAccess(
            allowedState = allowed,
            requestPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            openSettingsAction = { openNotificationSettings(context) },
        )
    }
}

/** False when the permission is missing (Android 13+) or the user blocked the app's notifications. */
private fun notificationsAllowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

/** The app's notification settings page; the app-info page where that isn't available. */
private fun openNotificationSettings(context: Context) {
    val notificationSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    for (intent in listOf(notificationSettings, appDetails)) {
        try {
            context.startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            Log.w(LOG_TAG, "No activity for ${intent.action}", e)
        }
    }
}
