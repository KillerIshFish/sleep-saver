package com.sleepsaver.core.data.sync

import com.sleepsaver.core.data.db.SessionWithStages
import com.sleepsaver.core.data.db.SleepSessionEntity
import com.sleepsaver.core.data.db.SleepStageEntity
import com.sleepsaver.core.data.db.SourceKind
import com.sleepsaver.core.data.db.VitalEntity
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.VitalSample
import java.time.ZoneId

// Domain → Room rows for the write path (sync and demo data). Offsets are always resolved by the source,
// so they are stored as recorded-or-filled; ingestZoneId records the device zone used for any filling.

internal fun RawSleepSession.toSessionWithStages(kind: SourceKind, ingestZone: ZoneId): SessionWithStages =
    SessionWithStages(
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
            sourceKind = kind.name,
        ),
        stages = stages
            .distinctBy { it.start.toEpochMilli() }
            .map { SleepStageEntity(id, it.start.toEpochMilli(), it.end.toEpochMilli(), it.type.healthConnectValue) },
    )

internal fun VitalSample.toVitalEntity(kind: SourceKind, zone: ZoneId): VitalEntity = VitalEntity(
    id = id,
    type = type.name,
    timeEpochMs = time.toEpochMilli(),
    offsetSec = zone.rules.getOffset(time).totalSeconds,
    value = value,
    originPackage = origin,
    sourceKind = kind.name,
)
