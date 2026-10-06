package com.sleepsaver.core.data.source

import androidx.health.connect.client.changes.Change
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import com.sleepsaver.core.data.healthconnect.HealthConnectClientProvider
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SleepStage
import com.sleepsaver.core.domain.model.SleepStageType
import com.sleepsaver.core.domain.model.VitalSample
import com.sleepsaver.core.domain.model.VitalType
import java.time.LocalDate
import java.time.ZoneId

// Pure Health Connect record → domain mapping. Kept free of client calls so it can be unit tested.

/** Stable ids for vitals that Sleep Saver derives itself (aggregates have no Health Connect record id). */
object VitalIds {
    fun steps(date: LocalDate): String = "steps:$date"
    fun sleepingHeartRate(sessionId: String): String = "sleephr:$sessionId"
}

/**
 * Missing offsets are filled with [deviceZone]'s offset at that instant, so local times stay reproducible.
 * Stages are sorted by start; unknown stage constants map to [SleepStageType.UNKNOWN].
 */
internal fun SleepSessionRecord.toRawSleepSession(deviceZone: ZoneId): RawSleepSession = RawSleepSession(
    id = metadata.id,
    origin = metadata.dataOrigin.packageName,
    start = startTime,
    end = endTime,
    startOffset = startZoneOffset ?: deviceZone.rules.getOffset(startTime),
    endOffset = endZoneOffset ?: deviceZone.rules.getOffset(endTime),
    stages = stages
        .map { SleepStage(it.startTime, it.endTime, SleepStageType.fromHealthConnect(it.stage)) }
        .sortedBy { it.start },
    lastModified = metadata.lastModifiedTime,
    title = title,
)

internal fun RestingHeartRateRecord.toVitalSample(): VitalSample = VitalSample(
    id = metadata.id,
    type = VitalType.RESTING_HR,
    time = time,
    value = beatsPerMinute.toDouble(),
    origin = metadata.dataOrigin.packageName,
)

internal fun HeartRateVariabilityRmssdRecord.toVitalSample(): VitalSample = VitalSample(
    id = metadata.id,
    type = VitalType.HRV_RMSSD,
    time = time,
    value = heartRateVariabilityMillis,
    origin = metadata.dataOrigin.packageName,
)

/** Steps for one local day, stamped at the start of that day in [zone]. */
internal fun dailyStepsSample(date: LocalDate, zone: ZoneId, steps: Long, origins: Collection<String>): VitalSample =
    VitalSample(
        id = VitalIds.steps(date),
        type = VitalType.STEPS_DAY,
        time = date.atStartOfDay(zone).toInstant(),
        value = steps.toDouble(),
        origin = aggregateOrigin(origins),
    )

/** Average heart rate while [session] lasted, stamped at its end (the morning it belongs to). */
internal fun sleepingHeartRateSample(session: RawSleepSession, bpm: Long): VitalSample = VitalSample(
    id = VitalIds.sleepingHeartRate(session.id),
    type = VitalType.SLEEP_HR_AVG,
    time = session.end,
    value = bpm.toDouble(),
    origin = session.origin,
)

/** Aggregates can mix apps: credit Garmin Connect when it contributed, else the first origin, else Health Connect. */
internal fun aggregateOrigin(origins: Collection<String>): String {
    val known = origins.filter { it.isNotEmpty() }
    return when {
        SleepConfig.GARMIN_CONNECT_PACKAGE in known -> SleepConfig.GARMIN_CONNECT_PACKAGE
        known.isNotEmpty() -> known.min()
        else -> HealthConnectClientProvider.PROVIDER_PACKAGE
    }
}

/**
 * Folds Changes API pages into one [ChangeBatch.Changes]. Later changes win: an upsert followed by a deletion
 * of the same id ends as a deletion. A [DeletionChange] doesn't say which type it was, so its id is reported
 * as deleted for both sessions and vitals (deleting an unknown id is a no-op).
 */
internal class ChangeCollector(private val deviceZone: ZoneId) {
    private val sessions = LinkedHashMap<String, RawSleepSession>()
    private val vitals = LinkedHashMap<String, VitalSample>()
    private val deleted = LinkedHashSet<String>()

    fun add(changes: List<Change>) {
        for (change in changes) {
            when (change) {
                is UpsertionChange -> upsert(change)
                is DeletionChange -> {
                    sessions.remove(change.recordId)
                    vitals.remove(change.recordId)
                    deleted += change.recordId
                }
            }
        }
    }

    private fun upsert(change: UpsertionChange) {
        when (val record = change.record) {
            is SleepSessionRecord -> record.toRawSleepSession(deviceZone).let { sessions[it.id] = it; deleted -= it.id }
            is RestingHeartRateRecord -> record.toVitalSample().let { vitals[it.id] = it; deleted -= it.id }
            is HeartRateVariabilityRmssdRecord -> record.toVitalSample().let { vitals[it.id] = it; deleted -= it.id }
            else -> Unit
        }
    }

    fun build(nextToken: String): ChangeBatch.Changes = ChangeBatch.Changes(
        upserted = sessions.values.toList(),
        deletedIds = deleted.toList(),
        vitals = vitals.values.toList(),
        deletedVitalIds = deleted.toList(),
        nextToken = nextToken,
    )
}
