package com.sleepsaver.core.data.mapping

import com.sleepsaver.core.data.db.AlarmEventEntity
import com.sleepsaver.core.data.db.AlarmTuningEntity
import com.sleepsaver.core.data.db.AnchorEntity
import com.sleepsaver.core.data.db.HushPlayEntity
import com.sleepsaver.core.data.db.MovementEpochEntity
import com.sleepsaver.core.data.db.MovementLogEntity
import com.sleepsaver.core.data.repository.MovementTrace
import com.sleepsaver.core.domain.alarm.AlarmEvent
import com.sleepsaver.core.domain.alarm.AlarmTuning
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.alarm.MovementEpoch
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingOutcome
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.alarm.TuningReason
import com.sleepsaver.core.domain.journal.Anchor
import com.sleepsaver.core.domain.journal.AnchorSource
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.WakeStyle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ---------------------------------------------------------------- Anchors

/** An unknown source reads as TONIGHT: the tap itself (its time) is what matters. */
fun AnchorEntity.toDomain(): Anchor = Anchor(
    id = id,
    at = Instant.ofEpochMilli(atEpochMs),
    offset = ZoneOffset.ofTotalSeconds(offsetSec),
    wakeDate = LocalDate.parse(wakeDate),
    source = enumOrNull<AnchorSource>(source) ?: AnchorSource.TONIGHT,
    wholeNightLog = wholeNightLog,
)

/** id 0 inserts a new row. */
fun Anchor.toEntity(): AnchorEntity = AnchorEntity(
    id = id,
    atEpochMs = at.toEpochMilli(),
    offsetSec = offset.totalSeconds,
    wakeDate = wakeDate.toString(),
    source = source.name,
    wholeNightLog = wholeNightLog,
)

// ---------------------------------------------------------------- Movement

fun MovementEpochEntity.toDomain(): MovementEpoch = MovementEpoch(start = Instant.ofEpochMilli(startEpochMs), activity = activity)

/** null for a missing epoch (a missing slot has no row). */
fun MovementEpoch.toEntityOrNull(logId: Long): MovementEpochEntity? =
    activity?.let { MovementEpochEntity(logId = logId, startEpochMs = start.toEpochMilli(), activity = it) }

/** The log with its epochs ([epochs] may hold other logs' rows; only this log's are kept, oldest first). */
fun MovementLogEntity.toDomain(epochs: List<MovementEpochEntity>): MovementTrace = MovementTrace(
    logId = id,
    mode = mode,
    start = Instant.ofEpochMilli(startEpochMs),
    end = endEpochMs?.let(Instant::ofEpochMilli),
    epochs = epochs.filter { it.logId == id }.sortedBy { it.startEpochMs }.map { it.toDomain() },
)

// ---------------------------------------------------------------- Alarm events

/**
 * null for a ring kind, reason or outcome this version doesn't know (the row can't be read honestly). An unknown
 * mode falls back to OFF and an unknown wake-up style to STANDARD.
 */
fun AlarmEventEntity.toDomainOrNull(): AlarmEvent? {
    val ringKind = enumOrNull<RingKind>(kind) ?: return null
    val ringReason = enumOrNull<RingReason>(reason) ?: return null
    val ringOutcome = if (outcome == null) null else enumOrNull<RingOutcome>(outcome) ?: return null
    return AlarmEvent(
        id = id,
        kind = ringKind,
        occurrenceId = occurrenceId,
        wakeDate = LocalDate.parse(wakeDate),
        target = Instant.ofEpochMilli(targetEpochMs),
        windowStart = Instant.ofEpochMilli(windowStartEpochMs),
        mode = enumOrNull<AlarmMode>(mode) ?: AlarmMode.OFF,
        style = enumOrNull<WakeStyle>(wakeStyle) ?: WakeStyle.STANDARD,
        firstRingAt = Instant.ofEpochMilli(firstRingEpochMs),
        reason = ringReason,
        maxZ = maxZ,
        snoozeTimes = decodeEpochList(snoozeTimes),
        stoppedAt = stoppedAtEpochMs?.let(Instant::ofEpochMilli),
        outcome = ringOutcome,
        sensingStart = sensingStartEpochMs?.let(Instant::ofEpochMilli),
        offset = ZoneOffset.ofTotalSeconds(offsetSec),
    )
}

/** id 0 inserts a new row; any other id updates that row. */
fun AlarmEvent.toEntity(): AlarmEventEntity = AlarmEventEntity(
    id = id,
    kind = kind.name,
    occurrenceId = occurrenceId,
    wakeDate = wakeDate.toString(),
    targetEpochMs = target.toEpochMilli(),
    windowStartEpochMs = windowStart.toEpochMilli(),
    mode = mode.name,
    wakeStyle = style.name,
    firstRingEpochMs = firstRingAt.toEpochMilli(),
    reason = reason.name,
    maxZ = maxZ,
    snoozeTimes = snoozeTimes.joinToString(",") { it.toEpochMilli().toString() },
    stoppedAtEpochMs = stoppedAt?.toEpochMilli(),
    outcome = outcome?.name,
    sensingStartEpochMs = sensingStart?.toEpochMilli(),
    offsetSec = offset.totalSeconds,
)

/** "1,2,3" → instants; blank → none; a malformed part is skipped. */
private fun decodeEpochList(csv: String): List<Instant> =
    csv.split(',').mapNotNull { part -> part.trim().toLongOrNull()?.let(Instant::ofEpochMilli) }

// ---------------------------------------------------------------- Tuning

/** null for a tuning reason this version doesn't know. */
fun AlarmTuningEntity.toDomainOrNull(): AlarmTuning? {
    val tuningReason = enumOrNull<TuningReason>(reason) ?: return null
    return AlarmTuning(
        zThreshold = zThreshold,
        reason = tuningReason,
        changedAt = Instant.ofEpochMilli(changedAtEpochMs),
        offset = ZoneOffset.ofTotalSeconds(offsetSec),
    )
}

fun AlarmTuning.toEntity(): AlarmTuningEntity = AlarmTuningEntity(
    zThreshold = zThreshold,
    reason = reason.name,
    changedAtEpochMs = changedAt.toEpochMilli(),
    offsetSec = offset.totalSeconds,
)

// ---------------------------------------------------------------- Hush

/** null for a colour this version doesn't know. */
fun HushPlayEntity.colorOrNull(): HushColor? = enumOrNull<HushColor>(color)

/** A new Hush play row (id 0; end set later). */
fun HushColor.toPlayEntity(start: Instant, offset: ZoneOffset, wakeDate: LocalDate): HushPlayEntity = HushPlayEntity(
    startEpochMs = start.toEpochMilli(),
    endEpochMs = null,
    offsetSec = offset.totalSeconds,
    color = name,
    wakeDate = wakeDate.toString(),
)
