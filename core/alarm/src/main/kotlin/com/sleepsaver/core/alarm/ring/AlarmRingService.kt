package com.sleepsaver.core.alarm.ring

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import com.sleepsaver.core.alarm.AlarmActions
import com.sleepsaver.core.alarm.AlarmChannels
import com.sleepsaver.core.alarm.AlarmCoordinator
import com.sleepsaver.core.alarm.AlarmPlatform
import com.sleepsaver.core.alarm.AlarmSchedulerImpl
import com.sleepsaver.core.alarm.AlarmText
import com.sleepsaver.core.alarm.NightControllerImpl
import com.sleepsaver.core.alarm.mirror.MirroredRinging
import com.sleepsaver.core.alarm.night.NightService
import com.sleepsaver.core.alarm.receiver.RingWakeLock
import com.sleepsaver.core.alarm.synth.ToneSynth
import com.sleepsaver.core.data.hooks.Ringing
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.SnoozeOffer
import com.sleepsaver.core.domain.config.SleepConfig
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import javax.inject.Inject

/**
 * The ringing alarm (SPEC_V2 §4.4 T6, T8, T9, T10; §4.7): a mediaPlayback foreground service started only by
 * AlarmReceiver when RING_PLAN, RING_EXTRA or RING_CATNAP fires (the exact-alarm start exemption). It posts the
 * full-screen notification 300, plays the wake-up style's cue timeline (player gain only), escalates, times out and
 * arms the one backup ring. It never idles while snoozed: a snooze stops it, and the re-ring is a fresh start.
 *
 * Direct-boot aware and direct-boot safe: everything it injects reads only the device-protected mirror until unlock.
 */
@AndroidEntryPoint
internal class AlarmRingService : Service() {

    @Inject internal lateinit var coordinator: AlarmCoordinator
    @Inject internal lateinit var scheduler: dagger.Lazy<AlarmSchedulerImpl>
    @Inject internal lateinit var nightController: NightControllerImpl
    @Inject internal lateinit var time: TimeProvider
    @Inject internal lateinit var outputs: RingOutputs

    private val config = SleepConfig.DEFAULT
    private val transitions = RingTransitions(config)
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sounding: Sounding? = null
    private var queued: RingRequest? = null
    private var foreground = false

    /** A ring that is sounding: its mirror snapshot, outputs and timeline. */
    private class Sounding(val ringing: MirroredRinging, val sound: RingSound, val vibration: RingVibration, var offer: SnoozeOffer) {
        var job: Job? = null
        var wakeLock: PowerManager.WakeLock? = null
    }

    /** True while a ring sounds (not while snoozed). */
    val isSounding: Boolean get() = sounding != null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        current = this
        AlarmChannels.ensure(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AlarmActions.ACTION_RING -> onRing(requestOf(intent))
            AlarmActions.ACTION_SNOOZE -> snooze()
            AlarmActions.ACTION_STOP -> stop()
            AlarmActions.ACTION_REPOST -> repost()
            else -> if (sounding == null) finishIdle()
        }
        RingWakeLock.release()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sounding?.let(::silence)
        sounding = null
        main.cancel()
        if (current === this) current = null
        running = false
        nightController.publishRinging(null)
        RingWakeLock.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- T6

    private fun onRing(request: RingRequest) {
        // The window run's last word first: a SURFACING ring at the wake time is logged by its sensing outcome.
        if (request.source == RingSource.PLAN) NightService.current?.concludeWindowRun(request.occurrenceId)
        val now = time.now()
        when (val decision = coordinator.apply { m -> transitions.onRing(m, request, now, sounding?.ringing) }) {
            is RingDecision.Ignore -> {
                Log.i(TAG, "Ring ignored: ${decision.why}")
                if (sounding == null) finishIdle()
            }
            is RingDecision.Queue -> queued = decision.request
            is RingDecision.Start -> begin(decision.ringing, decision.catnapConsumed)
        }
    }

