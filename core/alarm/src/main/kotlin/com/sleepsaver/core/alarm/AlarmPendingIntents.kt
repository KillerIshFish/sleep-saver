package com.sleepsaver.core.alarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.sleepsaver.core.alarm.night.NightService
import com.sleepsaver.core.alarm.receiver.AlarmReceiver
import com.sleepsaver.core.alarm.ring.AlarmRingService
import com.sleepsaver.core.data.hooks.AlarmIntents

/**
 * Every PendingIntent of core:alarm (SPEC_V2 §4.2): explicit component, FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT, only ids
 * and simple extras (never a nested Intent). Extras don't take part in PendingIntent identity, so the request code
 * alone tells RING_PLAN, RING_EXTRA and RING_CATNAP apart: an extra never un-arms the plan.
 */
internal object AlarmPendingIntents {
    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /** 20: the planned occurrence's wake-time ring. */
    fun ringPlan(context: Context, occurrenceId: String): PendingIntent = broadcast(
        context,
        AlarmActions.RC_RING_PLAN,
        ringIntent(context, AlarmActions.KIND_PLAN).putExtra(AlarmActions.EXTRA_OCCURRENCE_ID, occurrenceId),
    )

    /** 21: sensing starts (the lead-in before the surfacing window). */
    fun window(context: Context, occurrenceId: String): PendingIntent = broadcast(
        context,
        AlarmActions.RC_WINDOW,
        Intent(context, AlarmReceiver::class.java).setAction(AlarmActions.ACTION_WINDOW).putExtra(AlarmActions.EXTRA_OCCURRENCE_ID, occurrenceId),
    )

    /** 22: AlarmClockInfo.showIntent (tapping the system's next-alarm icon opens Tonight). */
    fun show(context: Context): PendingIntent = activity(context, AlarmActions.RC_SHOW, AlarmIntents.DEEP_LINK_TONIGHT)

    /** 23: snooze end, test, restless trigger, catch-up, backup, resume. */
    fun ringExtra(context: Context, at: Long? = null): PendingIntent = broadcast(
        context,
        AlarmActions.RC_RING_EXTRA,
        ringIntent(context, AlarmActions.KIND_EXTRA).apply { if (at != null) putExtra(AlarmActions.EXTRA_EPOCH_MS, at) },
    )

    /** 24: the catnap's own alarm. */
    fun ringCatnap(context: Context): PendingIntent = broadcast(context, AlarmActions.RC_RING_CATNAP, ringIntent(context, AlarmActions.KIND_CATNAP))

    /** 25: the ringing screen, as full-screen intent and content intent of the ring notification. */
    fun fullScreen(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        AlarmActions.RC_FULL_SCREEN,
        Intent()
            .setClassName(context.packageName, AlarmIntents.RINGING_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        FLAGS,
    )

    /** 26: Snooze (notification action, and the delete intent while a snooze is available). */
    fun snooze(context: Context): PendingIntent = ringService(context, AlarmActions.RC_SNOOZE, AlarmActions.ACTION_SNOOZE)

    /** 27: Stop. */
    fun stop(context: Context): PendingIntent = ringService(context, AlarmActions.RC_STOP, AlarmActions.ACTION_STOP)

    /** 28: Stop on the "Snoozed until …" notification. */
    fun stopSnoozed(context: Context): PendingIntent = receiverAction(context, AlarmActions.RC_STOP_SNOOZED, AlarmActions.ACTION_STOP_SNOOZED)

    /** 29: Cancel on the catnap notification. */
    fun cancelCatnap(context: Context): PendingIntent = receiverAction(context, AlarmActions.RC_CANCEL_CATNAP, AlarmActions.ACTION_CANCEL_CATNAP)

    /** 30: Stop Hush. */
    fun hushStop(context: Context): PendingIntent = receiverAction(context, AlarmActions.RC_HUSH_STOP, AlarmActions.ACTION_HUSH_STOP)

    /** 31: Hush "+15 min". */
    fun hushExtend(context: Context): PendingIntent = receiverAction(context, AlarmActions.RC_HUSH_EXTEND, AlarmActions.ACTION_HUSH_EXTEND)

    /** 32: content of the night notification. */
    fun night(context: Context): PendingIntent = activity(context, AlarmActions.RC_NIGHT, AlarmIntents.DEEP_LINK_NIGHT)

    /** 33: the ring notification's delete intent when no snooze is left: it is posted again. */
    fun repost(context: Context): PendingIntent = ringService(context, AlarmActions.RC_REPOST, AlarmActions.ACTION_REPOST)

    /** The service intent NightService gets for [action]. */
    fun nightServiceIntent(context: Context, action: String): Intent = Intent(context, NightService::class.java).setAction(action)

    private fun ringIntent(context: Context, kind: String): Intent =
        Intent(context, AlarmReceiver::class.java).setAction(AlarmActions.ACTION_RING).putExtra(AlarmActions.EXTRA_KIND, kind)

    private fun receiverAction(context: Context, requestCode: Int, action: String): PendingIntent =
        broadcast(context, requestCode, Intent(context, AlarmReceiver::class.java).setAction(action))

    private fun ringService(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(context, requestCode, Intent(context, AlarmRingService::class.java).setAction(action), FLAGS)

    private fun broadcast(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(context, requestCode, intent, FLAGS)

    private fun activity(context: Context, requestCode: Int, link: String): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        FLAGS,
    )
}
