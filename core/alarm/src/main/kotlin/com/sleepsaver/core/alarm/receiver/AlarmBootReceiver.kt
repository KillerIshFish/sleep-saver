package com.sleepsaver.core.alarm.receiver

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sleepsaver.core.alarm.AlarmChannels
import com.sleepsaver.core.alarm.AlarmPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Re-arms the alarm after a reboot, an app update, a clock or zone change, a locale change, or when exact-alarm access
 * is granted (SPEC_V2 §4.4 T11/T12). Never starts a service.
 *
 *  - Always first (also before the first unlock): the mirror-only re-arm (T11). Missed rings become CATCH_UP extras on
 *    RING_EXTRA; RING_PLAN is re-resolved from date + local time in the current zone.
 *  - Unlocked: then a full reschedule from Room on the app scope (goAsync, under 9 s).
 */
internal class AlarmBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return
        val entry = context.alarmEntryPoint()
        if (action == Intent.ACTION_LOCALE_CHANGED) AlarmChannels.ensure(context)
        if (action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            Log.i(TAG, "Exact alarm access changed; allowed = ${entry.coordinator().canScheduleExact()}")
        }
        val scheduler = entry.scheduler().get()
        scheduler.rearmFromMirror()
        entry.nightController().refresh()
        if (!scheduler.unlocked()) return

        val pending: PendingResult? = goAsync()
        entry.scope().launch {
            try {
                withTimeoutOrNull(AlarmPlatform.RECEIVER_BUDGET_MS) { scheduler.reschedule() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Reschedule after $action failed; the mirror re-arm stands", e)
            } finally {
                pending?.finish()
            }
        }
    }

    internal companion object {
        private const val TAG = "SleepSaverAlarm"

        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
