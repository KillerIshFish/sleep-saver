package com.sleepsaver.core.alarm

import com.sleepsaver.core.alarm.mirror.AlarmAction
import com.sleepsaver.core.alarm.mirror.ExtraTrigger
import com.sleepsaver.core.alarm.mirror.MirrorState
import com.sleepsaver.core.alarm.mirror.MirroredAlarm
import com.sleepsaver.core.alarm.mirror.MirroredEvent
import com.sleepsaver.core.alarm.mirror.MirroredExtra
import com.sleepsaver.core.alarm.mirror.MirroredNight
import com.sleepsaver.core.alarm.mirror.PendingAlarm
import com.sleepsaver.core.alarm.mirror.localTimeOrNull
import com.sleepsaver.core.alarm.ring.RingTransitions
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingOutcome
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.config.SleepConfig
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The mirror after reconcile, plus what it asks for: AlarmManager calls, and Room writes to make once unlocked. */
internal data class Reconciled(
    val state: MirrorState,
    val actions: List<AlarmAction>,
    /** Whole-night movement logs to close: (log id, end). */
    val logsToClose: List<Pair<Long, Instant>> = emptyList(),
    /** Hush plays to end: (play id, end). */
    val hushPlaysToEnd: List<Pair<Long, Instant>> = emptyList(),
    /** The night this reconcile cleared because it ended: a NightService still logging it stops. */
    val endedNight: MirroredNight? = null,
)

/**
 * Resolves every transient mirror state that outlived its process (SPEC_V2 §4.3 Reconcile, §4.4 T11/T14). Pure: the
 * liveness of the two services comes in as flags (their in-process `running` companions, never the mirror).
 *
 * Runs in AlarmBootstrap.startLocked/startUnlocked, at the top of every reschedule and in AlarmBootReceiver.
 */
internal class MirrorReconciler(private val config: SleepConfig = SleepConfig.DEFAULT) {
    private val cfg = config.alarm
    private val transitions = RingTransitions(config)

