package com.sleepsaver.core.alarm.ring

import com.sleepsaver.core.alarm.mirror.AlarmAction
import com.sleepsaver.core.alarm.mirror.ExtraTrigger
import com.sleepsaver.core.alarm.mirror.MirrorState
import com.sleepsaver.core.alarm.mirror.MirrorUpdate
import com.sleepsaver.core.alarm.mirror.MirroredAlarm
import com.sleepsaver.core.alarm.mirror.MirroredEvent
import com.sleepsaver.core.alarm.mirror.MirroredExtra
import com.sleepsaver.core.alarm.mirror.MirroredNight
import com.sleepsaver.core.alarm.mirror.MirroredRinging
import com.sleepsaver.core.alarm.mirror.PendingAlarm
import com.sleepsaver.core.alarm.mirror.unchanged
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingOutcome
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.alarm.SensingOutcome
import com.sleepsaver.core.domain.alarm.SnoozeMath
import com.sleepsaver.core.domain.alarm.SnoozeOffer
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.SnoozeSettings
import com.sleepsaver.core.domain.schedule.SnoozeStyle
import com.sleepsaver.core.domain.schedule.WakeStyle
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Which PendingIntent fired: RING_PLAN, RING_EXTRA or RING_CATNAP (the `kind` extra of ACTION_RING). */
internal enum class RingSource { PLAN, EXTRA, CATNAP }

/** A ring broadcast as AlarmRingService receives it. [queued] = rung after the ring that was sounding stopped. */
internal data class RingRequest(val source: RingSource, val occurrenceId: String?, val queued: Boolean = false)

/** What T6 does with a ring broadcast. */
internal sealed interface RingDecision {
    /** Nothing rings ([why] is for the log). */
    data class Ignore(val why: String) : RingDecision

    /** A planned ring that arrived while another one sounds: it rings right after Stop. */
    data class Queue(val request: RingRequest) : RingDecision

    /** [ringing] is now in the mirror; the service makes it sound. [catnapConsumed]: cancel notification 304. */
    data class Start(val ringing: MirroredRinging, val catnapConsumed: Boolean) : RingDecision
}

/** What a Stop (T9) ended. [endedNight]: the night state a planned stop closed (its movement log ends too). */
internal data class StopResult(val kind: RingKind, val occurrenceId: String?, val wasSnoozed: Boolean, val endedNight: MirroredNight?) {
    val planned: Boolean get() = kind == RingKind.PLANNED
}

/**
 * The ring state machine's mirror transitions (SPEC_V2 §4.4 T6, T8, T9, T10), pure so they can be tested without a
 * service. Each returns the new mirror state and the AlarmManager calls; the coordinator commits both under its lock.
 */
internal class RingTransitions(private val config: SleepConfig = SleepConfig.DEFAULT) {
    private val cfg = config.alarm

    /**
     * T6: what [request] rings. [sounding] = the ring AlarmRingService is sounding right now (null when none).
     *
     *  - PLAN rings when its occurrence isn't handled. A duplicate (the sounding occurrence, or one with a pending
     *    planned extra) is ignored; a pending CATCH_UP that never rang is only a stand-in for this very ring, so the
     *    real ring replaces it.
     *  - EXTRA rings `mirror.extra` when its time is within extraMatchTolerance of now.
     *  - CATNAP rings `mirror.catnap`.
     *  - While a ring sounds: a planned ring for another occurrence is queued; a catnap or test ring is dropped; a
     *    duplicate planned extra is cleared (the sounding ring answers it).
     */
    fun onRing(m: MirrorState, request: RingRequest, now: Instant, sounding: MirroredRinging?): MirrorUpdate<RingDecision> = when (request.source) {
        RingSource.PLAN -> onPlan(m, request, now, sounding)
        RingSource.EXTRA -> onExtra(m, request, now, sounding)
        RingSource.CATNAP -> onCatnap(m, now, sounding)
    }

