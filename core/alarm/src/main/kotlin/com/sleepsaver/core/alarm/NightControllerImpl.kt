package com.sleepsaver.core.alarm

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.sleepsaver.core.alarm.mirror.AlarmAction
import com.sleepsaver.core.alarm.mirror.ExtraTrigger
import com.sleepsaver.core.alarm.mirror.MirrorState
import com.sleepsaver.core.alarm.mirror.MirrorUpdate
import com.sleepsaver.core.alarm.mirror.MirroredAlarm
import com.sleepsaver.core.alarm.mirror.MirroredCatnap
import com.sleepsaver.core.alarm.mirror.MirroredExtra
import com.sleepsaver.core.alarm.mirror.MirroredFeel
import com.sleepsaver.core.alarm.mirror.MirroredHush
import com.sleepsaver.core.alarm.mirror.MirroredLogEnd
import com.sleepsaver.core.alarm.mirror.MirroredNight
import com.sleepsaver.core.alarm.mirror.PendingAlarm
import com.sleepsaver.core.alarm.mirror.unchanged
import com.sleepsaver.core.alarm.night.NightService
import com.sleepsaver.core.alarm.ring.AlarmRingService
import com.sleepsaver.core.alarm.ring.RingNotifications
import com.sleepsaver.core.alarm.ring.RingOutputs
import com.sleepsaver.core.alarm.ring.RingSound
import com.sleepsaver.core.alarm.ring.RingTransitions
import com.sleepsaver.core.alarm.ring.StopResult
import com.sleepsaver.core.data.di.ApplicationScope
import com.sleepsaver.core.data.hooks.ActiveCatnap
import com.sleepsaver.core.data.hooks.ActiveNight
import com.sleepsaver.core.data.hooks.HushState
import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.hooks.NightState
import com.sleepsaver.core.data.hooks.ReminderPlanner
import com.sleepsaver.core.data.hooks.Ringing
import com.sleepsaver.core.data.hooks.SensingMode
import com.sleepsaver.core.data.hooks.SensingState
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.JournalRepository
import com.sleepsaver.core.data.repository.NightLogRepository
import com.sleepsaver.core.domain.alarm.CatnapPlanner
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.AnchorSource
import com.sleepsaver.core.domain.journal.FeelSource
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.schedule.AlarmTone
import com.sleepsaver.core.domain.schedule.SnoozeSettings
import com.sleepsaver.core.domain.schedule.SnoozeStyle
import com.sleepsaver.core.domain.schedule.WakeStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the phone is doing tonight, and the UI's handle on it (SPEC_V2 §3.6, §4.10). [state] is derived from the
 * reconciled mirror (night, Hush, catnap, snooze, test) plus what the running services publish (the sounding ring,
 * movement sensing). Every call returns at once; the work happens in the services and on the app scope.
 *
 * Direct-boot safe: context, the coordinator, time, the app scope and Lazy repositories (resolved only when unlocked);
 * the ring outputs only take a context.
 */
