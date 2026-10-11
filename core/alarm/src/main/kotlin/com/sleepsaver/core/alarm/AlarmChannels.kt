package com.sleepsaver.core.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.sleepsaver.core.data.hooks.AlarmIntents

/**
 * core:alarm's notification channels (SPEC_V2 §4.2). Ids live in [AlarmIntents] and never change: users' per-channel
 * settings hang off them, and a channel's importance, sound and lock-screen visibility are fixed once it exists.
 */
internal object AlarmChannels {

    /**
     * Creates the three channels. Idempotent; refreshes the localized names (run again after a locale change). Runs in
     * AlarmBootstrap.startLocked and at the top of AlarmRingService.onCreate and NightService.onCreate, so a channel
     * always exists before startForeground.
     */
    fun ensure(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        // The ring service plays the sound and vibrates, so the channel does neither (no doubled sound).
        val ring = channel(context, AlarmIntents.CHANNEL_RING, R.string.alarm_channel_ring, R.string.alarm_channel_ring_desc, NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setBypassDnd(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val quiet = channel(context, AlarmIntents.CHANNEL_QUIET, R.string.alarm_channel_quiet, R.string.alarm_channel_quiet_desc, NotificationManager.IMPORTANCE_LOW)
        val night = channel(context, AlarmIntents.CHANNEL_NIGHT, R.string.alarm_channel_night, R.string.alarm_channel_night_desc, NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannels(listOf(ring, quiet, night))
    }

    /**
     * True when the ring notification can't show its Stop/Snooze or full-screen intent: notifications are off for the
     * app, or the user turned the "Alarm" category (alarm_ring) off. Rings are then capped at
     * notificationsBlockedRingCap, and catnaps and test rings are refused.
     */
    fun ringUiBlocked(context: Context): Boolean =
        !NotificationManagerCompat.from(context).areNotificationsEnabled() ||
            context.getSystemService<NotificationManager>()?.getNotificationChannel(AlarmIntents.CHANNEL_RING)?.importance ==
            NotificationManager.IMPORTANCE_NONE

    private fun channel(context: Context, id: String, @StringRes name: Int, @StringRes description: Int, importance: Int) =
        NotificationChannel(id, context.getString(name), importance).apply { this.description = context.getString(description) }
}
