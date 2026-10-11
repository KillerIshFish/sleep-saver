package com.sleepsaver.core.ui.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Something on this phone that can stop an alarm from ringing (or from showing its Stop button), in severity order.
 * [blocking] problems make an alarm untrustworthy; the others (battery optimisation, a Bluetooth speaker) are only
 * worth knowing. Example: RING_CHANNEL_OFF is the "Alarm" notification category switched off while the app's other
 * notifications stay on.
 */
enum class AlarmBlocker(val blocking: Boolean) {
    /** POST_NOTIFICATIONS missing or the app's notifications blocked. */
    NOTIFICATIONS_OFF(blocking = true),

    /** The ring channel set to off (importance NONE). */
    RING_CHANNEL_OFF(blocking = true),

    /** Android 12+: "Alarms & reminders" not allowed, so alarms may ring late or early. */
    EXACT_ALARMS_OFF(blocking = true),

    /** Android 14+: full-screen intents not allowed, so the alarm shows as a banner. */
    FULL_SCREEN_OFF(blocking = true),

    /** The app's battery use set to Restricted. */
    BACKGROUND_RESTRICTED(blocking = true),

    /** The alarm stream's volume at 0. */
    ALARM_VOLUME_ZERO(blocking = true),

    /** Do Not Disturb on "Total silence", which mutes alarms too. */
    TOTAL_SILENCE(blocking = true),

    /** Battery optimisation on for the app: usually fine. */
    BATTERY_OPTIMIZED(blocking = false),

    /** A Bluetooth (A2DP or LE audio) output connected: the alarm may play there. Nothing to fix. */
    BLUETOOTH_OUTPUT(blocking = false),
}

/** One Alarm check row: [blocker] [passed] (nothing wrong) or not. */
data class AlarmCheck(val blocker: AlarmBlocker, val passed: Boolean)

/**
 * The phone settings an alarm depends on, read when the screen resumes, plus the way to fix each. Get one with
 * [rememberAlarmAccess]. Before arming anything that can ring, call [requestNotifications] when
 * NOTIFICATIONS_OFF is in [blockers], then check RING_CHANNEL_OFF; the other blockers never block arming.
 */
@Stable
class AlarmAccess internal constructor(
    private val checksState: State<List<AlarmCheck>>,
    private val onFix: (AlarmBlocker) -> Unit,
    private val onRequestNotifications: (onResult: (Boolean) -> Unit) -> Unit,
) {
    /** Every check, in display order (the [AlarmBlocker] order). */
    val checks: List<AlarmCheck> get() = checksState.value

    /** The failing blocking checks, most severe first. */
    val blockers: List<AlarmBlocker> get() = checks.filter { !it.passed && it.blocker.blocking }.map { it.blocker }

    /** No blocking check fails. */
    val ready: Boolean get() = blockers.isEmpty()

    /**
     * Opens the fix for [blocker]: the notification prompt while Android still shows it (otherwise the app's
     * notification settings), the "Alarm" category's settings, the Alarms & reminders or full-screen page, app info,
     * sound settings, Do Not Disturb settings, or the battery optimisation list. BLUETOOTH_OUTPUT has no fix.
     */
    fun fix(blocker: AlarmBlocker) = onFix(blocker)

    /**
     * Makes sure notifications are allowed, asking for POST_NOTIFICATIONS (Android 13+) when the prompt can still be
     * shown. [onResult] gets whether notifications are allowed afterwards (true at once when they already are); the
     * checks are re-read before it runs, so RING_CHANNEL_OFF can be checked next.
     */
    fun requestNotifications(onResult: (Boolean) -> Unit) = onRequestNotifications(onResult)
}

/**
 * [AlarmAccess] for this screen. [ringChannelId] is the alarm's notification channel (core:alarm's
 * `AlarmIntents.CHANNEL_RING`, "alarm_ring"); a channel that doesn't exist yet counts as on.
 */