    private fun onPlan(m: MirrorState, request: RingRequest, now: Instant, sounding: MirroredRinging?): MirrorUpdate<RingDecision> {
        val id = request.occurrenceId?.takeIf { it.isNotEmpty() } ?: return m.unchanged(RingDecision.Ignore("no occurrence"))
        if (id in m.handledIds) return m.unchanged(RingDecision.Ignore("already handled"))
        if (sounding != null && sounding.isPlanned && sounding.occurrenceId == id) return m.unchanged(RingDecision.Ignore("already sounding"))
        val alarm = listOfNotNull(m.armed, m.extra?.alarm, m.ringing?.alarm).plus(m.upcoming).firstOrNull { it.occurrenceId == id }
            ?: fallbackAlarm(id, now)
        var state = m
        val actions = mutableListOf<AlarmAction>()
        val extra = m.extra
        if (extra != null && extra.isPlanned && extra.occurrenceId == id) {
            if (extra.extraTrigger != ExtraTrigger.CATCH_UP || extra.event != null) return m.unchanged(RingDecision.Ignore("a pending ring answers it"))
            state = state.copy(extra = null)
            actions += AlarmAction.Cancel(PendingAlarm.RING_EXTRA)
        }
        if (sounding != null) return MirrorUpdate(state, RingDecision.Queue(request), actions)
        val reason = plannedReason(alarm)
        val ringing = ringing(RingKind.PLANNED, TRIGGER_PLAN, id, now, snoozesUsed = 0, reason, alarm, event = null, isBackup = false)
        return start(state, ringing, actions, catnapConsumed = false)
    }

    private fun onExtra(m: MirrorState, request: RingRequest, now: Instant, sounding: MirroredRinging?): MirrorUpdate<RingDecision> {
        val extra = m.extra ?: return m.unchanged(RingDecision.Ignore("no pending extra"))
        if (!request.queued && Duration.between(extra.at, now).abs() > cfg.extraMatchTolerance) {
            return m.unchanged(RingDecision.Ignore("stale extra"))
        }
        val cleared = m.copy(extra = null)
        val cancel = listOf(AlarmAction.Cancel(PendingAlarm.RING_EXTRA))
        if (sounding != null) {
            return when {
                extra.isPlanned && sounding.isPlanned && sounding.occurrenceId == extra.occurrenceId ->
                    MirrorUpdate(cleared, RingDecision.Ignore("the sounding ring answers it"), cancel)
                extra.isPlanned -> m.unchanged(RingDecision.Queue(request.copy(queued = true)))
                else -> MirrorUpdate(cleared, RingDecision.Ignore("dropped while another ring sounds"), cancel)
            }
        }
        val ringAlarm = extra.alarm ?: return MirrorUpdate(cleared, RingDecision.Ignore("extra without an alarm"), cancel)
        val ringing = ringing(
            kind = extra.kind,
            trigger = extra.extraTrigger.name,
            occurrenceId = extra.occurrenceId,
            now = now,
            snoozesUsed = extra.snoozesUsed,
            reason = extra.ringReason,
            alarm = ringAlarm,
            event = extra.event,
            isBackup = extra.isBackup || extra.extraTrigger == ExtraTrigger.BACKUP,
        )
        return start(cleared, ringing, cancel, catnapConsumed = false)
    }

    private fun onCatnap(m: MirrorState, now: Instant, sounding: MirroredRinging?): MirrorUpdate<RingDecision> {
        val catnap = m.catnap ?: return m.unchanged(RingDecision.Ignore("no catnap"))
        val cleared = m.copy(catnap = null)
        val cancel = listOf(AlarmAction.Cancel(PendingAlarm.RING_CATNAP))
        if (sounding != null) return MirrorUpdate(cleared, RingDecision.Ignore("catnap dropped while another ring sounds"), cancel)
        val napSnooze = config.catnap.snooze.multipliedBy(config.catnap.snoozeCount.toLong())
        val napAlarm = MirroredAlarm.adHoc(
            at = catnap.ringAt,
            offset = m.armed?.offset ?: ZoneOffset.UTC,
            style = WakeStyle.STANDARD,
            tone = catnap.alarmTone,
            volume = catnap.volume,
            snooze = SnoozeSettings(style = SnoozeStyle.FIXED, fixedLength = config.catnap.snooze, fixedCount = config.catnap.snoozeCount, overrun = napSnooze),
        )
        val ringing = ringing(RingKind.NAP, TRIGGER_CATNAP, null, now, 0, RingReason.CATNAP_END, napAlarm, event = null, isBackup = false)
        return start(cleared, ringing, cancel, catnapConsumed = true)
    }