    private fun begin(ringing: MirroredRinging, catnapConsumed: Boolean) {
        val alarm = ringing.alarm ?: return finishIdle()
        val now = time.now()
        val offer = transitions.offer(ringing, now)
        val use24h = coordinator.read().use24h
        try {
            startForegroundCompat(RingNotifications.ring(this, ringing, offer, time.zone(), use24h))
        } catch (e: Exception) {
            // No foreground service, no sound: the alarm still shows with Stop/Snooze (notification 303), and the
            // mirror keeps the ring so those actions and the next reconcile (which resumes it) work.
            Log.w(TAG, "Ring service refused; showing the fallback notification", e)
            RingNotifications.post(this, AlarmActions.ID_RING_FALLBACK, RingNotifications.fallback(this, ringing, offer, time.zone(), use24h))
            scheduler.get().flushEventsAsync()
            stopSelf()
            return
        }
        RingNotifications.cancel(this, AlarmActions.ID_SNOOZED, AlarmActions.ID_RING_FALLBACK)
        if (catnapConsumed) RingNotifications.cancel(this, AlarmActions.ID_CATNAP)
        NightService.current?.let { night ->
            night.stopHushForRing()
            night.concludeWindowRun(null)
        }
        val scheduler = scheduler.get()
        val sound = outputs.sound(alarm.alarmTone, locked = !scheduler.unlocked())
        val vibration = outputs.vibration()
        val active = Sounding(ringing, sound, vibration, offer)
        sounding = active
        val cap = when {
            ringing.kind == RingKind.TEST -> config.alarm.testRingLength
            AlarmChannels.ringUiBlocked(this) -> config.alarm.notificationsBlockedRingCap
            else -> config.alarm.ringTimeout
        }
        // The timeline must keep running with the screen off, also in Quiet bed's silent first minutes.
        active.wakeLock = getSystemService<PowerManager>()?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, AlarmPlatform.RING_WAKE_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire(cap.toMillis() + AlarmPlatform.RING_WAKE_LOCK_MS)
        }
        val timeline = CueTimeline(config.alarm.cue(alarm.wakeStyle), alarm.volume, ToneSynth.cycleOf(alarm.alarmTone), config.alarm)
        active.job = main.launch { runTimeline(active, timeline, cap) }
        publish()
        if (ringing.isPlanned) scheduler.replanAfterRing()
        scheduler.flushEventsAsync()
    }

    /** T10: the cue timeline on the main thread, then the timeout. */
    private suspend fun runTimeline(active: Sounding, timeline: CueTimeline, cap: Duration) {
        val startedAt = SystemClock.elapsedRealtime()
        var speakerOn = false
        var vibrating = false
        while (main.isActive && sounding === active) {
            val elapsed = Duration.ofMillis(SystemClock.elapsedRealtime() - startedAt)
            if (elapsed >= cap) {
                timeout()
                return
            }
            val cue = timeline.at(elapsed)
            if (cue.speaker && !speakerOn) {
                active.sound.start(cue.gain)
                speakerOn = true
            } else if (speakerOn) {
                active.sound.setGain(cue.gain)
            }
            if (cue.vibrate != vibrating) {
                if (cue.vibrate) active.vibration.start() else active.vibration.stop()
                vibrating = cue.vibrate
            }
            refreshOffer(active)
            delay(AlarmPlatform.GAIN_STEP_MS)
        }
    }

    // ---------------------------------------------------------------- T8, T9, T10

    /** T8: snooze, when one is left. The service stops; the re-ring is a fresh RING with a fresh notification. */
    fun snooze() {
        val active = sounding ?: return run {
            nightController.snoozeFromMirror()
            if (!foreground) stopSelf()
        }
        val now = time.now()
        val offer = coordinator.apply { m -> transitions.snooze(m, now) } ?: return
        val until = offer.until ?: return
        silence(active)
        sounding = null
        RingNotifications.post(this, AlarmActions.ID_SNOOZED, RingNotifications.snoozed(this, until, offer.lastOne, time.zone(), coordinator.read().use24h))
        publish()
        scheduler.get().flushEventsAsync()
        next()
    }

    /** T9: Stop. A planned stop also ends the night state, the movement log and Hush; the plan moves on. */
    fun stop() {
        val active = sounding ?: return run {
            nightController.stopFromMirror()
            if (!foreground) stopSelf()
        }
        silence(active)
        val now = time.now()
        val result = coordinator.apply { m -> transitions.stop(m, now) }
        sounding = null
        publish()
        result?.let(nightController::afterStop)
        next()
    }

    /** Reset all data: silence and go, without touching the mirror (it is being cleared). */
    fun stopEverything() {
        sounding?.let(::silence)
        sounding = null
        queued = null
        publish()
        if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    private fun timeout() {
        val active = sounding ?: return
        silence(active)
        val now = time.now()
        coordinator.apply { m -> transitions.timeout(m, now) }
        sounding = null
        publish()
        scheduler.get().flushEventsAsync()
        next()
    }

    /** The ring notification was swiped away with no snooze left: post it again (it never stops silently). */
    private fun repost() {
        val active = sounding ?: return
        RingNotifications.post(this, AlarmActions.ID_RINGING, ringNotification(active))
    }

    /** After a ring ended: a queued planned ring rings now, else the service goes. */
    private fun next() {
        val waiting = queued
        queued = null
        if (waiting != null) {
            onRing(waiting)
            if (sounding != null) return
        }
        if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    /** An ignored start on a service that isn't foreground yet must still reach startForeground, then go. */
    private fun finishIdle() {
        if (!foreground) {
            try {
                startForegroundCompat(RingNotifications.placeholder(this))
            } catch (e: Exception) {
                Log.w(TAG, "Placeholder foreground refused", e)
            }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    private fun silence(active: Sounding) {
        active.job?.cancel()
        active.sound.stop()
        active.vibration.stop()
        active.wakeLock?.let { if (it.isHeld) it.release() }
    }

    /** The snooze label moves with the clock ("Snooze until 6:40"): refresh it when the offer changes. */
    private fun refreshOffer(active: Sounding) {
        val offer = transitions.offer(active.ringing, time.now())
        if (offer == active.offer) return
        active.offer = offer
        RingNotifications.post(this, AlarmActions.ID_RINGING, ringNotification(active))
        publish()
    }

    private fun ringNotification(active: Sounding): Notification =
        RingNotifications.ring(this, active.ringing, active.offer, time.zone(), coordinator.read().use24h)

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, AlarmActions.ID_RINGING, notification, type)
        foreground = true
    }

    /** NightController.state.ringing: published only by this running service. */
    private fun publish() {
        val active = sounding
        val r = active?.ringing
        val alarm = r?.alarm
        nightController.publishRinging(
            if (r == null || alarm == null) {
                null
            } else {
                Ringing(
                    kind = r.kind,
                    reason = r.ringReason,
                    since = r.since,
                    target = alarm.target.takeIf { r.kind != RingKind.TEST },
                    wakeDate = alarm.wakeDate.takeIf { r.kind == RingKind.PLANNED },
                    earlyBy = Duration.ofMinutes(AlarmText.minutesEarly(r.since, alarm.target)).takeIf { r.kind == RingKind.PLANNED } ?: Duration.ZERO,
                    snooze = active.offer,
                    style = alarm.wakeStyle,
                    use24h = coordinator.read().use24h,
                    zone = time.zone(),
                )
            },
        )
    }

    private fun requestOf(intent: Intent): RingRequest {
        val source = when (intent.getStringExtra(AlarmActions.EXTRA_KIND)) {
            AlarmActions.KIND_EXTRA -> RingSource.EXTRA
            AlarmActions.KIND_CATNAP -> RingSource.CATNAP
            else -> RingSource.PLAN
        }
        return RingRequest(source, intent.getStringExtra(AlarmActions.EXTRA_OCCURRENCE_ID))
    }

    internal companion object {
        private const val TAG = "SleepSaverAlarm"

        /** In-process liveness (set in onCreate, cleared in onDestroy); the reconciler's ringServiceRunning. */
        @Volatile var running: Boolean = false

        @Volatile var current: AlarmRingService? = null
    }
}
