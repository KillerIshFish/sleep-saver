package com.sleepsaver.core.alarm.ring

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
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
import com.sleepsaver.core.alarm.NightControllerImpl
import com.sleepsaver.core.alarm.mirror.MirroredRinging
import com.sleepsaver.core.alarm.night.NightService
import com.sleepsaver.core.alarm.receiver.RingWakeLock
import com.sleepsaver.core.alarm.synth.ToneSynth
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * The ringing alarm (SPEC_V2 §4.4 T6, T8, T9, T10; §4.7): a mediaPlayback foreground service started only by
 * AlarmReceiver when RING_PLAN, RING_EXTRA or RING_CATNAP fires (the exact-alarm start exemption). It posts the
 * full-screen notification 300, plays the wake-up style's cue timeline (player gain only), escalates, times out and
 * arms the one backup ring. It never idles while snoozed: a snooze stops it, and the re-ring is a fresh start. It never
 * starts an activity itself: SystemUI sends the notification's full-screen intent, and MainActivity opens the ringing
 * screen while the app is in front (docs/PLAN.md §5, "Opening the ringing screen").
 *
 * Android 17 background audio hardening (docs/PLAN.md §5): a service started by an alarm has no while-in-use
 * capability, so the ring's audio focus and playback rest on the exemption for USAGE_ALARM with an exact-alarm
 * permission (USE_EXACT_ALARM). The player only starts after startForeground, and whenever one of our screens comes to
 * the front mid-ring the app is in the foreground anyway: the player then asks for focus again ([screens]).
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

    /**
     * One of our screens came to the front while a ring sounds (the ringing screen from its full-screen intent or a
     * notification tap, or the app before it redirects there): the app is in the foreground, so a focus request the
     * audio hardening held back goes through now. Asking again is a no-op while the player holds focus.
     */
    private val screens = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            sounding?.let { active -> output("focus") { active.sound.reclaimFocus() } }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        current = this
        AlarmChannels.ensure(this)
        application.registerActivityLifecycleCallbacks(screens)
    }

    /**
     * Sticky while a ring sounds (T14): if the process dies mid-ring, the system restarts the service, and that start's
     * reconcile (Application.onCreate) resumes the ring right away instead of only at the ring guard's timeout. The
     * restart's null intent then finds nothing sounding and goes.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AlarmActions.ACTION_RING -> onRing(requestOf(intent))
            AlarmActions.ACTION_SNOOZE -> snooze()
            AlarmActions.ACTION_STOP -> stop()
            AlarmActions.ACTION_REPOST -> repost()
            else -> if (sounding == null) finishIdle()
        }
        RingWakeLock.release()
        return if (sounding != null) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        application.unregisterActivityLifecycleCallbacks(screens)
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
        val offset = time.zone().rules.getOffset(now)
        when (val decision = coordinator.apply { m -> transitions.onRing(m, request, now, sounding?.ringing, offset) }) {
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
            // No foreground service, so no tone of ours: notification 303 rings with the phone's alarm sound and shows
            // Stop/Snooze, and the mirror keeps the ring (marked fallback) so those actions and the ringing screen
            // work, and reconcile lets it time out.
            Log.w(TAG, "Ring service refused; showing the fallback notification", e)
            coordinator.apply { m -> transitions.markFallback(m, ringing.since) }
            RingNotifications.cancel(this, AlarmActions.ID_SNOOZED)
            if (catnapConsumed) RingNotifications.cancel(this, AlarmActions.ID_CATNAP)
            RingNotifications.post(this, AlarmActions.ID_RING_FALLBACK, RingNotifications.fallback(this, ringing, offer, now, time.zone(), use24h))
            val scheduler = scheduler.get()
            if (ringing.isPlanned) scheduler.replanAfterRing()
            scheduler.flushEventsAsync()
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
        // Blocked ring UI (no Stop button): capped, but only counted once the speaker is on (AlarmConfig.ringCap).
        val cap = config.alarm.ringCap(ringing.kind, alarm.wakeStyle, uiBlocked = AlarmChannels.ringUiBlocked(this))
        // The timeline must keep running with the screen off, also in Quiet bed's silent first minutes.
        active.wakeLock = getSystemService<PowerManager>()?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, AlarmPlatform.RING_WAKE_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire(cap.toMillis() + AlarmPlatform.RING_WAKE_LOCK_MS)
        }
        val cue = config.alarm.ringCue(ringing.kind, alarm.wakeStyle)
        val timeline = CueTimeline(cue, alarm.volume, ToneSynth.cycleOf(alarm.alarmTone), config.alarm)
        active.job = main.launch { runTimeline(active, timeline, cap) }
        publish()
        if (ringing.isPlanned) scheduler.replanAfterRing()
        scheduler.flushEventsAsync()
    }

    /**
     * T10: the cue timeline on the main thread, then the timeout. A sound or vibration that fails never ends it: the
     * other output, the snooze label and the timeout go on (an exception here would crash the app mid-ring, and the
     * sticky restart would resume the ring into the same failure).
     */
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
                speakerOn = true // started once: a player that failed isn't started again every tick
                output("sound") { active.sound.start(cue.gain) }
            } else if (speakerOn) {
                output("gain") { active.sound.setGain(cue.gain) }
            }
            if (cue.vibrate != vibrating) {
                vibrating = cue.vibrate
                output("vibration") { if (cue.vibrate) active.vibration.start() else active.vibration.stop() }
            }
            refreshOffer(active)
            delay(AlarmPlatform.GAIN_STEP_MS)
        }
    }

    /** Runs one call into the ring's player or vibrator; a failure is logged, never thrown into the ring or its end. */
    private inline fun output(what: String, call: () -> Unit) {
        try {
            call()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Alarm $what failed; the ring goes on", e)
        }
    }

    // ---------------------------------------------------------------- T8, T9, T10

    /**
     * T8: snooze, when one is left. The service stops; the re-ring is a fresh RING with a fresh notification. When a
     * planned ring of another occurrence waits on the extra channel, this ring gives way to it instead (no "Snoozed
     * until"), and a queued one rings right away.
     */
    fun snooze() {
        val active = sounding ?: return run {
            nightController.snoozeFromMirror()
            if (!foreground) stopSelf()
        }
        val now = time.now()
        val result = coordinator.apply { m -> transitions.snooze(m, now) } ?: return
        silence(active)
        sounding = null
        if (result is SnoozeResult.Snoozed) {
            val notification = RingNotifications.snoozed(this, result.until, result.lastOne, time.zone(), coordinator.read().use24h)
            RingNotifications.post(this, AlarmActions.ID_SNOOZED, notification)
        }
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
        val active = sounding ?: return run { if (!foreground) stopSelf() }
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

    /**
     * Ends a ring's outputs. Stop, Snooze, the timeout and onDestroy all come through here, so a player or vibrator that
     * fails to stop never keeps the other one going, holds the wake lock or keeps the caller's transition from running.
     */
    private fun silence(active: Sounding) {
        active.job?.cancel()
        output("sound stop") { active.sound.stop() }
        output("vibration stop") { active.vibration.stop() }
        active.wakeLock?.let { if (it.isHeld) it.release() }
    }

    /**
     * The snooze label moves with the clock ("Snooze until 6:40"): re-post 300 and re-publish only when what they show
     * changes (the minute, last one, none left). The offer's `until` itself moves with every 250-ms tick while it isn't
     * capped, and re-posting the full-screen notification four times a second would hit the notification rate limit.
     */
    private fun refreshOffer(active: Sounding) {
        val offer = transitions.offer(active.ringing, time.now())
        val shownChanged = offer.shown() != active.offer.shown()
        active.offer = offer
        if (!shownChanged) return
        RingNotifications.post(this, AlarmActions.ID_RINGING, ringNotification(active))
        publish()
    }

    /** What the notification and the ringing screen show of an offer: its minute and whether it is the last one. */
    private fun SnoozeOffer.shown(): Pair<Instant?, Boolean> = until?.truncatedTo(ChronoUnit.MINUTES) to lastOne

    private fun ringNotification(active: Sounding): Notification =
        RingNotifications.ring(this, active.ringing, active.offer, time.zone(), coordinator.read().use24h)

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, AlarmActions.ID_RINGING, notification, type)
        foreground = true
    }

    /**
     * NightController.state.ringing: published by this running service (NightController itself shows only a fallback
     * ring, one no service could start).
     */
    private fun publish() {
        val active = sounding
        nightController.publishRinging(active?.ringing?.published(active.offer, coordinator.read().use24h, time.zone()))
    }

    private fun requestOf(intent: Intent): RingRequest {
        val source = when (intent.getStringExtra(AlarmActions.EXTRA_KIND)) {
            AlarmActions.KIND_EXTRA -> RingSource.EXTRA
            AlarmActions.KIND_CATNAP -> RingSource.CATNAP
            else -> RingSource.PLAN
        }
        val atEpochMs = if (intent.hasExtra(AlarmActions.EXTRA_EPOCH_MS)) intent.getLongExtra(AlarmActions.EXTRA_EPOCH_MS, 0L) else null
        return RingRequest(source, intent.getStringExtra(AlarmActions.EXTRA_OCCURRENCE_ID), atEpochMs = atEpochMs)
    }

    internal companion object {
        private const val TAG = "SleepSaverAlarm"

        /** In-process liveness (set in onCreate, cleared in onDestroy); the reconciler's ringServiceRunning. */
        @Volatile var running: Boolean = false

        @Volatile var current: AlarmRingService? = null
    }
}
