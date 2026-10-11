package com.sleepsaver.core.alarm.receiver

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.os.UserManagerCompat
import com.sleepsaver.core.alarm.AlarmActions
import com.sleepsaver.core.alarm.AlarmPendingIntents
import com.sleepsaver.core.alarm.AlarmPlatform
import com.sleepsaver.core.alarm.mirror.MirrorUpdate
import com.sleepsaver.core.alarm.mirror.unchanged
import com.sleepsaver.core.alarm.ring.AlarmRingService
import com.sleepsaver.core.alarm.ring.RingDecision
import com.sleepsaver.core.alarm.ring.RingNotifications
import com.sleepsaver.core.alarm.ring.RingRequest
import com.sleepsaver.core.alarm.ring.RingSource
import com.sleepsaver.core.alarm.ring.RingTransitions
import com.sleepsaver.core.domain.alarm.SensingOutcome
import com.sleepsaver.core.domain.config.SleepConfig

/**
 * The alarm broadcasts (SPEC_V2 §4.4): RING (T5), WINDOW (T3), the ring guard (T14), and the notification actions
 * Stop-while-snoozed, Cancel catnap, Stop Hush and Hush +15 min. Only our own explicit PendingIntents reach it
 * (exported=false).
 * Direct-boot aware. RING and WINDOW start their service synchronously in onReceive, while the exact alarm's
 * foreground-service start exemption holds.
 */
