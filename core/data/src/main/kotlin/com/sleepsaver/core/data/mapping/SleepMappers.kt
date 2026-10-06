package com.sleepsaver.core.data.mapping

import com.sleepsaver.core.data.db.AddedAwakeEntity
import com.sleepsaver.core.data.db.ManualNapEntity
import com.sleepsaver.core.data.db.NightOverrideEntity
import com.sleepsaver.core.data.db.SessionWithStages
import com.sleepsaver.core.data.db.SleepSessionEntity
import com.sleepsaver.core.data.db.SleepStageEntity
import com.sleepsaver.core.data.db.SourceKind
import com.sleepsaver.core.data.db.VitalEntity
import com.sleepsaver.core.domain.model.AddedAwake
import com.sleepsaver.core.domain.model.ManualNap
import com.sleepsaver.core.domain.model.NightOverride
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SleepStage
import com.sleepsaver.core.domain.model.SleepStageType
import com.sleepsaver.core.domain.model.VitalSample
import com.sleepsaver.core.domain.model.VitalType
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

// ---------------------------------------------------------------- Sessions

/**
 * Missing offsets are resolved with the zone the row was ingested in, at that instant
 * (so a null-offset session that spans a DST change still gets two different offsets).
 */
fun SessionWithStages.toDomain(): RawSleepSession {
    val start = Instant.ofEpochMilli(session.startEpochMs)
    val end = Instant.ofEpochMilli(session.endEpochMs)
    val ingestZone = zoneOrUtc(session.ingestZoneId)
    return RawSleepSession(
        id = session.id,
        origin = session.originPackage,
        start = start,
        end = end,
        startOffset = session.startOffsetSec?.let(ZoneOffset::ofTotalSeconds) ?: ingestZone.rules.getOffset(start),
        endOffset = session.endOffsetSec?.let(ZoneOffset::ofTotalSeconds) ?: ingestZone.rules.getOffset(end),
        stages = stages.sortedBy { it.startEpochMs }.map { it.toDomain() },
        lastModified = Instant.ofEpochMilli(session.lastModifiedEpochMs),
        title = session.title,
    )
}

fun SleepStageEntity.toDomain(): SleepStage = SleepStage(
    start = Instant.ofEpochMilli(startEpochMs),
    end = Instant.ofEpochMilli(endEpochMs),
    type = SleepStageType.fromHealthConnect(stage),
)

/** Stores the session's offsets as recorded; [ingestZone] is kept for reference. */
fun RawSleepSession.toEntity(sourceKind: SourceKind, ingestZone: ZoneId): SessionWithStages = SessionWithStages(
    session = SleepSessionEntity(
        id = id,
        originPackage = origin,
        startEpochMs = start.toEpochMilli(),
        endEpochMs = end.toEpochMilli(),
        startOffsetSec = startOffset.totalSeconds,
        endOffsetSec = endOffset.totalSeconds,
        ingestZoneId = ingestZone.id,
        title = title,
        lastModifiedEpochMs = lastModified.toEpochMilli(),
        sourceKind = sourceKind.name,
    ),
    stages = stages.map {
        SleepStageEntity(
            sessionId = id,
            startEpochMs = it.start.toEpochMilli(),
            endEpochMs = it.end.toEpochMilli(),
            stage = it.type.healthConnectValue,
        )
    },
)

private fun zoneOrUtc(id: String): ZoneId = try {
    ZoneId.of(id)
} catch (_: DateTimeException) {
    ZoneOffset.UTC
}

// ---------------------------------------------------------------- User edits

fun NightOverrideEntity.toDomain(): NightOverride = NightOverride(
    wakeDate = LocalDate.parse(wakeDate),
    fellAsleep = fellAsleepEpochMs?.let(Instant::ofEpochMilli),
    wokeUp = wokeUpEpochMs?.let(Instant::ofEpochMilli),
    awakeMinutes = awakeMinutes,
    napMinutes = napMinutes,
    isManualNight = isManualNight,
    offset = ZoneOffset.ofTotalSeconds(offsetSec),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMs),
)

fun NightOverride.toEntity(): NightOverrideEntity = NightOverrideEntity(
    wakeDate = wakeDate.toString(),
    fellAsleepEpochMs = fellAsleep?.toEpochMilli(),
    wokeUpEpochMs = wokeUp?.toEpochMilli(),
    awakeMinutes = awakeMinutes,
    napMinutes = napMinutes,
    isManualNight = isManualNight,
    offsetSec = offset.totalSeconds,
    updatedAtEpochMs = updatedAt.toEpochMilli(),
)

fun ManualNapEntity.toDomain(): ManualNap = ManualNap(
    id = id,
    start = Instant.ofEpochMilli(startEpochMs),
    end = Instant.ofEpochMilli(endEpochMs),
    offset = ZoneOffset.ofTotalSeconds(offsetSec),
)

fun ManualNap.toEntity(createdAt: Instant): ManualNapEntity = ManualNapEntity(
    id = id,
    startEpochMs = start.toEpochMilli(),
    endEpochMs = end.toEpochMilli(),
    offsetSec = offset.totalSeconds,
    createdAtEpochMs = createdAt.toEpochMilli(),
)

fun AddedAwakeEntity.toDomain(): AddedAwake = AddedAwake(
    id = id,
    wakeDate = LocalDate.parse(wakeDate),
    start = Instant.ofEpochMilli(startEpochMs),
    end = Instant.ofEpochMilli(endEpochMs),
)

fun AddedAwake.toEntity(createdAt: Instant): AddedAwakeEntity = AddedAwakeEntity(
    id = id,
    wakeDate = wakeDate.toString(),
    startEpochMs = start.toEpochMilli(),
    endEpochMs = end.toEpochMilli(),
    createdAtEpochMs = createdAt.toEpochMilli(),
)

// ---------------------------------------------------------------- Vitals

/** null for a type this version doesn't know (rows written by a newer version). */
fun VitalEntity.toDomainOrNull(): VitalSample? {
    val vitalType = VitalType.entries.firstOrNull { it.name == type } ?: return null
    return VitalSample(
        id = id,
        type = vitalType,
        time = Instant.ofEpochMilli(timeEpochMs),
        value = value,
        origin = originPackage,
    )
}

fun VitalSample.toEntity(sourceKind: SourceKind, offset: ZoneOffset?): VitalEntity = VitalEntity(
    id = id,
    type = type.name,
    timeEpochMs = time.toEpochMilli(),
    offsetSec = offset?.totalSeconds,
    value = value,
    originPackage = origin,
    sourceKind = sourceKind.name,
)