    /**
     * T8: snooze the sounding ring. The offer comes from SnoozeMath (capped by the wake time + overrun; after an early
     * ring never past the wake time). null when no snooze is left; then nothing changes.
     */
    fun snooze(m: MirrorState, now: Instant): MirrorUpdate<SnoozeOffer?> {
        val r = m.ringing ?: return m.unchanged(null)
        val ringAlarm = r.alarm ?: return m.unchanged(null)
        val offer = SnoozeMath(config).offer(ringAlarm.snooze, r.snoozesUsed, now, ringAlarm.target)
        val until = offer.until ?: return m.unchanged(null)
        val event = (r.event ?: MirroredEvent.firstRing(r.kind, r.occurrenceId, ringAlarm, r.since, r.ringReason)).snoozed(now)
        val extra = MirroredExtra(
            trigger = ExtraTrigger.SNOOZE.name,
            atEpochMs = until.toEpochMilli(),
            ringKind = r.ringKind,
            occurrenceId = r.occurrenceId,
            snoozesUsed = r.snoozesUsed + 1,
            reason = RingReason.SNOOZE_END.name,
            alarm = ringAlarm,
            expiresAtEpochMs = (until + cfg.ringTimeout).toEpochMilli(),
            eventId = event.id.takeIf { it != 0L },
            event = event,
            isBackup = r.isBackup,
        )
        val state = m.copy(ringing = null, extra = extra).queueEvent(event)
        return MirrorUpdate(state, offer, listOf(AlarmAction.ArmExtra(until)))
    }

    /** The snooze the sounding ring would offer now (for the notification and the ringing screen). */
    fun offer(r: MirroredRinging, now: Instant): SnoozeOffer {
        val ringAlarm = r.alarm ?: return SnoozeOffer(until = null, lastOne = false)
        return SnoozeMath(config).offer(ringAlarm.snooze, r.snoozesUsed, now, ringAlarm.target)
    }

    /**
     * T9: Stop, either of the sounding ring or of a snoozed one (no ring sounding, a SNOOZE pending). The event gets
     * stoppedAt and STOPPED; a planned occurrence becomes handled and ends the night state. null when nothing rings or
     * waits.
     */
    fun stop(m: MirrorState, now: Instant): MirrorUpdate<StopResult?> {
        val r = m.ringing
        if (r != null) {
            var state = m.copy(ringing = null)
            val actions = mutableListOf<AlarmAction>()
            r.event?.let { state = state.queueEvent(it.ended(RingOutcome.STOPPED, now)) }
            val pending = state.extra
            if (pending != null && pending.kind == r.kind && pending.occurrenceId == r.occurrenceId) {
                state = state.copy(extra = null)
                actions += AlarmAction.Cancel(PendingAlarm.RING_EXTRA)
            }
            if (r.isPlanned) state = state.handle(r.occurrenceId, cfg.handledIdsKept).copy(night = null)
            return MirrorUpdate(state, StopResult(r.kind, r.occurrenceId, wasSnoozed = false, endedNight = m.night.takeIf { r.isPlanned }), actions)
        }
        val snoozed = m.extra?.takeIf { it.extraTrigger == ExtraTrigger.SNOOZE } ?: return m.unchanged(null)
        var state = m.copy(extra = null)
        snoozed.event?.let { state = state.queueEvent(it.ended(RingOutcome.STOPPED, now)) }
        val planned = snoozed.kind == RingKind.PLANNED
        if (planned) state = state.handle(snoozed.occurrenceId, cfg.handledIdsKept).copy(night = null)
        return MirrorUpdate(
            state,
            StopResult(snoozed.kind, snoozed.occurrenceId, wasSnoozed = true, endedNight = m.night.takeIf { planned }),
            listOf(AlarmAction.Cancel(PendingAlarm.RING_EXTRA)),
        )
    }

    /**
     * T10: the sounding ring ran out (ringTimeout, the blocked-notifications cap, or testRingLength). The event gets
     * TIMED_OUT, or MISSED when it was the backup ring. A planned occurrence becomes handled and, unless it was the
     * backup, gets the one backup ring at [backupAt].
     */
    fun timeout(m: MirrorState, now: Instant, backupAt: Instant = now + cfg.backupRingDelay): MirrorUpdate<RingOutcome?> {
        val r = m.ringing ?: return m.unchanged(null)
        val outcome = if (r.isBackup) RingOutcome.MISSED else RingOutcome.TIMED_OUT
        var state = m.copy(ringing = null)
        val ended = r.event?.ended(outcome)
        if (ended != null) state = state.queueEvent(ended)
        val actions = mutableListOf<AlarmAction>()
        if (r.isPlanned) {
            state = state.handle(r.occurrenceId, cfg.handledIdsKept)
            val ringAlarm = r.alarm
            val pending = state.extra
            if (!r.isBackup && ringAlarm != null && (pending == null || !pending.isPlanned)) {
                state = state.copy(
                    extra = MirroredExtra(
                        trigger = ExtraTrigger.BACKUP.name,
                        atEpochMs = backupAt.toEpochMilli(),
                        ringKind = RingKind.PLANNED.name,
                        occurrenceId = r.occurrenceId,
                        snoozesUsed = r.snoozesUsed,
                        reason = RingReason.BACKUP.name,
                        alarm = ringAlarm,
                        expiresAtEpochMs = (backupAt + cfg.ringTimeout).toEpochMilli(),
                        eventId = ended?.id?.takeIf { it != 0L },
                        event = ended,
                        isBackup = true,
                    ),
                )
                actions += AlarmAction.ArmExtra(backupAt)
            }
        }
        return MirrorUpdate(state, outcome, actions)
    }

