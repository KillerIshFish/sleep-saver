package com.sleepsaver.core.alarm.night

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.sleepsaver.core.alarm.AlarmActions
import com.sleepsaver.core.alarm.AlarmChannels
import com.sleepsaver.core.alarm.AlarmCoordinator
import com.sleepsaver.core.alarm.AlarmPendingIntents
import com.sleepsaver.core.alarm.AlarmPlatform
import com.sleepsaver.core.alarm.AlarmText
import com.sleepsaver.core.alarm.NightControllerImpl
import com.sleepsaver.core.alarm.R
import com.sleepsaver.core.alarm.mirror.AlarmAction
import com.sleepsaver.core.alarm.mirror.ExtraTrigger
import com.sleepsaver.core.alarm.mirror.MirrorUpdate
import com.sleepsaver.core.alarm.mirror.MirroredAlarm
import com.sleepsaver.core.alarm.mirror.MirroredExtra
import com.sleepsaver.core.alarm.mirror.MirroredHush
import com.sleepsaver.core.alarm.mirror.unchanged
import com.sleepsaver.core.alarm.ring.RingNotifications
import com.sleepsaver.core.data.di.ApplicationScope
import com.sleepsaver.core.data.hooks.AlarmIntents
import com.sleepsaver.core.data.hooks.SensingMode
import com.sleepsaver.core.data.hooks.SensingState
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.NightLogRepository
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.alarm.MovementEpoch
import com.sleepsaver.core.domain.alarm.MovementMeter
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.alarm.SensingOutcome
import com.sleepsaver.core.domain.alarm.SurfacingDecider
import com.sleepsaver.core.domain.alarm.SurfacingDecision
import com.sleepsaver.core.domain.alarm.SurfacingNight
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.schedule.NightRules
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

/**
 * Movement sensing and Hush (SPEC_V2 §4.4 T3/T4/T7, §4.6). It runs only while it has a reason: sensing (a surfacing
 * window run and/or the opt-in whole-night log → the health type) and Hush (→ mediaPlayback). A Drop anchor without
 * the log and without Hush runs no service at all.
 *
 * Starts: ACTION_WINDOW from AlarmReceiver when WINDOW fires (the exact-alarm exemption makes adding the health type
 * legal, also to a service already running Hush), ACTION_LOG_START and ACTION_HUSH_START from the UI. Never from a
 * boot receiver or a reschedule. Not direct-boot aware: it writes Room.
 */
@AndroidEntryPoint
internal class NightService : Service(), AudioManager.OnAudioFocusChangeListener {

    @Inject internal lateinit var coordinator: AlarmCoordinator
    @Inject internal lateinit var nightController: NightControllerImpl
    @Inject internal lateinit var nightLog: dagger.Lazy<NightLogRepository>
    @Inject internal lateinit var time: TimeProvider
    @Inject @ApplicationScope internal lateinit var scope: CoroutineScope
    @Inject internal lateinit var sensors: MotionSensors
    @Inject internal lateinit var hushOutputs: HushOutputs

    private val config = SleepConfig.DEFAULT
    private val decider = SurfacingDecider(config)
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()

    /** The foreground types in force (0 = not foreground). Internal for tests. */
    internal var foregroundMask = 0
        private set

    private var stream: MovementStream? = null
    private var windowRun: WindowRun? = null
    private var windowTimer: Job? = null
    private var wholeNight: WholeNight? = null
    private var hush: HushPlayback? = null
    private var ringStoppedHush = false
    private var focusRequest: AudioFocusRequest? = null

    /** The occurrence a window run is sensing for right now, if any. */
    val windowRunId: String? get() = synchronized(lock) { windowRun?.occurrenceId }