@Composable
fun rememberAlarmAccess(ringChannelId: String): AlarmAccess {
    val context = LocalContext.current
    val checks = remember(context, ringChannelId) { mutableStateOf(readAlarmChecks(context, ringChannelId)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checks.value = readAlarmChecks(context, ringChannelId) }
    // Asked from the notice's "Allow": if Android no longer shows the prompt, the settings page is the only way left.
    val settingsIfRefused = rememberSaveable { mutableStateOf(false) }
    // Waiting for the prompt's answer (not saved: a recreated screen simply asks again).
    val pending = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        checks.value = readAlarmChecks(context, ringChannelId)
        val allowed = notificationsAllowed(context)
        if (!granted && settingsIfRefused.value && !canAskAgain(context)) openNotificationSettings(context)
        settingsIfRefused.value = false
        val onResult = pending.value
        pending.value = null
        onResult?.invoke(allowed)
    }
    return remember(context, ringChannelId, launcher) {
        fun prompt(fromNotice: Boolean, onResult: ((Boolean) -> Unit)?): Boolean {
            settingsIfRefused.value = fromNotice
            pending.value = onResult
            return try {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                true
            } catch (e: ActivityNotFoundException) {
                Log.w(LOG_TAG, "No permission prompt available", e)
                settingsIfRefused.value = false
                pending.value = null
                false
            }
        }
        AlarmAccess(
            checksState = checks,
            onFix = { blocker ->
                if (blocker == AlarmBlocker.NOTIFICATIONS_OFF) {
                    if (!needsPermission(context) || !prompt(fromNotice = true, onResult = null)) openNotificationSettings(context)
                } else {
                    context.openFix(blocker, ringChannelId)
                }
            },
            onRequestNotifications = { onResult ->
                checks.value = readAlarmChecks(context, ringChannelId)
                when {
                    notificationsAllowed(context) -> onResult(true)
                    needsPermission(context) -> if (!prompt(fromNotice = false, onResult = onResult)) onResult(false)
                    // Blocked in settings (or Android 12 and lower): no prompt to show; the notice explains.
                    else -> onResult(false)
                }
            },
        )
    }
}

// ---------------------------------------------------------------- Reading the checks

/** Every check, read now, in [AlarmBlocker] order. */
internal fun readAlarmChecks(context: Context, ringChannelId: String): List<AlarmCheck> =
    AlarmBlocker.entries.map { AlarmCheck(it, passed = !it.isFailing(context, ringChannelId)) }

/** A check that can't be read (an odd system service) doesn't count as a problem. */
private fun AlarmBlocker.isFailing(context: Context, ringChannelId: String): Boolean = try {
    when (this) {
        AlarmBlocker.NOTIFICATIONS_OFF -> !notificationsAllowed(context)
        AlarmBlocker.RING_CHANNEL_OFF ->
            context.notificationManager()?.getNotificationChannel(ringChannelId)?.importance == NotificationManager.IMPORTANCE_NONE
        AlarmBlocker.EXACT_ALARMS_OFF ->
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == false
        AlarmBlocker.FULL_SCREEN_OFF ->
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                context.notificationManager()?.canUseFullScreenIntent() == false
        AlarmBlocker.BACKGROUND_RESTRICTED -> context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true
        AlarmBlocker.ALARM_VOLUME_ZERO -> context.audioManager()?.getStreamVolume(AudioManager.STREAM_ALARM) == 0
        AlarmBlocker.TOTAL_SILENCE ->
            context.notificationManager()?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
        AlarmBlocker.BATTERY_OPTIMIZED ->
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == false
        AlarmBlocker.BLUETOOTH_OUTPUT ->
            context.audioManager()?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().any { it.type in BluetoothOutputs }
    }
} catch (e: RuntimeException) {
    Log.w(LOG_TAG, "Couldn't read $this", e)
    false
}

/** A2DP plus the LE audio types (Android 12+ and 13+; older systems never report them). */
@SuppressLint("InlinedApi")
private val BluetoothOutputs = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST,
)

private fun Context.notificationManager(): NotificationManager? = getSystemService(NotificationManager::class.java)

private fun Context.audioManager(): AudioManager? = getSystemService(AudioManager::class.java)

// ---------------------------------------------------------------- Fixing them

/** Opens the settings page that fixes [blocker] (every blocker but NOTIFICATIONS_OFF, which may prompt instead). */
private fun Context.openFix(blocker: AlarmBlocker, ringChannelId: String) {
    val packageUri = Uri.fromParts("package", packageName, null)
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
    val opened = when (blocker) {
        AlarmBlocker.NOTIFICATIONS_OFF -> {
            openNotificationSettings(this)
            true
        }
        AlarmBlocker.RING_CHANNEL_OFF -> tryStartFirst(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, ringChannelId),
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            appDetails,
        )
        AlarmBlocker.EXACT_ALARMS_OFF ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                tryStartFirst(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri), appDetails)
            } else {
                tryStartFirst(appDetails)
            }
        AlarmBlocker.FULL_SCREEN_OFF ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                tryStartFirst(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri), appDetails)
            } else {
                tryStartFirst(appDetails)
            }
        AlarmBlocker.BACKGROUND_RESTRICTED -> tryStartFirst(appDetails)
        AlarmBlocker.ALARM_VOLUME_ZERO -> tryStartFirst(Intent(Settings.ACTION_SOUND_SETTINGS))
        AlarmBlocker.TOTAL_SILENCE ->
            tryStartFirst(Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS), Intent(Settings.ACTION_SOUND_SETTINGS))
        // The list, never the "Allow" dialog that exempts the app directly.
        AlarmBlocker.BATTERY_OPTIMIZED -> tryStartFirst(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        AlarmBlocker.BLUETOOTH_OUTPUT -> true
    }
    if (!opened) Log.w(LOG_TAG, "No settings page for $blocker")
}

private const val LOG_TAG = "AlarmAccess"
