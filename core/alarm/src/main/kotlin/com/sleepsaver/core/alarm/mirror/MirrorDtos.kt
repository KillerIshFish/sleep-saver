package com.sleepsaver.core.alarm.mirror

import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.domain.alarm.AlarmEvent
import com.sleepsaver.core.domain.alarm.DeepPrior
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingOutcome
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.alarm.SensingOutcome
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.AlarmSettings
import com.sleepsaver.core.domain.schedule.AlarmTone
import com.sleepsaver.core.domain.schedule.NightRules
import com.sleepsaver.core.domain.schedule.SnoozeSettings
import com.sleepsaver.core.domain.schedule.SnoozeStyle
import com.sleepsaver.core.domain.schedule.WakeStyle
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

// The device-protected mirror's DTOs (SPEC_V2 §4.3). Kotlinx JSON of these internal types only: domain types are never
// annotated. Enums are stored by name and read back with a default, so a renamed constant never loses the mirror.

/** What put a ring on the extra channel (RING_EXTRA). */
internal enum class ExtraTrigger {
    SNOOZE, TEST, SMART, CATCH_UP, BACKUP, RESUME;

    companion object {
        /** The triggers of a planned morning alarm's pending ring (they keep its occurrence "in progress"). */
        val PLANNED: Set<ExtraTrigger> = setOf(SNOOZE, SMART, CATCH_UP, BACKUP, RESUME)
    }
}

/**
 * One alarm as armed: date + local time (re-resolved after a zone change) and the instant it resolved to, its window,
 * settings and tonight's sensing results. Example: "2026-10-07T06:30" with a 30-min SURFACING window starting 06:00.
 */