    /** Whether Hush is playing (not paused for a transient focus loss). Internal for tests. */
    internal val hushPlaying: Boolean get() = hush?.paused == false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        current = this
        AlarmChannels.ensure(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AlarmActions.ACTION_WINDOW -> openWindow(intent.getStringExtra(AlarmActions.EXTRA_OCCURRENCE_ID))
            AlarmActions.ACTION_LOG_START -> startWholeNightLog()
            AlarmActions.ACTION_HUSH_START -> startHush()
            AlarmActions.ACTION_HUSH_STOP -> stopHush()
            AlarmActions.ACTION_HUSH_EXTEND -> nightController.extendHush(config.hush.extendStep)
            else -> reachForeground()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        synchronized(lock) {
            windowTimer?.cancel()
            windowRun = null
            wholeNight = null
            stream?.let { closeStream(it, time.now()) }
            stream = null
        }
        // Hush never restarts by itself: a service that goes takes its Hush with it.
        hush?.let { endHushPlayback(it, writeMirror = true) }
        hush = null
        main.cancel()
        if (current === this) current = null
        running = false
        nightController.publishSensing(null)
        nightController.refresh()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Window run (T3, T4, T7)

    /**
     * ACTION_WINDOW. The same run again is ignored (but still reaches startForeground); otherwise the health type is
     * added through startForeground here, in onStartCommand of this exempt start. A refusal drops sensing and leaves
     * `sensingOutcome = UNAVAILABLE` (the ring then logs SENSING_UNAVAILABLE); Hush keeps playing.
     */
    private fun openWindow(occurrenceId: String?) {
        synchronized(lock) {
            if (occurrenceId != null && windowRun?.occurrenceId == occurrenceId) return reachForeground()
            val armed = coordinator.read().armed
            val now = time.now()
            if (occurrenceId == null || armed == null || armed.occurrenceId != occurrenceId || !armed.isSurfacing || now >= armed.target) {
                return reachForeground()
            }
            // A run for another occurrence ends here without the foreground update: on the main thread that update
            // runs at once and, with no other reason left, would stopSelf() the service this new run is starting in.
            val replaced = windowRun != null
            windowRun?.let { endWindowRun(updateForeground = false) }
            val wanted = mask(sensing = true, hush = hush != null)
            if (!applyForeground(wanted, onlyIfChanged = false)) {
                markUnavailable(occurrenceId)
                if (!replaced) return reachForeground()
                updateForeground() // the ended run's reason is gone too
                publishSensing()
                return
            }
            val active = stream ?: startStream(armed.wakeDate, mode = SensingMode.WINDOW, anchorId = null, occurrenceId = occurrenceId)
            if (active == null) {
                markUnavailable(occurrenceId)
                updateForeground()
                return
            }
            val night = surfacingNight(armed, active.start)
            windowRun = WindowRun(occurrenceId, night, since = now)
            coordinator.apply { m ->
                val current = m.armed?.takeIf { it.occurrenceId == occurrenceId } ?: return@apply m.unchanged(Unit)
                // A run that starts after an UNAVAILABLE attempt (locked, refused) drops that result: it senses now.
                val outcome = current.sensingOutcome.takeUnless { it == SensingOutcome.UNAVAILABLE.name }
                MirrorUpdate(m.copy(armed = current.copy(sensingStartEpochMs = night.sensingStart.toEpochMilli(), sensingOutcome = outcome)), Unit)
            }
            if (!active.source.wakeUp) {
                val lockFor = minOf(Duration.between(now, armed.target + config.surfacing.wakeLockMargin), config.surfacing.maxRunLength)
                active.holdWakeLock(lockFor)
            }
            // Concludes the run at the wake time even if no epoch closes then (a stalled sensor).
            windowTimer = main.launch {
                delay(Duration.between(now, armed.target).toMillis().coerceAtLeast(0) + AlarmPlatform.GAIN_STEP_MS)
                concludeWindowRun(occurrenceId)
            }
        }
        publishSensing()
    }

    /** Builds the decider's night from the armed alarm (§4.6, lead amendment 5). */
    private fun surfacingNight(armed: MirroredAlarm, streamStart: Instant): SurfacingNight {
        val windowStart = armed.windowStart
        return SurfacingNight(
            occurrenceId = armed.occurrenceId,
            target = armed.target,
            windowStart = windowStart,
            sensingStart = maxOf(streamStart, windowStart - config.surfacing.sensingLeadIn),
            debtAware = armed.debtAware,
            zThreshold = armed.zThreshold,
            expectedOnset = armed.expectedOnsetEpochMs?.let(Instant::ofEpochMilli) ?: (windowStart - config.surfacing.onsetFallbackBeforeWindow),
            prior = coordinator.read().prior?.toDomain(),
        )
    }

    /**
     * Epochs that closed (from the sensor thread, or a test). [sampleTime] = the time of the sample that closed the
     * latest one, which is what the decider weighs as "now" (§2.5; a batch arrives late but keeps its times).
     */
    internal fun onEpochs(closed: List<MovementEpoch>, sampleTime: Instant) {
        if (closed.isEmpty()) return
        val ended = synchronized(lock) {
            val active = stream ?: return
            active.add(closed)
            persist(active, force = false)
            windowRun?.let { run -> evaluate(run, active, sampleTime) }
            wholeNight?.takeIf { sampleTime >= it.endsAt }
        }
        // The whole-night log's night is over (a sample keeps its own time, also in a batch that arrives late).
        if (ended != null) main.launch { endWholeNightLog(ended.anchorId, ended.endsAt) }
    }

    /** T4. Must hold [lock]. */
    private fun evaluate(run: WindowRun, active: MovementStream, sampleTime: Instant) {
        val armed = coordinator.read().armed
        if (armed == null || armed.occurrenceId != run.occurrenceId) {
            endWindowRun() // T7: the plan moved; no AlarmManager call
            return
        }
        when (val decision = decider.decide(run.night, active.epochs, sampleTime)) {
            SurfacingDecision.Wait -> Unit
            is SurfacingDecision.Fire -> fire(run, active)
            is SurfacingDecision.Done -> conclude(run, active, decision.outcome)
        }
    }

    /**
     * T4 Fire: under the coordinator, only for the same armed id, with no ring sounding, no planned ring pending and
     * time left before the wake time: SMART on RING_EXTRA at now + catchUpDelay. RING_PLAN stays at the wake time as a
     * backstop. The window run ends (a whole-night log keeps logging). Must hold [lock].
     */
    private fun fire(run: WindowRun, active: MovementStream) {
        val now = time.now()
        val maxZ = decider.maxZ(run.night, active.epochs)
        coordinator.apply { m ->
            val armed = m.armed
            val at = now + config.alarm.catchUpDelay
            if (armed == null || armed.occurrenceId != run.occurrenceId || m.ringing != null || m.extra?.isPlanned == true || at >= armed.target) {
                return@apply m.unchanged(Unit)
            }
            val restless = armed.copy(sensingOutcome = SensingOutcome.RESTLESS.name, maxZ = maxZ)
            val smart = MirroredExtra(
                trigger = ExtraTrigger.SMART.name,
                atEpochMs = at.toEpochMilli(),
                ringKind = RingKind.PLANNED.name,
                occurrenceId = run.occurrenceId,
                snoozesUsed = 0,
                reason = RingReason.RESTLESS.name,
                alarm = restless,
                expiresAtEpochMs = (at + config.alarm.ringTimeout).toEpochMilli(),
            )
            MirrorUpdate(m.copy(armed = restless, extra = smart), Unit, listOf(AlarmAction.ArmExtra(at)))
        }
        endWindowRun()
    }

    /** T4 Done: the outcome goes on the armed alarm (the wake-time ring logs it). Must hold [lock]. */
    private fun conclude(run: WindowRun, active: MovementStream, outcome: SensingOutcome) {
        val maxZ = decider.maxZ(run.night, active.epochs)
        coordinator.apply { m ->
            val armed = m.armed?.takeIf { it.occurrenceId == run.occurrenceId && it.outcome == null } ?: return@apply m.unchanged(Unit)
            MirrorUpdate(m.copy(armed = armed.copy(sensingOutcome = outcome.name, maxZ = maxZ)), Unit)
        }
        endWindowRun()
    }

    /**
     * Ends the window run with its outcome at the wake time (the decider's Done), e.g. when the ring starts.
     * [occurrenceId] null = whatever run is going.
     */
    fun concludeWindowRun(occurrenceId: String?) {
        synchronized(lock) {
            val run = windowRun ?: return
            if (occurrenceId != null && run.occurrenceId != occurrenceId) return
            val active = stream ?: return endWindowRun()
            active.meter.flush()?.let { active.add(listOf(it)) }
            val decision = decider.decide(run.night, active.epochs, maxOf(time.now(), run.night.target))
            if (decision is SurfacingDecision.Done) conclude(run, active, decision.outcome) else endWindowRun()
        }
    }

    /** T1 step 8 / T7: the armed alarm changed; a run for another occurrence ends without touching AlarmManager. */
    fun onArmedChanged(armed: MirroredAlarm?) {
        synchronized(lock) {
            val run = windowRun ?: return
            if (run.occurrenceId != armed?.occurrenceId) endWindowRun()
        }
    }

    /** Must hold [lock]. [updateForeground] false: the caller sets the foreground itself right after. */
    private fun endWindowRun(updateForeground: Boolean = true) {
        windowRun ?: return
        windowRun = null
        windowTimer?.cancel()
        windowTimer = null
        val active = stream
        if (active != null && wholeNight == null) {
            closeStream(active, time.now())
            stream = null
        }
        if (!updateForeground) return
        main.launch {
            updateForeground()
            publishSensing()
        }
    }

    private fun markUnavailable(occurrenceId: String) {
        coordinator.apply { m ->
            val armed = m.armed?.takeIf { it.occurrenceId == occurrenceId && it.outcome == null } ?: return@apply m.unchanged(Unit)
            MirrorUpdate(m.copy(armed = armed.copy(sensingOutcome = SensingOutcome.UNAVAILABLE.name)), Unit)
        }
    }

    // ---------------------------------------------------------------- Whole-night log

    private fun startWholeNightLog() {
        val night = coordinator.read().night
        synchronized(lock) {
            if (night == null || !night.wholeNightLog) return reachForeground()
            if (wholeNight?.anchorId == night.anchorId) return reachForeground()
            if (!applyForeground(mask(sensing = true, hush = hush != null), onlyIfChanged = false)) return reachForeground()
            val wakeDate = NightRules.wakeDateOf(night.anchoredAt, night.offset, config)
            val active = stream ?: startStream(wakeDate, SensingMode.WHOLE_NIGHT, anchorId = night.anchorId, occurrenceId = null)
            if (active == null) {
                updateForeground()
                return
            }
            wholeNight = WholeNight(night.anchorId, night.endsAt)
            refreshNotification() // "Movement log on" (the foreground start above didn't know the reason yet)
            if (!active.source.wakeUp) {
                val armedTarget = coordinator.read().armed?.target
                val until = listOfNotNull(armedTarget?.plus(config.surfacing.wholeNightWakeLockMargin), night.endsAt).minOrNull() ?: night.endsAt
                active.holdWakeLock(Duration.between(time.now(), until))
            }
            // No timer for the night's end: a delay counts uptime, which stands still while the CPU sleeps between
            // sensor batches (and could then stop a later night's log). The first sample past the end stops the log
            // (onEpochs), and so does the reconcile that clears the ended night (onNightEnded).
        }
        publishSensing()
    }

    /** "I'm up" or undo: the whole-night log stops now (a window run keeps its stream). */
    fun stopWholeNightLog() = endWholeNightLog(anchorId = null, end = time.now())

    /**
     * Reconcile cleared the night of [anchorId], which ended at [endsAt]: its log stops there, on the main thread. A
     * later night's log keeps going.
     */
    fun onNightEnded(anchorId: Long, endsAt: Instant) {
        main.launch { endWholeNightLog(anchorId, minOf(time.now(), endsAt)) }
    }

    /** Stops the whole-night log of [anchorId] (null: whichever runs) at [end]; a window run keeps its stream. */
    private fun endWholeNightLog(anchorId: Long?, end: Instant) {
        synchronized(lock) {
            val log = wholeNight ?: return
            if (anchorId != null && log.anchorId != anchorId) return
            wholeNight = null
            val active = stream
            if (active != null && windowRun == null) {
                closeStream(active, end)
                stream = null
            }
        }
        updateForeground()
        publishSensing()
    }

    // ---------------------------------------------------------------- Movement stream

    /** Must hold [lock]. null when the phone has no accelerometer. */
    private fun startStream(wakeDate: LocalDate, mode: SensingMode, anchorId: Long?, occurrenceId: String?): MovementStream? {
        val start = time.now()
        val offset = time.zone().rules.getOffset(start)
        val source = sensors.create()
        val active = MovementStream(start, offset, MovementMeter(config), source, getSystemService<PowerManager>())
        val started = source.start { at, x, y, z ->
            val closed = synchronized(lock) { active.meter.add(at, x, y, z) }
            onEpochs(closed, at)
        }
        if (!started) return null
        stream = active
        scope.launch {
            val id = try {
                nightLog.get().startMovementLog(start, offset, mode.name, wakeDate, anchorId, occurrenceId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Movement log not created", e)
                return@launch
            }
            synchronized(lock) {
                active.logId = id
                persist(active, force = active.closed)
                if (active.closed) active.endAt?.let { end -> endLog(id, end) }
            }
            if (mode == SensingMode.WHOLE_NIGHT) {
                coordinator.apply { m -> m.night?.takeIf { it.anchorId == anchorId }?.let { MirrorUpdate(m.copy(night = it.copy(logId = id)), Unit) } ?: m.unchanged(Unit) }
            }
        }
        return active
    }

    /** Must hold [lock]. */
    private fun closeStream(active: MovementStream, end: Instant) {
        active.source.stop()
        active.meter.flush()?.let { active.add(listOf(it)) }
        active.releaseWakeLock()
        active.closed = true
        active.endAt = end
        persist(active, force = true)
        active.logId?.let { endLog(it, end) }
    }

    /** Writes epochs every persistEveryEpochs (or all on [force]) once the log row exists. Must hold [lock]. */
    private fun persist(active: MovementStream, force: Boolean) {
        val id = active.logId ?: return
        if (active.unsaved.isEmpty() || (!force && active.unsaved.size < config.surfacing.persistEveryEpochs)) return
        val batch = active.unsaved.toList()
        active.unsaved.clear()
        scope.launch { runCatching { nightLog.get().addEpochs(id, batch) }.onFailure { Log.w(TAG, "Epochs not saved", it) } }
    }

    private fun endLog(id: Long, end: Instant) {
        scope.launch { runCatching { nightLog.get().endMovementLog(id, end) }.onFailure { Log.w(TAG, "Movement log not closed", it) } }
    }

    // ---------------------------------------------------------------- Hush

    /** ACTION_HUSH_START (a UI tap): plays mirror.hush with a fade-in, fading out before its end. */
    private fun startHush() {
        val wanted = coordinator.read().hush ?: return reachForeground()
        ringStoppedHush = false
        val playing = hush
        if (playing != null && playing.color == wanted.hushColor) {
            applyForeground(foregroundMask, onlyIfChanged = false)
            return
        }
        playing?.let { endHushPlayback(it, writeMirror = false) }
        if (!applyForeground(mask(sensing = sensingActive(), hush = true), onlyIfChanged = false)) {
            coordinator.apply { m -> MirrorUpdate(m.copy(hush = null), Unit) }
            hush = null
            return reachForeground()
        }
        val sound = hushOutputs.noise(wanted.hushColor)
        val now = time.now()
        val playback = HushPlayback(wanted.hushColor, sound, startedAt = now)
        hush = playback
        requestHushFocus()
        sound.start(0f)
        playback.job = main.launch { runHush(playback) }
        val color = wanted.hushColor
        scope.launch {
            val id = runCatching { nightLog.get().startHushPlay(now, time.zone().rules.getOffset(now), color) }.getOrNull() ?: return@launch
            playback.playId = id
            coordinator.apply { m -> m.hush?.let { MirrorUpdate(m.copy(hush = it.copy(playId = id)), Unit) } ?: m.unchanged(Unit) }
        }
        updateForeground()
    }

    /** The fade-in, the slow fade-out before the end, and the end itself; follows the mirror (volume, +15 min). */
    private suspend fun runHush(playback: HushPlayback) {
        val hushConfig = config.hush
        while (hush === playback) {
            val wanted = coordinator.read().hush
            if (wanted == null) {
                stopHush()
                return
            }
            val now = time.now()
            val endsAt = wanted.endsAt
            if (endsAt != null && now >= endsAt) {
                stopHush()
                return
            }
            playback.sound.setGain(hushGain(now, playback.startedAt, endsAt, wanted.volume, hushConfig.fadeIn, hushConfig.fadeOut))
            delay(AlarmPlatform.GAIN_STEP_MS)
        }
    }

    /** NightController changed the volume or the end: the gain loop reads the mirror, so only the notification changes. */
    fun onHushChanged() {
        if (hush != null) refreshNotification()
    }

    /** Stop Hush (button, timer end, permanent focus loss). */
    fun stopHush() {
        val playback = hush
        if (playback == null) {
            coordinator.apply { m -> if (m.hush == null) m.unchanged(Unit) else MirrorUpdate(m.copy(hush = null), Unit) }
            updateForeground()
            return
        }
        endHushPlayback(playback, writeMirror = true)
        hush = null
        updateForeground()
    }

    /** A ring starts: Hush stops for good. A focus gain that follows never resumes it. */
    fun stopHushForRing() {
        ringStoppedHush = true
        if (hush != null) stopHush()
    }

    override fun onAudioFocusChange(focusChange: Int) {
        val playback = hush ?: return
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                playback.sound.pause()
                playback.paused = true
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (!ringStoppedHush && playback.paused) {
                playback.sound.resume()
                playback.paused = false
            }
            AudioManager.AUDIOFOCUS_LOSS -> stopHush()
        }
    }

    private fun endHushPlayback(playback: HushPlayback, writeMirror: Boolean) {
        playback.job?.cancel()
        playback.sound.stop()
        focusRequest?.let { request -> getSystemService<AudioManager>()?.abandonAudioFocusRequest(request) }
        focusRequest = null
        val end = time.now()
        val playId = playback.playId ?: coordinator.read().hush?.playId
        if (playId != null) scope.launch { runCatching { nightLog.get().endHushPlay(playId, end) } }
        if (writeMirror) coordinator.apply { m -> MirrorUpdate(m.copy(hush = null), Unit) }
    }

    private fun requestHushFocus() {
        val manager = getSystemService<AudioManager>() ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(this)
            .setWillPauseWhenDucked(true)
            .build()
        manager.requestAudioFocus(request)
        focusRequest = request
    }

    // ---------------------------------------------------------------- Stop and foreground

    /** A planned stop or Reset: unregister, flush, end the logs and the Hush play, then go. */
    fun stopAll() {
        synchronized(lock) {
            windowTimer?.cancel()
            windowRun = null
            wholeNight = null
            stream?.let { closeStream(it, time.now()) }
            stream = null
        }
        hush?.let { endHushPlayback(it, writeMirror = true) }
        hush = null
        updateForeground()
        publishSensing()
    }

    private fun sensingActive(): Boolean = synchronized(lock) { windowRun != null || wholeNight != null }

    private fun mask(sensing: Boolean, hush: Boolean): Int = (if (sensing) TYPE_HEALTH else 0) or (if (hush) TYPE_MEDIA else 0)

    /** The foreground types the current reasons need; none → stopForeground + stopSelf. */
    private fun updateForeground() {
        val wanted = mask(sensingActive(), hush != null)
        if (wanted == 0) {
            if (foregroundMask != 0) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundMask = 0
            stopSelf()
            return
        }
        applyForeground(wanted, onlyIfChanged = true)
    }

    /**
     * Calls startForeground(301, …, [mask]) when the mask changes (or always, for a start that must reach it). Returns
     * false when the system refused it; the mask in force stays as it was.
     */
    private fun applyForeground(mask: Int, onlyIfChanged: Boolean): Boolean {
        if (onlyIfChanged && mask == foregroundMask) {
            refreshNotification()
            return true
        }
        return try {
            ServiceCompat.startForeground(this, AlarmActions.ID_NIGHT, notification(mask), platformTypes(mask))
            foregroundMask = mask
            true
        } catch (e: Exception) {
            Log.w(TAG, "Night service foreground refused (mask $mask)", e)
            false
        }
    }

    /**
     * Every startForegroundService start must reach startForeground. With reasons, re-call it with the mask in force;
     * without any, satisfy it with the health type, then go.
     */
    private fun reachForeground() {
        if (foregroundMask != 0) {
            applyForeground(foregroundMask, onlyIfChanged = false)
            return
        }
        try {
            ServiceCompat.startForeground(this, AlarmActions.ID_NIGHT, notification(TYPE_HEALTH), platformTypes(TYPE_HEALTH))
        } catch (e: Exception) {
            Log.w(TAG, "Placeholder foreground refused", e)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun platformTypes(mask: Int): Int {
        var types = 0
        if (mask and TYPE_HEALTH != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        if (mask and TYPE_MEDIA != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        return types
    }

    private fun refreshNotification() {
        if (foregroundMask != 0) RingNotifications.post(this, AlarmActions.ID_NIGHT, notification(foregroundMask))
    }

    /**
     * 301 on the night channel: "Hush · Pink" / "Movement log on" / "Getting ready for your alarm", and "Never later
     * than 6:30" while this night's alarm is armed ([MirroredAlarm.endsNightOf]). Ongoing, silent, no time shown; Stop
     * Hush and +15 min while Hush plays.
     */
    private fun notification(mask: Int): Notification {
        val mirror = coordinator.read()
        val playing = hush?.color ?: mirror.hush?.hushColor?.takeIf { mask and TYPE_MEDIA != 0 }
        val title = when {
            playing != null -> getString(R.string.alarm_night_hush, getString(colorLabel(playing)))
            wholeNight != null -> getString(R.string.alarm_night_log)
            else -> getString(R.string.alarm_night_window)
        }
        val builder = NotificationCompat.Builder(this, AlarmIntents.CHANNEL_NIGHT)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(ContextCompat.getColor(this, R.color.alarm_accent))
            .setContentTitle(title)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(AlarmPendingIntents.night(this))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        // Only this night's alarm: before a free Saturday, Monday's 6:30 would read as a promise for the morning.
        mirror.armed?.takeIf { it.endsNightOf(time.now(), time.zone(), config) }?.let { armed ->
            builder.setContentText(getString(R.string.alarm_night_never_later, AlarmText.clock(armed.target, time.zone(), mirror.use24h)))
        }
        if (playing != null) {
            builder.addAction(0, getString(R.string.alarm_action_hush_stop), AlarmPendingIntents.hushStop(this))
            builder.addAction(0, getString(R.string.alarm_action_hush_extend), AlarmPendingIntents.hushExtend(this))
        }
        return builder.build()
    }

    private fun colorLabel(color: HushColor): Int = when (color) {
        HushColor.WHITE -> R.string.alarm_hush_white
        HushColor.PINK -> R.string.alarm_hush_pink
        HushColor.BROWN -> R.string.alarm_hush_brown
    }

    private fun publishSensing() {
        val value = synchronized(lock) {
            val active = stream
            when {
                wholeNight != null && active != null -> SensingState(SensingMode.WHOLE_NIGHT, active.start, running = true)
                windowRun != null -> SensingState(SensingMode.WINDOW, windowRun?.since ?: time.now(), running = true)
                else -> null
            }
        }
        nightController.publishSensing(value)
    }

    internal companion object {
        private const val TAG = "SleepSaverNight"
        private const val TYPE_HEALTH = 1
        private const val TYPE_MEDIA = 2

        /** In-process liveness (set in onCreate, cleared in onDestroy); the reconciler's nightServiceRunning. */
        @Volatile var running: Boolean = false

        @Volatile var current: NightService? = null

        /** Hush's gain: volume × fade-in (from the start) × fade-out (before [endsAt]). Pure. */
        fun hushGain(now: Instant, startedAt: Instant, endsAt: Instant?, volume: Float, fadeIn: Duration, fadeOut: Duration): Float {
            val fadeInPart = fraction(Duration.between(startedAt, now), fadeIn)
            val fadeOutPart = if (endsAt == null) 1f else fraction(Duration.between(now, endsAt), fadeOut)
            return volume * fadeInPart * fadeOutPart
        }

        private fun fraction(elapsed: Duration, length: Duration): Float =
            if (length.isZero || length.isNegative) 1f else (elapsed.toMillis().toFloat() / length.toMillis()).coerceIn(0f, 1f)
    }
}

/** One surfacing window run over a movement stream. */
internal class WindowRun(val occurrenceId: String, val night: SurfacingNight, val since: Instant)

/** The opt-in whole-night log of an anchor. */
internal class WholeNight(val anchorId: Long, val endsAt: Instant)

/** Hush as it plays. */
internal class HushPlayback(val color: HushColor, val sound: HushSound, val startedAt: Instant) {
    var job: Job? = null
    var playId: Long? = null
    @Volatile var paused = false
}

/**
 * One stream of phone movement (the window run's, or the whole-night log's, which a window run then shares): the meter,
 * the recent epochs the decider reads (bounded to the longest run), and the epochs not yet written to Room.
 */
internal class MovementStream(
    val start: Instant,
    val offset: ZoneOffset,
    val meter: MovementMeter,
    val source: MotionSource,
    private val power: PowerManager?,
) {
    val epochs = ArrayList<MovementEpoch>()
    val unsaved = ArrayList<MovementEpoch>()
    var logId: Long? = null
    var closed = false
    var endAt: Instant? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** When the held wake lock times out (SystemClock.elapsedRealtime). */
    private var wakeLockUntilMs = 0L

    fun add(closed: List<MovementEpoch>) {
        epochs += closed
        while (epochs.size > MAX_EPOCHS) epochs.removeAt(0)
        closed.filterTo(unsaved) { it.activity != null }
    }

    /**
     * Keeps the CPU awake for [length] from now. The stream holds one lock for all its reasons (the whole-night log's,
     * then a window run's that shares the stream): a later reason extends it when it needs it longer and never cuts it
     * short, so [releaseWakeLock] always releases everything the stream took.
     */
    fun holdWakeLock(length: Duration) {
        if (length.isNegative || length.isZero) return
        val lock = wakeLock ?: power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, AlarmPlatform.SURFACING_WAKE_LOCK_TAG)
            ?.apply { setReferenceCounted(false) }
            ?.also { wakeLock = it }
            ?: return
        val now = SystemClock.elapsedRealtime()
        val until = now + length.toMillis()
        if (lock.isHeld && until <= wakeLockUntilMs) return
        lock.acquire(until - now) // not reference-counted: the new timeout replaces the old one
        wakeLockUntilMs = until
    }

    fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wakeLockUntilMs = 0L
    }

    private companion object {
        /** The longest window run (lead-in + max window + margin) in epochs, plus one. */
        val MAX_EPOCHS: Int = (SleepConfig.DEFAULT.surfacing.maxRunLength.toMillis() / SleepConfig.DEFAULT.surfacing.epoch.toMillis()).toInt() + 1
    }
}
