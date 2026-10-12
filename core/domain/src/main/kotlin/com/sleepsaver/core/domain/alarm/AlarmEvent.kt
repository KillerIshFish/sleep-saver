package com.sleepsaver.core.domain.alarm

import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.WakeStyle
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** What rang: a planned morning alarm, a Catnap, or "Test ring in 1 minute". */
enum class RingKind { PLANNED, NAP, TEST }

/** Why a ring started ([NEVER_RANG]: it never did). */
enum class RingReason {
    /** Surfacing: a restless moment inside the window. */
    RESTLESS,

    /** Surfacing: the wake time came with no restless moment (sensing ran). */
    DEADLINE,

    /** Surfacing: the phone never moved (probably not on the bed); rang at the wake time. */
    PLACEMENT_FALLBACK,

    /** Surfacing: sensing couldn't run (no sensor, service refused, locked, too few epochs); rang at the wake time. */
    SENSING_UNAVAILABLE,

    /** Fixed mode: the wake time. */
    FIXED_TIME,

    SNOOZE_END,
    CATNAP_END,
    TEST,

    /** Rang late after a reboot or a dead process (within missedGrace). */
    CATCH_UP,

    /** The one backup ring after an unanswered one. */
    BACKUP,

    /**
     * No ring at all: the occurrence was MISSED (the phone was off, or the app dead, past missedGrace) or CANCELLED
     * (switched off) before its first ring. Its firstRingAt is when that ring was due.
     */
    NEVER_RANG,
}

/** How an occurrence ended: Stop, auto-silenced, never rang (MISSED), or switched off before it rang. */
enum class RingOutcome {
    STOPPED,
    TIMED_OUT,
    MISSED,
    CANCELLED,
    ;

    /**
     * That morning's alarm is over: no other plan time rings on its wake date. Not for CANCELLED, which a plan edit
     * records when it switches off or moves a pending ring (a snooze, say), so the time it moved to still rings.
     */
    val endsMorning: Boolean get() = this != CANCELLED
}

/**
 * One alarm occurrence's history (one row per occurrence; snoozes and the backup update it). Example: a 06:30
 * SURFACING alarm that rang at 06:12 on a restless moment has reason RESTLESS and earlyBy 18 min.
 */
data class AlarmEvent(
    val id: Long = 0,
    val kind: RingKind,
    val occurrenceId: String?,
    val wakeDate: LocalDate,
    val target: Instant,
    /** Effective window start (== target for FIXED, catnap, test). */
    val windowStart: Instant,
    val mode: AlarmMode,
    val style: WakeStyle,
    val firstRingAt: Instant,
    val reason: RingReason,
    val maxZ: Double? = null,
    val snoozeTimes: List<Instant> = emptyList(),
    val stoppedAt: Instant? = null,
    val outcome: RingOutcome? = null,
    val sensingStart: Instant? = null,
    val offset: ZoneOffset,
) {
    /** How long before the wake time it first rang (zero at or after it). */
    val earlyBy: Duration get() = maxOf(Duration.ZERO, Duration.between(firstRingAt, target))

    /** False for an occurrence recorded without ever ringing ([RingReason.NEVER_RANG]). */
    val rang: Boolean get() = reason != RingReason.NEVER_RANG
}