@Singleton
internal class NightControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coordinator: AlarmCoordinator,
    private val time: TimeProvider,
    @ApplicationScope private val scope: CoroutineScope,
    private val scheduler: dagger.Lazy<AlarmSchedulerImpl>,
    private val nightLog: dagger.Lazy<NightLogRepository>,
    private val journal: dagger.Lazy<JournalRepository>,
    private val reminderPlanner: dagger.Lazy<ReminderPlanner>,
    private val ringOutputs: RingOutputs,
) : NightController {

    private val config = SleepConfig.DEFAULT
    private val transitions = RingTransitions(config)
    private val lock = Any()
    private var mirror = MirrorState()
    private var ringing: Ringing? = null
    private var sensing: SensingState? = null
    private val mutableState = MutableStateFlow(NightState())
    private var preview: RingSound? = null
    private var previewJob: Job? = null

    override val state: StateFlow<NightState> = mutableState.asStateFlow()

    init {
        // Called under the coordinator's lock; never call the coordinator while holding [lock] (lock order).
        coordinator.addListener { m -> synchronized(lock) { accept(m) } }
        refresh()
    }

    /** Re-reads the mirror (after a reconcile that ran before this object listened, or a liveness change). */
    fun refresh() {
        val m = coordinator.read()
        synchronized(lock) { accept(m) }
    }

    /** AlarmRingService publishes the sounding ring (null when it stops, snoozes or times out). */
    fun publishRinging(value: Ringing?) = synchronized(lock) {
        ringing = value
        recompute()
    }

    /** NightService publishes its movement sensing (null when it isn't sensing). */
    fun publishSensing(value: SensingState?) = synchronized(lock) {
        sensing = value
        recompute()
    }

    // ---------------------------------------------------------------- Drop anchor and the night state

    override suspend fun dropAnchor(source: AnchorSource, wholeNightLog: Boolean): Long? {
        val now = time.now()
        val offset = time.zone().rules.getOffset(now)
        val id = try {
            nightLog.get().addAnchor(now, offset, source, wholeNightLog)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Anchor not saved", e)
            return null
        }
        coordinator.apply { m ->
            val night = MirroredNight(id, now.toEpochMilli(), offset.totalSeconds, wholeNightLog, logId = null, endsAtEpochMs = (now + config.anchor.nightMaxLength).toEpochMilli())
            MirrorUpdate(m.copy(night = night), Unit)
        }
        if (wholeNightLog) startNightService(AlarmActions.ACTION_LOG_START)
        replanAfterAnchor()
        return id
    }

    override suspend fun undoAnchor(anchorId: Long) {
        val removed = coordinator.apply { m ->
            if (m.night?.anchorId == anchorId) MirrorUpdate(m.copy(night = null), true) else m.unchanged(false)
        }
        if (removed) NightService.current?.stopWholeNightLog()
        try {
            nightLog.get().deleteAnchor(anchorId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Anchor $anchorId not deleted", e)
        }
        replanAfterAnchor()
    }

    override fun endNight() {
        val ended = coordinator.apply { m -> MirrorUpdate(m.copy(night = null), m.night) }
        val service = NightService.current
        if (service != null) {
            service.stopWholeNightLog()
        } else {
            // The log's service is gone (it stopped unexpectedly): close its row here, or it stays open for good.
            closeOrphanLog(ended?.logId)
        }
    }

    override fun resumeMovementLog() {
        if (coordinator.read().night?.wholeNightLog == true) startNightService(AlarmActions.ACTION_LOG_START)
    }

    // ---------------------------------------------------------------- Hush

    override fun startHush(color: HushColor, timer: Duration?, volume: Float) {
        val now = time.now()
        val hush = config.hush
        coordinator.apply { m ->
            // "Until my window" fades out before sensing starts (the window start − lead-in; the wake time for FIXED).
            val untilWindow = m.armed?.let { it.windowStart - config.surfacing.sensingLeadIn }?.takeIf { it > now }
            val endsAt = timer?.let { now + it } ?: untilWindow ?: (now + hush.defaultTimer)
            val mirrored = MirroredHush(
                color = color.name,
                volume = volume.coerceIn(hush.minVolume, 1f),
                timerMin = timer?.toMinutes(),
                endsAtEpochMs = endsAt.toEpochMilli(),
                playId = m.hush?.takeIf { it.color == color.name }?.playId,
            )
            MirrorUpdate(m.copy(hush = mirrored), Unit)
        }
        startNightService(AlarmActions.ACTION_HUSH_START)
    }

    override fun extendHush(by: Duration) {
        coordinator.apply { m ->
            val hush = m.hush ?: return@apply m.unchanged(Unit)
            MirrorUpdate(m.copy(hush = hush.copy(endsAtEpochMs = hush.endsAtEpochMs?.plus(by.toMillis()))), Unit)
        }
        NightService.current?.onHushChanged()
    }

    override fun setHushVolume(volume: Float) {
        coordinator.apply { m ->
            val hush = m.hush ?: return@apply m.unchanged(Unit)
            MirrorUpdate(m.copy(hush = hush.copy(volume = volume.coerceIn(config.hush.minVolume, 1f))), Unit)
        }
        NightService.current?.onHushChanged()
    }

    override fun stopHush() {
        val service = NightService.current
        if (service != null) {
            service.stopHush()
        } else {
            coordinator.apply { m -> MirrorUpdate(m.copy(hush = null), Unit) }
        }
    }

    // ---------------------------------------------------------------- Catnap and test ring

    override fun startCatnap(length: Duration): Boolean {
        if (ringingNow() || AlarmChannels.ringUiBlocked(context)) return false
        val now = time.now()
        val ringsAt = CatnapPlanner(config).plan(now, length, schedule = null, caffeineCutoff = null).ringsAt
        val started = coordinator.apply { m ->
            if (m.ringing != null || m.extra?.isPlanned == true) return@apply m.unchanged(false)
            val catnap = MirroredCatnap(
                startedAtEpochMs = now.toEpochMilli(),
                ringAtEpochMs = ringsAt.toEpochMilli(),
                lengthMin = length.toMinutes(),
                tone = m.armed?.tone ?: config.alarm.defaultTone.name,
                volume = m.armed?.volume ?: config.alarm.defaultVolume,
                expiresAtEpochMs = (ringsAt + config.alarm.ringTimeout).toEpochMilli(),
            )
            MirrorUpdate(m.copy(catnap = catnap), true, listOf(AlarmAction.ArmCatnap(ringsAt)))
        }
        if (started) RingNotifications.post(context, AlarmActions.ID_CATNAP, RingNotifications.catnap(context, ringsAt, time.zone(), mirror.use24h))
        return started
    }

    override fun cancelCatnap() {
        coordinator.apply { m -> MirrorUpdate(m.copy(catnap = null), Unit, listOf(AlarmAction.Cancel(PendingAlarm.RING_CATNAP))) }
        RingNotifications.cancel(context, AlarmActions.ID_CATNAP)
    }

    override fun testRing(): Boolean {
        if (ringingNow() || AlarmChannels.ringUiBlocked(context)) return false
        val now = time.now()
        val at = now + config.alarm.testRingDelay
        return coordinator.apply { m ->
            // One pending ring per channel: no test while snoozed, catching up or another test waits.
            if (m.ringing != null || m.extra != null) return@apply m.unchanged(false)
            val armed = m.armed
            val alarm = MirroredAlarm.adHoc(
                at = at,
                offset = time.zone().rules.getOffset(at),
                style = armed?.wakeStyle ?: config.alarm.defaultWeekdayStyle,
                tone = armed?.alarmTone ?: config.alarm.defaultTone,
                volume = armed?.volume ?: config.alarm.defaultVolume,
                snooze = SnoozeSettings(style = SnoozeStyle.NONE),
            )
            val extra = MirroredExtra(
                trigger = ExtraTrigger.TEST.name,
                atEpochMs = at.toEpochMilli(),
                ringKind = RingKind.TEST.name,
                occurrenceId = null,
                snoozesUsed = 0,
                reason = RingReason.TEST.name,
                alarm = alarm,
                expiresAtEpochMs = (at + config.alarm.ringTimeout).toEpochMilli(),
            )
            MirrorUpdate(m.copy(extra = extra), true, listOf(AlarmAction.ArmExtra(at)))
        }
    }

    // ---------------------------------------------------------------- The ring

    override fun snooze() {
        val service = AlarmRingService.current
        if (service != null && service.isSounding) service.snooze() else snoozeFromMirror()
    }

    override fun stopRinging() {
        val service = AlarmRingService.current
        if (service != null && service.isSounding) service.stop() else stopFromMirror()
    }

    /** Snooze when no service sounds (the FGS was refused and notification 303 stands in for it). */
    fun snoozeFromMirror() {
        val now = time.now()
        val offer = coordinator.apply { m -> transitions.snooze(m, now) } ?: return
        val until = offer.until ?: return
        RingNotifications.cancel(context, AlarmActions.ID_RING_FALLBACK)
        RingNotifications.post(context, AlarmActions.ID_SNOOZED, RingNotifications.snoozed(context, until, offer.lastOne, time.zone(), mirror.use24h))
        scheduler.get().flushEventsAsync()
    }

    /** Stop when no service sounds: a snoozed ring (notification 302's Stop) or a fallback ring (303). */
    fun stopFromMirror() {
        val now = time.now()
        val result = coordinator.apply { m -> transitions.stop(m, now) } ?: return
        afterStop(result)
    }

    /**
     * T9's follow-up, after the mirror transition: notifications 302/303 go, a planned stop ends the night (and the
     * movement log and Hush), then the plan moves on (reschedule when unlocked, else a locked re-arm from `upcoming`).
     */
    fun afterStop(result: StopResult) {
        RingNotifications.cancel(context, AlarmActions.ID_SNOOZED, AlarmActions.ID_RING_FALLBACK)
        val scheduler = scheduler.get()
        if (result.planned) {
            val service = NightService.current
            if (service != null) service.stopAll() else closeOrphanLog(result.endedNight?.logId)
        }
        scheduler.replanAfterRing()
        scheduler.flushEventsAsync()
    }

    override fun recordMorningFeel(wakeDate: LocalDate, feel: MorningFeel) {
        val now = time.now()
        val offset = time.zone().rules.getOffset(now)
        if (scheduler.get().unlocked()) {
            scope.launch {
                try {
                    journal.get().setFeel(wakeDate, feel, FeelSource.RING, now, offset)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Morning feel not saved; buffering it", e)
                    bufferFeel(wakeDate, feel, now.toEpochMilli(), offset.totalSeconds)
                }
            }
        } else {
            bufferFeel(wakeDate, feel, now.toEpochMilli(), offset.totalSeconds)
        }
    }

    override fun previewTone(tone: AlarmTone, style: WakeStyle, volume: Float) {
        stopPreview()
        val sound = ringOutputs.sound(tone, locked = !scheduler.get().unlocked())
        preview = sound
        sound.start(volume.coerceIn(config.alarm.minVolume, 1f))
        // On the main thread, like the UI calls, and only for this preview: a newer one started meanwhile keeps playing.
        previewJob = scope.launch(Dispatchers.Main) {
            delay(config.alarm.previewLength.toMillis())
            if (preview === sound) stopPreview()
        }
    }

    override fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        preview?.stop()
        preview = null
    }

    override fun stopEverything() {
        stopPreview()
        AlarmRingService.current?.stopEverything()
        NightService.current?.stopAll()
        runCatching { context.stopService(android.content.Intent(context, AlarmRingService::class.java)) }
        runCatching { context.stopService(android.content.Intent(context, NightService::class.java)) }
        RingNotifications.cancelAll(context)
        coordinator.clear()
        synchronized(lock) {
            ringing = null
            sensing = null
            mirror = MirrorState()
            recompute()
        }
    }

    // ---------------------------------------------------------------- Internals

    /**
     * Ends the whole-night log row [logId] at now when no NightService holds it. Room only opens once unlocked: before
     * that the end waits in the mirror, and AlarmBootstrap.startUnlocked writes it.
     */
    private fun closeOrphanLog(logId: Long?) {
        if (logId == null) return
        val end = time.now()
        if (!scheduler.get().unlocked()) {
            coordinator.apply { m -> MirrorUpdate(m.copy(pendingLogEnds = m.pendingLogEnds + MirroredLogEnd(logId, end.toEpochMilli())), Unit) }
            return
        }
        scope.launch { runCatching { nightLog.get().endMovementLog(logId, end) }.onFailure { Log.w(TAG, "Movement log not closed", it) } }
    }

    private fun ringingNow(): Boolean = synchronized(lock) { ringing != null } || AlarmRingService.current?.isSounding == true

    private fun bufferFeel(wakeDate: LocalDate, feel: MorningFeel, atMs: Long, offsetSec: Int) {
        coordinator.apply { m -> MirrorUpdate(m.copy(pendingFeel = MirroredFeel(wakeDate.toString(), feel.level, atMs, offsetSec)), Unit) }
    }

    /** Re-plans the alarm (debt-aware window, expected onset) and tonight's reminders (overnight suppression). */
    private fun replanAfterAnchor() {
        scope.launch {
            try {
                scheduler.get().reschedule()
                reminderPlanner.get().replan()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Re-planning after the anchor failed", e)
            }
        }
    }

    /** A UI action (app in the foreground): starting a foreground service is allowed. */
    private fun startNightService(action: String) {
        try {
            ContextCompat.startForegroundService(context, AlarmPendingIntents.nightServiceIntent(context, action))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Night service not started", e)
            refusedNightService(action)
        } catch (e: SecurityException) {
            Log.w(TAG, "Night service not started", e)
            refusedNightService(action)
        }
    }

    private fun refusedNightService(action: String) {
        if (action == AlarmActions.ACTION_HUSH_START) coordinator.apply { m -> MirrorUpdate(m.copy(hush = null), Unit) }
        refresh()
    }

    /** Must hold [lock]. A read older than the last apply seen is ignored. */
    private fun accept(m: MirrorState) {
        if (m.generation < mirror.generation && m != MirrorState()) return
        mirror = m
        recompute()
    }

    /** Must hold [lock]. */
    private fun recompute() {
        val m = mirror
        val night = m.night
        val liveSensing = sensing ?: night?.takeIf { it.wholeNightLog && !NightService.running }?.let {
            SensingState(SensingMode.WHOLE_NIGHT, it.anchoredAt, running = false)
        }
        mutableState.value = NightState(
            night = night?.let { ActiveNight(it.anchorId, it.anchoredAt, it.offset, it.wholeNightLog) },
            sensing = liveSensing,
            hush = m.hush?.let { HushState(it.hushColor, it.volume, it.timer, it.endsAt) },
            catnap = m.catnap?.let { ActiveCatnap(it.startedAt, it.ringAt, it.length) },
            ringing = ringing,
            snoozedUntil = m.extra?.takeIf { it.extraTrigger == ExtraTrigger.SNOOZE }?.at,
            testPendingAt = m.extra?.takeIf { it.extraTrigger == ExtraTrigger.TEST }?.at,
        )
    }

    private companion object {
        const val TAG = "SleepSaverAlarm"
    }
}
