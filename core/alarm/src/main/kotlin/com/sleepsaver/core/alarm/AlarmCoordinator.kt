package com.sleepsaver.core.alarm

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService
import com.sleepsaver.core.alarm.mirror.AlarmAction
import com.sleepsaver.core.alarm.mirror.AlarmMirror
import com.sleepsaver.core.alarm.mirror.MirrorState
import com.sleepsaver.core.alarm.mirror.MirrorUpdate
import com.sleepsaver.core.alarm.mirror.PendingAlarm
import com.sleepsaver.core.domain.config.SleepConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single owner of the mirror's writes and of AlarmManager calls (SPEC_V2 §4.3). Every writer goes through [apply]:
 * reschedule, the restless trigger, snooze, stop, timeout, catnap, test, the boot paths, reconcile, Hush and anchor.
 *
 * Direct-boot safe: context and the device-protected mirror only.
 */
@Singleton
internal class AlarmCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mirror: AlarmMirror,
) {
    /** Serialises reschedule(): a reschedule computes outside [apply] and re-checks its inputs inside it. */
    val planMutex = Mutex()

    /** Set when a reschedule's inputs changed twice during its compute; one more reschedule runs after [planMutex]. */
    val rescheduleAgain = AtomicBoolean(false)

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(MirrorState) -> Unit>()
    private val alarmManager by lazy { context.getSystemService<AlarmManager>() }

    /** The mirror as stored now. */
    fun read(): MirrorState = synchronized(lock) { mirror.read() }

    /**
     * Re-reads every key, runs [block], commits the changed keys with generation + 1, then performs the AlarmManager
     * calls the block returned, all under one lock, and tells the listeners (NightControllerImpl) the new state.
     */
    fun <T> apply(block: (MirrorState) -> MirrorUpdate<T>): T = synchronized(lock) {
        val before = mirror.read()
        val update = block(before)
        val after = update.state.copy(generation = before.generation + 1)
        mirror.write(before, after)
        update.actions.forEach(::perform)
        listeners.forEach { it(after) }
        update.value
    }

    /** Clears every mirror key (Reset all data). */
    fun clear() = synchronized(lock) {
        mirror.clear()
        val cleared = MirrorState()
        listeners.forEach { it(cleared) }
    }

    /** Cancels the four alarm PendingIntents (Reset all data). */
    fun cancelAllAlarms() = synchronized(lock) {
        PendingAlarm.entries.forEach { perform(AlarmAction.Cancel(it)) }
    }

    fun addListener(listener: (MirrorState) -> Unit) {
        listeners += listener
    }

    /** Exact alarms allowed: always below Android 12, else the "Alarms & reminders" access. */
    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager?.canScheduleExactAlarms() == true

    /**
     * One AlarmManager call. Rings use setAlarmClock (Doze-exempt, shown as the next alarm); without exact-alarm access
     * (T2) the wake-time ring uses setWindow(target − inexactRingWindow, inexactRingWindow), early rather than late,
     * extras setWindow(at, inexactExtraWindow), and there is no WINDOW.
     */
    private fun perform(action: AlarmAction) {
        val manager = alarmManager ?: return
        val alarm = SleepConfig.DEFAULT.alarm
        try {
            when (action) {
                is AlarmAction.ArmPlan -> {
                    val operation = AlarmPendingIntents.ringPlan(context, action.occurrenceId)
                    if (canScheduleExact()) {
                        manager.setAlarmClock(AlarmManager.AlarmClockInfo(action.at.toEpochMilli(), AlarmPendingIntents.show(context)), operation)
                    } else {
                        val window = alarm.inexactRingWindow.toMillis()
                        manager.setWindow(AlarmManager.RTC_WAKEUP, action.at.toEpochMilli() - window, window, operation)
                    }
                }
                is AlarmAction.ArmWindow -> {
                    val operation = AlarmPendingIntents.window(context, action.occurrenceId)
                    if (canScheduleExact()) {
                        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, action.at.toEpochMilli(), operation)
                    } else {
                        manager.cancel(operation)
                    }
                }
                is AlarmAction.ArmExtra -> armRing(manager, action.at.toEpochMilli(), AlarmPendingIntents.ringExtra(context, action.at.toEpochMilli()))
                is AlarmAction.ArmCatnap -> armRing(manager, action.at.toEpochMilli(), AlarmPendingIntents.ringCatnap(context))
                is AlarmAction.Cancel -> manager.cancel(pendingIntent(action.alarm))
            }
        } catch (e: SecurityException) {
            // The exact-alarm access went away between the check and the call: the process is about to be killed and
            // the next start re-arms with the inexact fallback.
            Log.w(TAG, "AlarmManager refused $action", e)
        }
    }

    private fun armRing(manager: AlarmManager, atMs: Long, operation: android.app.PendingIntent) {
        if (canScheduleExact()) {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(atMs, AlarmPendingIntents.show(context)), operation)
        } else {
            manager.setWindow(AlarmManager.RTC_WAKEUP, atMs, SleepConfig.DEFAULT.alarm.inexactExtraWindow.toMillis(), operation)
        }
    }

    private fun pendingIntent(alarm: PendingAlarm): android.app.PendingIntent = when (alarm) {
        PendingAlarm.RING_PLAN -> AlarmPendingIntents.ringPlan(context, "")
        PendingAlarm.WINDOW -> AlarmPendingIntents.window(context, "")
        PendingAlarm.RING_EXTRA -> AlarmPendingIntents.ringExtra(context)
        PendingAlarm.RING_CATNAP -> AlarmPendingIntents.ringCatnap(context)
    }

    private companion object {
        const val TAG = "SleepSaverAlarm"
    }
}