    /**
     * T5's fallback: the ring started at [since] couldn't get a foreground service, so it stands only as notification
     * 303. Marked so reconcile doesn't resume it every few seconds while the start keeps being refused.
     */
    fun markFallback(m: MirrorState, since: Instant): MirrorUpdate<Unit> {
        val r = m.ringing?.takeIf { it.since == since } ?: return m.unchanged(Unit)
        return MirrorUpdate(m.copy(ringing = r.copy(fallback = true)), Unit)
    }

    /** How a planned ring at the wake time is logged: FIXED_TIME, or by tonight's sensing outcome. */
    fun plannedReason(alarm: MirroredAlarm): RingReason = when {
        alarm.alarmMode != AlarmMode.SURFACING -> RingReason.FIXED_TIME
        else -> when (alarm.outcome) {
            SensingOutcome.NO_TRIGGER, SensingOutcome.RESTLESS -> RingReason.DEADLINE
            SensingOutcome.FLAT -> RingReason.PLACEMENT_FALLBACK
            SensingOutcome.TOO_FEW_EPOCHS, SensingOutcome.UNAVAILABLE, null -> RingReason.SENSING_UNAVAILABLE
        }
    }

    private fun ringing(
        kind: RingKind,
        trigger: String,
        occurrenceId: String?,
        now: Instant,
        snoozesUsed: Int,
        reason: RingReason,
        alarm: MirroredAlarm,
        event: MirroredEvent?,
        isBackup: Boolean,
    ): MirroredRinging {
        // The first ring of an occurrence starts its history; later rings (snooze end, backup, resume) update it.
        val history = event ?: MirroredEvent.firstRing(kind, occurrenceId, alarm, now, reason)
        return MirroredRinging(
            ringKind = kind.name,
            trigger = trigger,
            occurrenceId = occurrenceId,
            sinceEpochMs = now.toEpochMilli(),
            snoozesUsed = snoozesUsed,
            reason = reason.name,
            alarm = alarm,
            eventId = history.id.takeIf { it != 0L },
            isBackup = isBackup,
            expiresAtEpochMs = (now + cfg.ringTimeout + cfg.ringingExpiryMargin).toEpochMilli(),
            event = history,
        )
    }

    private fun start(m: MirrorState, ringing: MirroredRinging, actions: List<AlarmAction>, catnapConsumed: Boolean): MirrorUpdate<RingDecision> {
        val state = m.copy(ringing = ringing).let { s -> ringing.event?.let(s::queueEvent) ?: s }
        return MirrorUpdate(state, RingDecision.Start(state.ringing ?: ringing, catnapConsumed), actions)
    }

    /** A RING_PLAN whose alarm is no longer in the mirror still rings, at its own time with default settings. */
    private fun fallbackAlarm(id: String, now: Instant): MirroredAlarm {
        val date = runCatching { LocalDate.parse(id.substringBefore('T')) }.getOrNull()
        val time = runCatching { LocalTime.parse(id.substringAfter('T')) }.getOrNull()
        val base = MirroredAlarm.adHoc(now, ZoneOffset.UTC, cfg.defaultWeekdayStyle, cfg.defaultTone, cfg.defaultVolume, SnoozeSettings())
        return base.copy(occurrenceId = id, date = date?.toString() ?: base.date, localTime = time?.toString() ?: base.localTime)
    }

    companion object {
        /** MirroredRinging.trigger of a ring straight from RING_PLAN / RING_CATNAP. */
        const val TRIGGER_PLAN = "PLAN"
        const val TRIGGER_CATNAP = "CATNAP"
    }
}
