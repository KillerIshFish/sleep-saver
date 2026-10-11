package com.sleepsaver.core.alarm.mirror

import java.time.Instant

/**
 * Everything the device-protected mirror holds, one field per key (SPEC_V2 §4.3). The plan channel is [armed] (+ the
 * RING_PLAN and WINDOW alarms); the extra channels are [extra] (RING_EXTRA) and [catnap] (RING_CATNAP), each with at
 * most one pending ring.
 */
internal data class MirrorState(
    val armed: MirroredAlarm? = null,
    /** The next alarm.followingMirrored alarms after [armed], for a locked re-arm. */
    val upcoming: List<MirroredAlarm> = emptyList(),
    val extra: MirroredExtra? = null,
    val catnap: MirroredCatnap? = null,
    val ringing: MirroredRinging? = null,
    val night: MirroredNight? = null,
    val hush: MirroredHush? = null,
    /** Occurrences stopped, timed out or missed: never planned again. Newest first, at most alarm.handledIdsKept. */
    val handledIds: List<String> = emptyList(),
    /** Alarm events not yet in Room (the outbox): written while locked, or waiting for the next flush. */
    val pendingEvents: List<MirroredEvent> = emptyList(),
    val pendingFeel: MirroredFeel? = null,
    /** Movement logs to close once unlocked (a planned stop before the first unlock ended their night). */
    val pendingLogEnds: List<MirroredLogEnd> = emptyList(),
    val prior: MirroredPrior? = null,
    val use24h: Boolean = false,
    /** Incremented by every coordinator apply (diagnostics and tests; not a gate). */
    val generation: Long = 0,
) {
    /** Occurrences that are sounding, snoozed, resuming, catching up or about to ring on a restless trigger. */
    val inProgressIds: Set<String>
        get() = setOfNotNull(ringing?.takeIf { it.isPlanned }?.occurrenceId, extra?.takeIf { it.isPlanned }?.occurrenceId)

    /** What a reschedule passes to computeAlarmPlan (it adds the handled ids stored in Room). */
    val excludedIds: Set<String> get() = handledIds.toSet() + inProgressIds

    /** [id] answered: kept newest first, trimmed to [kept]. */
    fun handle(id: String?, kept: Int): MirrorState =
        if (id.isNullOrEmpty()) this else copy(handledIds = (listOf(id) + handledIds.filterNot { it == id }).take(kept))

    /**
     * Puts [event] in the outbox, replacing an older version of the same occurrence's history, and updates the copies
     * that [ringing] and [extra] carry.
     */
    fun queueEvent(event: MirroredEvent): MirrorState = copy(
        pendingEvents = pendingEvents.filterNot { it.key == event.key } + event,
        ringing = ringing?.let { r -> if (r.event?.key == event.key) r.copy(event = event, eventId = event.id.takeIf { it != 0L }) else r },
        extra = extra?.let { x -> if (x.event?.key == event.key) x.copy(event = event, eventId = event.id.takeIf { it != 0L }) else x },
    )

    /**
     * Room stored [flushed] as row [id]: drop it from the outbox (or, when a newer version of it is waiting, give that
     * version the id so the next flush updates the same row), and give the copies in [ringing] and [extra] the id.
     */
    fun afterFlush(flushed: MirroredEvent, id: Long): MirrorState {
        fun withId(event: MirroredEvent?): MirroredEvent? =
            if (event != null && event.key == flushed.key && event.id == 0L) event.copy(id = id) else event
        return copy(
            pendingEvents = pendingEvents.mapNotNull { pending ->
                when {
                    pending.key != flushed.key -> pending
                    pending == flushed -> null
                    else -> withId(pending)
                }
            },
            ringing = ringing?.let { r -> withId(r.event)?.let { e -> r.copy(event = e, eventId = e.id.takeIf { it != 0L }) } ?: r },
            extra = extra?.let { x -> withId(x.event)?.let { e -> x.copy(event = e, eventId = e.id.takeIf { it != 0L }) } ?: x },
        )
    }
}

/** An AlarmManager call the coordinator performs after committing a mirror change. */
internal sealed interface AlarmAction {
    /** setAlarmClock(target, RING_PLAN) for [occurrenceId] (setWindow early when exact alarms aren't allowed). */
    data class ArmPlan(val at: Instant, val occurrenceId: String) : AlarmAction

    /** setExactAndAllowWhileIdle(at, WINDOW) (no WINDOW when exact alarms aren't allowed). */
    data class ArmWindow(val at: Instant, val occurrenceId: String) : AlarmAction

    /** setAlarmClock(at, RING_EXTRA). */
    data class ArmExtra(val at: Instant) : AlarmAction

    /** setAlarmClock(at, RING_CATNAP). */
    data class ArmCatnap(val at: Instant) : AlarmAction

    data class Cancel(val alarm: PendingAlarm) : AlarmAction
}

/** The four AlarmManager PendingIntents core:alarm owns. */
internal enum class PendingAlarm { RING_PLAN, WINDOW, RING_EXTRA, RING_CATNAP }

/** The result of one coordinator apply: the new mirror, a value for the caller, and the AlarmManager calls to make. */
internal data class MirrorUpdate<out T>(val state: MirrorState, val value: T, val actions: List<AlarmAction> = emptyList())

/** No change, returning [value]. */
internal fun <T> MirrorState.unchanged(value: T): MirrorUpdate<T> = MirrorUpdate(this, value)