@Serializable
internal data class MirroredAlarm(
    val occurrenceId: String,
    val date: String,
    val localTime: String,
    val targetEpochMs: Long,
    val offsetSec: Int,
    val mode: String,
    val baseWindowMin: Long,
    val windowStartEpochMs: Long,
    val debtAware: Boolean,
    val style: String,
    val tone: String,
    val volume: Float,
    val snoozeStyle: String,
    val snoozeFixedMin: Long,
    val snoozeFixedCount: Int,
    val overrunMin: Long,
    val zThreshold: Double,
    val expectedOnsetEpochMs: Long?,
    val sensingStartEpochMs: Long? = null,
    /** SensingOutcome.name once tonight's window run ended (or couldn't start). */
    val sensingOutcome: String? = null,
    val maxZ: Double? = null,
) {
    val target: Instant get() = Instant.ofEpochMilli(targetEpochMs)
    val windowStart: Instant get() = Instant.ofEpochMilli(windowStartEpochMs)
    val offset: ZoneOffset get() = ZoneOffset.ofTotalSeconds(offsetSec)
    val wakeDate: LocalDate get() = LocalDate.parse(date)
    val alarmMode: AlarmMode get() = enumOr(mode, AlarmMode.FIXED)
    val wakeStyle: WakeStyle get() = enumOr(style, WakeStyle.STANDARD)
    val alarmTone: AlarmTone get() = enumOr(tone, AlarmTone.SOFT_CHIMES)
    val outcome: SensingOutcome? get() = sensingOutcome?.let { enumOrNull<SensingOutcome>(it) }
    val isSurfacing: Boolean get() = alarmMode == AlarmMode.SURFACING && windowStartEpochMs < targetEpochMs

    val snooze: SnoozeSettings
        get() = SnoozeSettings(
            style = enumOr(snoozeStyle, SnoozeStyle.TAPERING),
            fixedLength = Duration.ofMinutes(snoozeFixedMin),
            fixedCount = snoozeFixedCount,
            overrun = Duration.ofMinutes(overrunMin),
        )

    /**
     * Whether this alarm ends the night [now] belongs to: its wake date is [NightRules.wakeDateOf] now (the next date
     * from 14:00), the rule Hush plays and anchors are dated by. Example: at 23:00 on Friday a Saturday alarm does; when
     * Saturday has none, Monday's doesn't.
     */
    fun endsNightOf(now: Instant, zone: ZoneId, config: SleepConfig = SleepConfig.DEFAULT): Boolean =
        wakeDate == NightRules.wakeDateOf(now, zone.rules.getOffset(now), config)

    /** Moves the alarm to [target] (a zone change), keeping the window's length. */
    fun resolvedTo(target: Instant, offset: ZoneOffset): MirroredAlarm {
        val shift = target.toEpochMilli() - targetEpochMs
        return copy(
            targetEpochMs = target.toEpochMilli(),
            offsetSec = offset.totalSeconds,
            windowStartEpochMs = windowStartEpochMs + shift,
            expectedOnsetEpochMs = expectedOnsetEpochMs?.plus(shift),
        )
    }

    /** Keeps tonight's sensing results when the same occurrence is re-planned. */
    fun withSensingOf(previous: MirroredAlarm?): MirroredAlarm =
        if (previous == null || previous.occurrenceId != occurrenceId) {
            this
        } else {
            copy(sensingStartEpochMs = previous.sensingStartEpochMs, sensingOutcome = previous.sensingOutcome, maxZ = previous.maxZ)
        }

    companion object {
        /** [zThreshold] = ZTuner.effectiveZ(tuning); [expectedOnset] = expectedOnset(...) for this night. */
        fun from(planned: PlannedAlarm, zThreshold: Double, expectedOnset: Instant?): MirroredAlarm {
            val occurrence = planned.occurrence
            val settings = occurrence.settings
            return MirroredAlarm(
                occurrenceId = occurrence.id,
                date = occurrence.date.toString(),
                localTime = occurrence.localTime.toString(),
                targetEpochMs = occurrence.target.toEpochMilli(),
                offsetSec = occurrence.offset.totalSeconds,
                mode = settings.mode.name,
                baseWindowMin = occurrence.window.toMinutes(),
                windowStartEpochMs = planned.window.start.toEpochMilli(),
                debtAware = planned.window.debtAware,
                style = settings.style.name,
                tone = settings.tone.name,
                volume = settings.volume,
                snoozeStyle = settings.snooze.style.name,
                snoozeFixedMin = settings.snooze.fixedLength.toMinutes(),
                snoozeFixedCount = settings.snooze.fixedCount,
                overrunMin = settings.snooze.overrun.toMinutes(),
                zThreshold = zThreshold,
                expectedOnsetEpochMs = expectedOnset?.toEpochMilli(),
            )
        }

        /**
         * A ring that isn't a planned occurrence (catnap, test): rings at [at] with these settings. Its occurrenceId is
         * empty; the ring's own occurrenceId field is null.
         */
        fun adHoc(at: Instant, offset: ZoneOffset, style: WakeStyle, tone: AlarmTone, volume: Float, snooze: SnoozeSettings): MirroredAlarm =
            MirroredAlarm(
                occurrenceId = "",
                date = at.atOffset(offset).toLocalDate().toString(),
                localTime = at.atOffset(offset).toLocalTime().withNano(0).toString(),
                targetEpochMs = at.toEpochMilli(),
                offsetSec = offset.totalSeconds,
                mode = AlarmMode.FIXED.name,
                baseWindowMin = 0,
                windowStartEpochMs = at.toEpochMilli(),
                debtAware = false,
                style = style.name,
                tone = tone.name,
                volume = volume,
                snoozeStyle = snooze.style.name,
                snoozeFixedMin = snooze.fixedLength.toMinutes(),
                snoozeFixedCount = snooze.fixedCount,
                overrunMin = snooze.overrun.toMinutes(),
                zThreshold = SleepConfig.DEFAULT.surfacing.restlessZ,
                expectedOnsetEpochMs = null,
            )
    }
}

/**
 * The one pending ring of the extra channel: a snooze end, a test ring, a restless (SMART) trigger, a catch-up, the
 * backup ring or a resumed ring. [alarm] is what rings (settings and wake time); [event] is the occurrence's history so
 * far (null before its first ring).
 */
@Serializable
internal data class MirroredExtra(
    val trigger: String,
    val atEpochMs: Long,
    val ringKind: String,
    val occurrenceId: String?,
    val snoozesUsed: Int,
    val reason: String,
    val alarm: MirroredAlarm?,
    val expiresAtEpochMs: Long,
    val eventId: Long? = null,
    val event: MirroredEvent? = null,
    val isBackup: Boolean = false,
) {
    val at: Instant get() = Instant.ofEpochMilli(atEpochMs)
    val extraTrigger: ExtraTrigger get() = enumOr(trigger, ExtraTrigger.RESUME)
    val kind: RingKind get() = enumOr(ringKind, RingKind.PLANNED)
    val ringReason: RingReason get() = enumOr(reason, RingReason.CATCH_UP)

    /** A pending ring of a planned morning alarm (it keeps its occurrence in progress). */
    val isPlanned: Boolean get() = kind == RingKind.PLANNED && extraTrigger in ExtraTrigger.PLANNED
}

