package com.sleepsaver.core.data.hooks

import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.alarm.SnoozeOffer
import com.sleepsaver.core.domain.journal.AnchorSource
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.schedule.AlarmTone
import com.sleepsaver.core.domain.schedule.WakeStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Wall clock + zone, injectable for tests. */
interface TimeProvider {
    fun now(): Instant
    fun zone(): ZoneId

    /** Emits now() immediately and then at the start of every minute. */
    val minuteTicks: Flow<Instant>
}

/**
 * Implemented in :core:notifications. The sync layer calls it when last night's data shows up,
 * so the morning-review notification can be posted (if enabled and not already reviewed).
 */
interface MorningReviewNotifier {
    fun onLastNightArrived(wakeDate: LocalDate)

    /** Without background reads we can't see data arrive; remind at a fixed time instead. */
    fun scheduleFallbackReminder(at: Instant)

    fun cancel()
}

/**
 * Implemented in :core:notifications. Recomputes and re-arms habit reminders from the current schedule.
 * Safe to call often.
 */
interface ReminderPlanner {
    suspend fun replan()
    fun cancelAll()
}

// ---------------------------------------------------------------- Alarm (implemented in :core:alarm)

/** Exact alarm arming. Safe to call often; never starts a service. */
interface AlarmScheduler {
    suspend fun reschedule()

    /** Cancels every alarm PendingIntent and clears the alarm mirror (Reset all data). */
    fun cancelAll()
}

/** What the phone is doing tonight. Drives Tonight, Night screen, Night steps, ringing screen, Settings, reminders. */
data class NightState(
    /** After Drop anchor, until the morning alarm is stopped, End night, or anchor + nightMaxLength. */
    val night: ActiveNight? = null,
    /** Movement sensing (the whole-night log or a surfacing window run). */
    val sensing: SensingState? = null,
    val hush: HushState? = null,
    val catnap: ActiveCatnap? = null,
    /**
     * Only while a ring is sounding, or stands as its fallback notification because no ring service could start
     * (null while snoozed).
     */
    val ringing: Ringing? = null,
    val snoozedUntil: Instant? = null,
    val testPendingAt: Instant? = null,
    /**
     * When "I'm up" ([NightController.endNight]) was last tapped in this process: NightRules.focus's `endedAt`, so it
     * ends night mode on Tonight, Sleep home, Energy and Night steps at once (NightRules.clockNow). Kept in memory only:
     * after a restart the bedtime rule applies again.
     */
    val endedAt: Instant? = null,
)

/** The night state after Drop anchor. */
data class ActiveNight(val anchorId: Long, val anchoredAt: Instant, val offset: ZoneOffset, val wholeNightLog: Boolean)

/** What the movement stream is for: the opt-in whole-night log, or one surfacing window run. */
enum class SensingMode { WHOLE_NIGHT, WINDOW }

/** [running] false = the service stopped unexpectedly (offer Resume). */
data class SensingState(val mode: SensingMode, val since: Instant, val running: Boolean)

/** Hush as it plays. [volume] is the player gain (0..1). */
data class HushState(
    val color: HushColor,
    val volume: Float,
    /** null = until the window */
    val timer: Duration?,
    val endsAt: Instant?,
)

/** A catnap waiting to ring. */
data class ActiveCatnap(val startedAt: Instant, val ringsAt: Instant, val length: Duration)

/** A ring that is sounding right now. */
data class Ringing(
    val kind: RingKind,
    val reason: RingReason,
    val since: Instant,
    /** The wake time (catnap: its ring time; test: null). */
    val target: Instant?,
    val wakeDate: LocalDate?,
    val earlyBy: Duration,
    val snooze: SnoozeOffer,
    val style: WakeStyle,
    val use24h: Boolean,
    val zone: ZoneId,
) {
    /** Morning feel is offered only after a planned morning alarm (never after a catnap or a test ring). */
    val offersMorningFeel: Boolean get() = kind == RingKind.PLANNED
}

/** Implemented in :core:alarm. Every call returns at once; work happens in services. */
interface NightController {
    val state: StateFlow<NightState>

    /**
     * Drop anchor: saves the anchor, enters the night state, re-runs the alarm scheduler, suppresses tonight's
     * remaining reminders, and starts the whole-night movement log when asked (UI tap = allowed FGS start). null when it
     * couldn't be saved.
     */
    suspend fun dropAnchor(source: AnchorSource, wholeNightLog: Boolean): Long?

    /** Within AnchorConfig.undoWindow: deletes the anchor and leaves the night state. */
    suspend fun undoAnchor(anchorId: Long)

    /** "I'm up": ends the night state and the movement log (the alarm stays armed), and sets [NightState.endedAt]. */
    fun endNight()

    /** After the movement log stopped unexpectedly (UI tap only). */
    fun resumeMovementLog()

    /**
     * [timer] null = "Until my window": until shortly before this night's alarm window (the alarm of the wake date
     * NightRules.wakeDateOf now), else for hush.defaultTimer when this night has no alarm.
     */
    fun startHush(color: HushColor, timer: Duration?, volume: Float)
    fun extendHush(by: Duration)
    fun setHushVolume(volume: Float)
    fun stopHush()

    /** false when a ring or snooze is in progress, or notifications or the alarm channel are blocked (ringUiBlocked). */
    fun startCatnap(length: Duration): Boolean
    fun cancelCatnap()

    /**
     * Rings in AlarmConfig.testRingDelay through the real path; false while ringing/snoozed/test pending, or when
     * notifications or the alarm channel are blocked.
     */
    fun testRing(): Boolean
    fun snooze()
    fun stopRinging()

    /** Saves now when unlocked, else buffers in the device-protected mirror and saves after unlock. */
    fun recordMorningFeel(wakeDate: LocalDate, feel: MorningFeel)
    fun previewTone(tone: AlarmTone, style: WakeStyle, volume: Float)
    fun stopPreview()

    /** Stops services and clears the night state and mirror (Reset all data). */
    fun stopEverything()
}

/** Constants shared by core:alarm, feature:tonight, core:notifications and app. */
object AlarmIntents {
    /** The ringing Activity (declared in feature:tonight, exported=false). Launch it with an explicit component. */
    const val RINGING_ACTIVITY = "com.sleepsaver.feature.tonight.ringing.RingingActivity"
    const val CHANNEL_RING = "alarm_ring"
    const val CHANNEL_QUIET = "alarm_quiet"
    const val CHANNEL_NIGHT = "night"
    const val DEEP_LINK_TONIGHT = "sleepsaver://tonight"
    const val DEEP_LINK_NIGHT = "sleepsaver://night"
    const val DEEP_LINK_NIGHT_STEPS = "sleepsaver://nightsteps"
    const val DEEP_LINK_WEEK_PLAN = "sleepsaver://weekplan"
    const val DEEP_LINK_ALARM_CHECK = "sleepsaver://alarmcheck"
    fun reviewLink(wakeDate: LocalDate): String = "sleepsaver://review?wakeDate=$wakeDate"
}