internal class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val entry = context.alarmEntryPoint()
        when (intent.action) {
            AlarmActions.ACTION_RING -> onRing(context, intent, entry)
            AlarmActions.ACTION_WINDOW -> onWindow(context, intent, entry)
            // Stop on "Snoozed until …": also stops the ring when the snooze end is already sounding (a stale 302).
            AlarmActions.ACTION_STOP_SNOOZED -> entry.nightController().stopRinging()
            AlarmActions.ACTION_CANCEL_CATNAP -> entry.nightController().cancelCatnap()
            AlarmActions.ACTION_HUSH_STOP -> entry.nightController().stopHush()
            AlarmActions.ACTION_HUSH_EXTEND -> entry.nightController().extendHush(SleepConfig.DEFAULT.hush.extendStep)
            AlarmActions.ACTION_RING_GUARD -> onRingGuard(context, entry)
        }
    }

    /**
     * T14: a ring's timeout passed while `ringing` still stood (the coordinator's RING_GUARD). Its process died, or it
     * only ever stood as notification 303. The mirror re-arm's reconcile resumes it or times it out and arms its
     * backup; a 303 whose ring is over goes. A ring service that is still sounding answers it itself (no change).
     */
    private fun onRingGuard(context: Context, entry: AlarmEntryPoint) {
        entry.scheduler().get().rearmFromMirror()
        if (entry.coordinator().read().ringing == null) RingNotifications.cancel(context, AlarmActions.ID_RING_FALLBACK)
    }

    /**
     * T5: a 60-s wake lock (the ring service releases it) and startForegroundService(AlarmRingService, RING). When the
     * start is refused, the ring is recorded in the mirror (so Stop/Snooze and the next reconcile work) and
     * notification 303 shows it with the full-screen intent and Stop/Snooze; no sound is possible.
     */
    private fun onRing(context: Context, intent: Intent, entry: AlarmEntryPoint) {
        RingWakeLock.acquire(context)
        val kind = intent.getStringExtra(AlarmActions.EXTRA_KIND) ?: AlarmActions.KIND_PLAN
        val occurrenceId = intent.getStringExtra(AlarmActions.EXTRA_OCCURRENCE_ID)
        // A RING_EXTRA's armed time: the ring is matched to the pending extra by it, however late an inexact (T2)
        // window delivered the broadcast.
        val atEpochMs = if (intent.hasExtra(AlarmActions.EXTRA_EPOCH_MS)) intent.getLongExtra(AlarmActions.EXTRA_EPOCH_MS, 0L) else null
        val service = Intent(context, AlarmRingService::class.java)
            .setAction(AlarmActions.ACTION_RING)
            .putExtra(AlarmActions.EXTRA_KIND, kind)
            .putExtra(AlarmActions.EXTRA_OCCURRENCE_ID, occurrenceId)
        if (atEpochMs != null) service.putExtra(AlarmActions.EXTRA_EPOCH_MS, atEpochMs)
        try {
            ContextCompat.startForegroundService(context, service)
        } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException is one
            ringFallback(context, entry, kind, occurrenceId, atEpochMs, e)
        } catch (e: SecurityException) {
            ringFallback(context, entry, kind, occurrenceId, atEpochMs, e)
        }
    }

    private fun ringFallback(context: Context, entry: AlarmEntryPoint, kind: String, occurrenceId: String?, atEpochMs: Long?, error: Exception) {
        Log.w(TAG, "Ring service not started ($kind $occurrenceId); showing the fallback notification", error)
        RingWakeLock.release()
        val source = when (kind) {
            AlarmActions.KIND_EXTRA -> RingSource.EXTRA
            AlarmActions.KIND_CATNAP -> RingSource.CATNAP
            else -> RingSource.PLAN
        }
        val transitions = RingTransitions(SleepConfig.DEFAULT)
        val time = entry.time()
        val now = time.now()
        val decision = entry.coordinator().apply { m ->
            val request = RingRequest(source, occurrenceId, atEpochMs = atEpochMs)
            val update = transitions.onRing(m, request, now, sounding = null, offset = time.zone().rules.getOffset(now))
            val started = update.value as? RingDecision.Start ?: return@apply update
            val marked = transitions.markFallback(update.state, started.ringing.since).state
            update.copy(state = marked, value = started.copy(ringing = marked.ringing ?: started.ringing))
        }
        if (decision !is RingDecision.Start) return
        // The snooze or the catnap this ring answers is over: only 303 stands for it now.
        RingNotifications.cancel(context, AlarmActions.ID_SNOOZED)
        if (decision.catnapConsumed) RingNotifications.cancel(context, AlarmActions.ID_CATNAP)
        val use24h = entry.coordinator().read().use24h
        val offer = transitions.offer(decision.ringing, now)
        RingNotifications.post(context, AlarmActions.ID_RING_FALLBACK, RingNotifications.fallback(context, decision.ringing, offer, time.zone(), use24h))
        val scheduler = entry.scheduler().get()
        if (decision.ringing.isPlanned) scheduler.replanAfterRing()
        scheduler.flushEventsAsync()
    }

    /**
     * T3: locked → no sensing (NightService isn't direct-boot aware; the wake-time ring still fires): UNAVAILABLE.
     * A stale id is ignored. Otherwise a 10-s wake lock and always startForegroundService(NightService, WINDOW), also
     * when it already runs for Hush or the whole-night log: this exempt start is what makes adding the health type
     * legal. A refused start leaves UNAVAILABLE.
     */
    private fun onWindow(context: Context, intent: Intent, entry: AlarmEntryPoint) {
        val occurrenceId = intent.getStringExtra(AlarmActions.EXTRA_OCCURRENCE_ID) ?: return
        val coordinator = entry.coordinator()
        if (!UserManagerCompat.isUserUnlocked(context)) {
            markUnavailable(entry, occurrenceId)
            return
        }
        if (coordinator.read().armed?.occurrenceId != occurrenceId) return
        acquireWindowWakeLock(context)
        try {
            ContextCompat.startForegroundService(context, AlarmPendingIntents.nightServiceIntent(context, AlarmActions.ACTION_WINDOW).putExtra(AlarmActions.EXTRA_OCCURRENCE_ID, occurrenceId))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Sensing not started", e)
            markUnavailable(entry, occurrenceId)
        } catch (e: SecurityException) {
            Log.w(TAG, "Sensing not started", e)
            markUnavailable(entry, occurrenceId)
        }
    }

    private fun markUnavailable(entry: AlarmEntryPoint, occurrenceId: String) {
        entry.coordinator().apply { m ->
            val armed = m.armed?.takeIf { it.occurrenceId == occurrenceId && it.outcome == null } ?: return@apply m.unchanged(Unit)
            MirrorUpdate(m.copy(armed = armed.copy(sensingOutcome = SensingOutcome.UNAVAILABLE.name)), Unit)
        }
    }

    @SuppressLint("WakelockTimeout") // acquired with a timeout
    private fun acquireWindowWakeLock(context: Context) {
        context.getSystemService<PowerManager>()
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, AlarmPlatform.WINDOW_WAKE_LOCK_TAG)
            ?.acquire(AlarmPlatform.WINDOW_WAKE_LOCK_MS)
    }

    private companion object {
        const val TAG = "SleepSaverAlarm"
    }
}

/** The 60-s wake lock between the RING broadcast and the ring service sounding (it releases it). */
internal object RingWakeLock {
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context) {
        val lock = wakeLock ?: context.applicationContext.getSystemService<PowerManager>()
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, AlarmPlatform.RING_WAKE_LOCK_TAG)
            ?.apply { setReferenceCounted(false) }
            ?.also { wakeLock = it }
        lock?.acquire(AlarmPlatform.RING_WAKE_LOCK_MS)
    }

    @Synchronized
    fun release() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }
}