/** A catnap waiting to ring on RING_CATNAP. */
@Serializable
internal data class MirroredCatnap(
    val startedAtEpochMs: Long,
    val ringAtEpochMs: Long,
    val lengthMin: Long,
    val tone: String,
    val volume: Float,
    val expiresAtEpochMs: Long,
) {
    val startedAt: Instant get() = Instant.ofEpochMilli(startedAtEpochMs)
    val ringAt: Instant get() = Instant.ofEpochMilli(ringAtEpochMs)
    val length: Duration get() = Duration.ofMinutes(lengthMin)
    val alarmTone: AlarmTone get() = enumOr(tone, AlarmTone.SOFT_CHIMES)
}

/**
 * The ring that is sounding (or was, when the process died). [trigger] is ExtraTrigger.name, or PLAN / CATNAP for a
 * ring straight from RING_PLAN / RING_CATNAP. [fallback]: no ring service could start, so the ring stands only as
 * notification 303 (Stop/Snooze work from the mirror); reconcile lets it time out instead of resuming it every 5 s.
 */
@Serializable
internal data class MirroredRinging(
    val ringKind: String,
    val trigger: String,
    val occurrenceId: String?,
    val sinceEpochMs: Long,
    val snoozesUsed: Int,
    val reason: String,
    val alarm: MirroredAlarm?,
    val eventId: Long?,
    val isBackup: Boolean,
    val expiresAtEpochMs: Long,
    val event: MirroredEvent? = null,
    val fallback: Boolean = false,
) {
    val since: Instant get() = Instant.ofEpochMilli(sinceEpochMs)
    val kind: RingKind get() = enumOr(ringKind, RingKind.PLANNED)
    val ringReason: RingReason get() = enumOr(reason, RingReason.FIXED_TIME)
    val isPlanned: Boolean get() = kind == RingKind.PLANNED
}

/** The night state after Drop anchor. [logId] = the whole-night movement log's row once it exists. */
@Serializable
internal data class MirroredNight(
    val anchorId: Long,
    val anchoredAtEpochMs: Long,
    val offsetSec: Int,
    val wholeNightLog: Boolean,
    val logId: Long?,
    val endsAtEpochMs: Long,
) {
    val anchoredAt: Instant get() = Instant.ofEpochMilli(anchoredAtEpochMs)
    val offset: ZoneOffset get() = ZoneOffset.ofTotalSeconds(offsetSec)
    val endsAt: Instant get() = Instant.ofEpochMilli(endsAtEpochMs)
}

/** Hush as asked for. [timerMin] null = "until my window"; [playId] = the hush_plays row once it exists. */
@Serializable
internal data class MirroredHush(
    val color: String,
    val volume: Float,
    val timerMin: Long?,
    val endsAtEpochMs: Long?,
    val playId: Long?,
) {
    val hushColor: HushColor get() = enumOr(color, HushColor.PINK)
    val endsAt: Instant? get() = endsAtEpochMs?.let(Instant::ofEpochMilli)
    val timer: Duration? get() = timerMin?.let(Duration::ofMinutes)
}