    /**
     * In this order:
     *  1. [zone] given: `armed` and `upcoming` are re-resolved from their date + local time (a zone change).
     *  2. `ringing` with no live ring service: within ringTimeout it resumes (RESUME on RING_EXTRA at
     *     now + catchUpDelay), except a fallback ring (no service could start), which waits on its notification;
     *     later it is TIMED_OUT (MISSED for the backup), handled, and a planned ring gets its one backup ring, unless
     *     that backup is already more than missedGrace overdue: then it is MISSED with no backup.
     *  3. `extra` / `catnap`: a future ring is re-asserted; one passed by at most missedGrace rings now (a planned one
     *     later than extraMatchTolerance reads CATCH_UP); an older one is dropped, a planned one as MISSED + handled.
     *  4. `armed` with a past target: when its id isn't excluded (handled, sounding, or a pending planned ring) it
     *     catches up within missedGrace, else it is MISSED + handled. Then the next `upcoming` alarm is promoted.
     *  5. `night` past its end is cleared (its movement log closes once unlocked, and a NightService still logging it
     *     stops); `hush` without a live NightService is cleared (Hush never restarts by itself).
     */
    fun reconcile(state: MirrorState, now: Instant, ringServiceRunning: Boolean, nightServiceRunning: Boolean, zone: ZoneId? = null): Reconciled {
        var m = if (zone != null) resolveIn(state, zone) else state
        val actions = mutableListOf<AlarmAction>()
        val logs = mutableListOf<Pair<Long, Instant>>()
        val plays = mutableListOf<Pair<Long, Instant>>()

        // 2. A ring whose service died (a fallback ring never had one: it waits on notification 303 until its timeout).
        val ringing = m.ringing
        if (ringing != null && !ringServiceRunning) {
            val timeoutAt = ringing.since + cfg.ringTimeout
            if (now < timeoutAt && ringing.fallback) {
                // Stop and Snooze on notification 303 answer it from the mirror.
            } else if (now < timeoutAt) {
                val at = now + cfg.catchUpDelay
                val resume = MirroredExtra(
                    trigger = ExtraTrigger.RESUME.name,
                    atEpochMs = at.toEpochMilli(),
                    ringKind = ringing.ringKind,
                    occurrenceId = ringing.occurrenceId,
                    snoozesUsed = ringing.snoozesUsed,
                    reason = ringing.reason,
                    alarm = ringing.alarm,
                    expiresAtEpochMs = (at + cfg.ringTimeout).toEpochMilli(),
                    eventId = ringing.eventId,
                    event = ringing.event,
                    isBackup = ringing.isBackup,
                )
                val pending = m.extra
                m = if (pending == null || !pending.isPlanned || ringing.isPlanned) {
                    actions += AlarmAction.ArmExtra(at)
                    m.copy(ringing = null, extra = resume)
                } else {
                    m.copy(ringing = null) // a pending planned ring wins over a resumed catnap or test ring
                }
            } else {
                // The backup is due at timeoutAt + backupRingDelay. When that is more than missedGrace ago (the phone was
                // off for hours), "Your alarm again" would sound long after it could help: the ring is MISSED instead.
                val dueBackup = timeoutAt + cfg.backupRingDelay
                val backupAt = if (Duration.between(dueBackup, now) > cfg.missedGrace) null else maxOf(dueBackup, now + cfg.catchUpDelay)
                val update = transitions.timeout(m, timeoutAt, backupAt)
                m = update.state
                actions += update.actions
            }
        }

        // 3a. The extra channel.
        m.extra?.let { extra ->
            val late = Duration.between(extra.at, now)
            when {
                extra.at > now -> actions += AlarmAction.ArmExtra(extra.at)
                late <= cfg.missedGrace -> {
                    val at = now + cfg.catchUpDelay
                    val catchUp = extra.isPlanned && late > cfg.extraMatchTolerance
                    m = m.copy(
                        extra = extra.copy(
                            trigger = if (catchUp) ExtraTrigger.CATCH_UP.name else extra.trigger,
                            reason = if (catchUp) RingReason.CATCH_UP.name else extra.reason,
                            atEpochMs = at.toEpochMilli(),
                            expiresAtEpochMs = (at + cfg.ringTimeout).toEpochMilli(),
                        ),
                    )
                    actions += AlarmAction.ArmExtra(at)
                }
                else -> {
                    m = m.copy(extra = null)
                    actions += AlarmAction.Cancel(PendingAlarm.RING_EXTRA)
                    if (extra.isPlanned) {
                        val history = extra.event
                            ?: extra.alarm?.let { MirroredEvent.firstRing(RingKind.PLANNED, extra.occurrenceId, it, extra.at, extra.ringReason) }
                        if (history != null) m = m.queueEvent(history.ended(RingOutcome.MISSED))
                        m = m.handle(extra.occurrenceId, cfg.handledIdsKept)
                    }
                }
            }
        }

        // 3b. The catnap channel.
        m.catnap?.let { catnap ->
            when {
                catnap.ringAt > now -> actions += AlarmAction.ArmCatnap(catnap.ringAt)
                Duration.between(catnap.ringAt, now) <= cfg.missedGrace -> actions += AlarmAction.ArmCatnap(now + cfg.catchUpDelay)
                else -> {
                    m = m.copy(catnap = null)
                    actions += AlarmAction.Cancel(PendingAlarm.RING_CATNAP)
                }
            }
        }

        // 4. The plan channel: an armed alarm whose wake time passed.
        val armed = m.armed
        if (armed != null && armed.target <= now) {
            if (armed.occurrenceId !in m.excludedIds) {
                val pending = m.extra
                if (Duration.between(armed.target, now) <= cfg.missedGrace && (pending == null || !pending.isPlanned)) {
                    val at = now + cfg.catchUpDelay
                    m = m.copy(
                        extra = MirroredExtra(
                            trigger = ExtraTrigger.CATCH_UP.name,
                            atEpochMs = at.toEpochMilli(),
                            ringKind = RingKind.PLANNED.name,
                            occurrenceId = armed.occurrenceId,
                            snoozesUsed = 0,
                            reason = RingReason.CATCH_UP.name,
                            alarm = armed,
                            expiresAtEpochMs = (at + cfg.ringTimeout).toEpochMilli(),
                        ),
                    )
                    actions += AlarmAction.ArmExtra(at)
                } else {
                    val missed = MirroredEvent.firstRing(RingKind.PLANNED, armed.occurrenceId, armed, armed.target, transitions.plannedReason(armed))
                    m = m.queueEvent(missed.ended(RingOutcome.MISSED)).handle(armed.occurrenceId, cfg.handledIdsKept)
                }
            }
            m = promote(m, now)
        }

        // 5. Night and Hush.
        val endedNight = m.night?.takeIf { it.endsAt <= now }
        if (endedNight != null) {
            endedNight.logId?.let { logs += it to endedNight.endsAt }
            m = m.copy(night = null)
        }
        m.hush?.let { hush ->
            if (!nightServiceRunning) {
                hush.playId?.let { plays += it to minOf(now, hush.endsAt ?: now) }
                m = m.copy(hush = null)
            }
        }
        return Reconciled(m, actions, logs, plays, endedNight)
    }

    /** The first upcoming alarm that is still ahead and not excluded becomes `armed`; the ones before it are gone. */
    fun promote(m: MirrorState, now: Instant): MirrorState {
        val excluded = m.excludedIds
        val index = m.upcoming.indexOfFirst { it.target > now && it.occurrenceId !in excluded }
        return if (index < 0) m.copy(armed = null, upcoming = emptyList()) else m.copy(armed = m.upcoming[index], upcoming = m.upcoming.drop(index + 1))
    }

    /** Re-resolves `armed` and `upcoming` in [zone] (gap → forward, overlap → earlier offset, like AlarmPlanner). */
    fun resolveIn(m: MirrorState, zone: ZoneId): MirrorState =
        m.copy(armed = m.armed?.let { resolve(it, zone) }, upcoming = m.upcoming.map { resolve(it, zone) })

    private fun resolve(alarm: MirroredAlarm, zone: ZoneId): MirroredAlarm {
        val time = alarm.localTimeOrNull() ?: return alarm
        val date = runCatching { LocalDate.parse(alarm.date) }.getOrNull() ?: return alarm
        val zoned = ZonedDateTime.of(date, time, zone)
        return if (zoned.toInstant() == alarm.target && zoned.offset == alarm.offset) alarm else alarm.resolvedTo(zoned.toInstant(), zoned.offset)
    }
}
