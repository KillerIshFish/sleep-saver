package com.sleepsaver.core.alarm.ring

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sleepsaver.core.alarm.AlarmActions
import com.sleepsaver.core.alarm.AlarmChannels
import com.sleepsaver.core.alarm.AlarmPendingIntents
import com.sleepsaver.core.alarm.AlarmText
import com.sleepsaver.core.alarm.R
import com.sleepsaver.core.alarm.mirror.MirroredRinging
import com.sleepsaver.core.data.hooks.AlarmIntents
import com.sleepsaver.core.domain.alarm.SnoozeOffer
import com.sleepsaver.core.domain.config.SleepConfig
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** The ring, snoozed, fallback and catnap notifications (ids 300, 302, 303, 304; SPEC_V2 §4.2, §4.7). */
internal object RingNotifications {

    /**
     * 300, the ringing foreground notification on alarm_ring: CATEGORY_ALARM, max priority, ongoing, full-screen
     * intent and content intent = the ringing screen; Snooze (only when available, "Snooze until 6:40") and Stop. Its
     * delete intent snoozes when it can, else posts it again: it never stops silently.
     *
     * Never setSilent: NotificationCompat makes a silent notification a child of a "silent" group with
     * GROUP_ALERT_SUMMARY, and SystemUI (Android 13+) then neither launches its full-screen intent nor shows it as a
     * heads-up. The service is the only sound anyway (alarm_ring has no sound or vibration); only-alert-once keeps the
     * once-a-minute re-posts of the snooze label from alerting again.
     */
    fun ring(context: Context, ringing: MirroredRinging, offer: SnoozeOffer, zone: ZoneId, use24h: Boolean): Notification {
        val fullScreen = AlarmPendingIntents.fullScreen(context)
        val builder = base(context, AlarmIntents.CHANNEL_RING)
            .setContentTitle(AlarmText.title(context, ringing.kind))
            .setContentText(AlarmText.reason(context, ringing, zone, use24h))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val until = offer.until
        if (until != null) {
            builder.addAction(0, context.getString(R.string.alarm_action_snooze, AlarmText.clock(until, zone, use24h)), AlarmPendingIntents.snooze(context))
        }
        builder.addAction(0, context.getString(R.string.alarm_action_stop), AlarmPendingIntents.stop(context))
        builder.setDeleteIntent(if (until != null) AlarmPendingIntents.snooze(context) else AlarmPendingIntents.repost(context))
        return builder.build()
    }

    /**
     * 303, when the ring service couldn't start (no foreground service, so no tone of ours): the same full-screen intent
     * and Stop/Snooze actions, on the alarm_ring_fallback channel, whose own sound (the phone's alarm sound, as an alarm)
     * and vibration repeat (insistent) until the ring is answered or its ringTimeout from [ringing]'s start is up.
     * Example: Android 12 without "Alarms & reminders", where the inexact ring alarm can't start a foreground service.
     */
    fun fallback(context: Context, ringing: MirroredRinging?, offer: SnoozeOffer, now: Instant, zone: ZoneId, use24h: Boolean): Notification {
        val fullScreen = AlarmPendingIntents.fullScreen(context)
        val ringTimeout = SleepConfig.DEFAULT.alarm.ringTimeout
        val left = ringing?.let { Duration.between(now, it.since + ringTimeout) } ?: ringTimeout
        val builder = base(context, AlarmChannels.CHANNEL_RING_FALLBACK)
            .setContentTitle(context.getString(R.string.alarm_fallback_title))
            .setContentText(ringing?.let { AlarmText.reason(context, it, zone, use24h) } ?: context.getString(R.string.alarm_fallback_body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
        if (left > Duration.ZERO) builder.setTimeoutAfter(left.toMillis())
        offer.until?.let { until ->
            builder.addAction(0, context.getString(R.string.alarm_action_snooze, AlarmText.clock(until, zone, use24h)), AlarmPendingIntents.snooze(context))
        }
        builder.addAction(0, context.getString(R.string.alarm_action_stop), AlarmPendingIntents.stop(context))
        return builder.build().apply { flags = flags or Notification.FLAG_INSISTENT }
    }

    /** 302, while snoozed: "Snoozed until 6:40" (" · then the last ring"), ongoing and silent, Stop, opens the ring screen. */
    fun snoozed(context: Context, until: Instant, lastOne: Boolean, zone: ZoneId, use24h: Boolean): Notification {
        val time = AlarmText.clock(until, zone, use24h)
        return base(context, AlarmIntents.CHANNEL_QUIET)
            .setContentTitle(context.getString(if (lastOne) R.string.alarm_snoozed_last else R.string.alarm_snoozed, time))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(AlarmPendingIntents.fullScreen(context))
            .addAction(0, context.getString(R.string.alarm_action_stop), AlarmPendingIntents.stopSnoozed(context))
            .build()
    }

    /** 304, "Catnap · rings at 2:47 PM" with Cancel. */
    fun catnap(context: Context, ringsAt: Instant, zone: ZoneId, use24h: Boolean): Notification =
        base(context, AlarmIntents.CHANNEL_QUIET)
            .setContentTitle(context.getString(R.string.alarm_catnap_pending, AlarmText.clock(ringsAt, zone, use24h)))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(AlarmPendingIntents.show(context))
            .addAction(0, context.getString(R.string.alarm_action_cancel), AlarmPendingIntents.cancelCatnap(context))
            .build()

    /** The notification an ignored ring start shows for an instant (every startForegroundService must reach startForeground). */
    fun placeholder(context: Context): Notification =
        base(context, AlarmIntents.CHANNEL_QUIET)
            .setContentTitle(context.getString(R.string.alarm_fallback_title))
            .setSilent(true)
            .setShowWhen(false)
            .build()

    /** Posts [notification] when the user allows notifications (a refused post is not an error here). */
    @SuppressLint("MissingPermission") // checked just above
    fun post(context: Context, id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(context)
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed && manager.areNotificationsEnabled()) manager.notify(id, notification)
    }

    fun cancel(context: Context, vararg ids: Int) {
        val manager = NotificationManagerCompat.from(context)
        ids.forEach(manager::cancel)
    }

    /** Every core:alarm notification (300–304). */
    fun cancelAll(context: Context) = cancel(
        context,
        AlarmActions.ID_RINGING,
        AlarmActions.ID_NIGHT,
        AlarmActions.ID_SNOOZED,
        AlarmActions.ID_RING_FALLBACK,
        AlarmActions.ID_CATNAP,
    )

    private fun base(context: Context, channel: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(ContextCompat.getColor(context, R.color.alarm_accent))
}