/** An AlarmEvent with every field, as the mirror keeps it (and queues it while the phone is locked). */
@Serializable
internal data class MirroredEvent(
    val id: Long,
    val kind: String,
    val occurrenceId: String?,
    val wakeDate: String,
    val targetEpochMs: Long,
    val windowStartEpochMs: Long,
    val mode: String,
    val style: String,
    val firstRingEpochMs: Long,
    val reason: String,
    val maxZ: Double?,
    val snoozeTimes: List<Long>,
    val stoppedAtEpochMs: Long?,
    val outcome: String?,
    val sensingStartEpochMs: Long?,
    val offsetSec: Int,
) {
    /** Identifies one occurrence's history across updates, also before Room has given it an id. */
    val key: String get() = "$kind|${occurrenceId.orEmpty()}|$firstRingEpochMs"

    fun toDomain(): AlarmEvent = AlarmEvent(
        id = id,
        kind = enumOr(kind, RingKind.PLANNED),
        occurrenceId = occurrenceId,
        wakeDate = LocalDate.parse(wakeDate),
        target = Instant.ofEpochMilli(targetEpochMs),
        windowStart = Instant.ofEpochMilli(windowStartEpochMs),
        mode = enumOr(mode, AlarmMode.FIXED),
        style = enumOr(style, WakeStyle.STANDARD),
        firstRingAt = Instant.ofEpochMilli(firstRingEpochMs),
        reason = enumOr(reason, RingReason.FIXED_TIME),
        maxZ = maxZ,
        snoozeTimes = snoozeTimes.map(Instant::ofEpochMilli),
        stoppedAt = stoppedAtEpochMs?.let(Instant::ofEpochMilli),
        outcome = outcome?.let { enumOrNull<RingOutcome>(it) },
        sensingStart = sensingStartEpochMs?.let(Instant::ofEpochMilli),
        offset = ZoneOffset.ofTotalSeconds(offsetSec),
    )

    fun snoozed(at: Instant): MirroredEvent = copy(snoozeTimes = snoozeTimes + at.toEpochMilli())

    fun ended(outcome: RingOutcome, stoppedAt: Instant? = null): MirroredEvent =
        copy(outcome = outcome.name, stoppedAtEpochMs = stoppedAt?.toEpochMilli() ?: stoppedAtEpochMs)

    companion object {
        fun from(event: AlarmEvent): MirroredEvent = MirroredEvent(
            id = event.id,
            kind = event.kind.name,
            occurrenceId = event.occurrenceId,
            wakeDate = event.wakeDate.toString(),
            targetEpochMs = event.target.toEpochMilli(),
            windowStartEpochMs = event.windowStart.toEpochMilli(),
            mode = event.mode.name,
            style = event.style.name,
            firstRingEpochMs = event.firstRingAt.toEpochMilli(),
            reason = event.reason.name,
            maxZ = event.maxZ,
            snoozeTimes = event.snoozeTimes.map { it.toEpochMilli() },
            stoppedAtEpochMs = event.stoppedAt?.toEpochMilli(),
            outcome = event.outcome?.name,
            sensingStartEpochMs = event.sensingStart?.toEpochMilli(),
            offsetSec = event.offset.totalSeconds,
        )

        /** The first ring of [alarm] (a planned occurrence, or an ad-hoc catnap/test alarm). */
        fun firstRing(kind: RingKind, occurrenceId: String?, alarm: MirroredAlarm, at: Instant, reason: RingReason): MirroredEvent =
            MirroredEvent(
                id = 0,
                kind = kind.name,
                occurrenceId = occurrenceId,
                wakeDate = alarm.date,
                targetEpochMs = alarm.targetEpochMs,
                windowStartEpochMs = alarm.windowStartEpochMs,
                mode = alarm.mode,
                style = alarm.style,
                firstRingEpochMs = at.toEpochMilli(),
                reason = reason.name,
                maxZ = alarm.maxZ.takeIf { kind == RingKind.PLANNED },
                snoozeTimes = emptyList(),
                stoppedAtEpochMs = null,
                outcome = null,
                sensingStartEpochMs = alarm.sensingStartEpochMs.takeIf { kind == RingKind.PLANNED },
                offsetSec = alarm.offsetSec,
            )

        /**
         * The history of an occurrence that ends before its first ring (MISSED while the phone was off, or CANCELLED):
         * reason NEVER_RANG, firstRingAt = [dueAt]. So Wake landing never reads it as "Rang 6:30 AM", and ZTuner never
         * counts it.
         */
        fun neverRang(kind: RingKind, occurrenceId: String?, alarm: MirroredAlarm, dueAt: Instant): MirroredEvent =
            firstRing(kind, occurrenceId, alarm, dueAt, RingReason.NEVER_RANG)
    }
}

/** A Morning feel tapped on the ringing screen before the first unlock. */
@Serializable
internal data class MirroredFeel(val wakeDate: String, val level: Int, val atEpochMs: Long, val offsetSec: Int) {
    val feel: MorningFeel? get() = MorningFeel.of(level)
}

/** A whole-night movement log a stop before the first unlock ended: Room closes it at [endEpochMs] after unlock. */
@Serializable
internal data class MirroredLogEnd(val logId: Long, val endEpochMs: Long)

/** The Garmin deep-sleep prior, so a window run can gate on it without Room. */
@Serializable
internal data class MirroredPrior(val binMin: Long, val sinceOnset: List<Double>, val beforeWake: List<Double>, val nightsUsed: Int) {
    fun toDomain(): DeepPrior = DeepPrior(Duration.ofMinutes(binMin), sinceOnset, beforeWake, nightsUsed)

    companion object {
        fun from(prior: DeepPrior): MirroredPrior =
            MirroredPrior(prior.bin.toMinutes(), prior.sinceOnset, prior.beforeWake, prior.nightsUsed)
    }
}

/** Settings of an armed alarm as the domain sees them. */
internal fun MirroredAlarm.settings(): AlarmSettings =
    AlarmSettings(mode = alarmMode, window = Duration.ofMinutes(baseWindowMin), style = wakeStyle, tone = alarmTone, volume = volume, snooze = snooze)

/** The local wake time, or null when the stored text is broken. */
internal fun MirroredAlarm.localTimeOrNull(): LocalTime? = runCatching { LocalTime.parse(localTime) }.getOrNull()

internal inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }

internal inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E = enumOrNull<E>(name) ?: default
