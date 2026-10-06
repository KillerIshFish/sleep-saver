package com.sleepsaver.core.ui.platform

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
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
class NotificationAccess internal constructor(
    private val allowedState: MutableState<Boolean>,
    private val request: (fromNotice: Boolean) -> Unit,
) {
    val allowed: Boolean get() = allowedState.value

    /** Call after a reminder was switched on: asks for POST_NOTIFICATIONS when it's missing (Android 13+). */
    fun onReminderTurnedOn() {
        if (!allowed) request(false)
    }

    /**
     * The "notifications are off" notice's button: asks for the permission while Android still shows the
     * prompt, otherwise (Android 12 and lower, blocked in settings, or denied for good) opens the system's
     * notification settings for the app.
     */
    fun turnOn() = request(true)
}

@Composable
fun rememberNotificationAccess(): NotificationAccess {
    val context = LocalContext.current
    val allowed = remember { mutableStateOf(notificationsAllowed(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed.value = notificationsAllowed(context) }
    // Asked from the notice: if Android no longer shows the prompt, the settings page is the only way left.
    val settingsIfRefused = rememberSaveable { mutableStateOf(false) }
    // Denied after a reminder toggle: `allowed` stays false and the screen shows the notice, which explains.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed.value = notificationsAllowed(context)
        if (!granted && settingsIfRefused.value && !canAskAgain(context)) openNotificationSettings(context)
        settingsIfRefused.value = false
    }
    return remember(context, launcher) {
        NotificationAccess(
            allowedState = allowed,
            request = { fromNotice ->
                when {
                    needsPermission(context) -> {
                        settingsIfRefused.value = fromNotice
                        try {
                            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } catch (e: ActivityNotFoundException) {
                            Log.w(LOG_TAG, "No permission prompt available", e)
                            if (fromNotice) openNotificationSettings(context)
                        }
                    }
                    fromNotice -> openNotificationSettings(context)
                }
            },
        )
    }
}

/** False when the permission is missing (Android 13+) or the user blocked the app's notifications. */
private fun notificationsAllowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

/** Android 13+ without POST_NOTIFICATIONS granted. */
private fun needsPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/**
 * Whether Android would still show the permission prompt after a refusal. After a first "Don't allow" it
 * would (the rationale flag is set); after a second, or a block in settings, it silently refuses.
 */
private fun canAskAgain(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val activity = context.findActivity() ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
}

/** The app's notification settings page; the app-info page where that isn't available. */
private fun openNotificationSettings(context: Context) {
    val notificationSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    for (intent in listOf(notificationSettings, appDetails)) {
        // Outside an activity (e.g. a wrapped context) a new task is required to start one.
        if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            Log.w(LOG_TAG, "No activity for ${intent.action}", e)
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current != null) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

private const val LOG_TAG = "NotificationAccess"
