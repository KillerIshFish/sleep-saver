package com.sleepsaver.core.data.export

import com.sleepsaver.core.data.db.AddedAwakeEntity
import com.sleepsaver.core.data.db.HabitEntity
import com.sleepsaver.core.data.db.ManualNapEntity
import com.sleepsaver.core.data.db.MorningReviewEntity
import com.sleepsaver.core.data.db.NightOverrideEntity
import com.sleepsaver.core.data.db.RoutineActivityEntity
import com.sleepsaver.core.data.db.SessionWithStages
import com.sleepsaver.core.data.db.SleepStageEntity
import com.sleepsaver.core.data.db.VitalEntity
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.encode
import com.sleepsaver.core.domain.model.SleepStageType
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset

/**
 * The full JSON export. Instants are ISO-8601 UTC strings, offsets "+02:00", dates ISO local dates and
 * durations whole minutes. Raw rows are exported exactly as stored (null offsets stay null).
 */
@Serializable
internal data class ExportDocument(
    val schemaVersion: Int,
    val exportedAt: String,
    val settings: SettingsDto,
    val rawSessions: List<SessionDto>,
    val overrides: List<OverrideDto>,
    val manualNaps: List<ManualNapDto>,
    val addedAwake: List<AddedAwakeDto>,
    val vitals: List<VitalDto>,
    val habits: List<HabitDto>,
    val routineActivities: List<RoutineActivityDto>,
    val reviews: List<ReviewDto>,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
internal data class SettingsDto(
    val sleepNeedMin: Long,
    val typicalWake: String,
    /** WakeTimeMode name. Absent in files exported before it existed. */
    val wakeTimeMode: String? = null,
    val bedtime: String? = null,
    /** BedtimeMode name. */
    val bedtimeMode: String? = null,
    val streakThresholdMin: Long,
    val lowDebtMaxMin: Long,
    val highDebtMinMin: Long,
    /** "*all*" or a package name. */
    val source: String,
    val demoMode: Boolean,
    val demoStartedAt: String?,
    val clockFormat: String,
    val morningReviewEnabled: Boolean,
    val onboardingDone: Boolean,
    val needSuggestionDismissedValueMin: Long?,
    val needSuggestionDismissedAt: String?,
)

@Serializable
internal data class SessionDto(
    val id: String,
    val origin: String,
    val start: String,
    val end: String,
    val startOffset: String?,
    val endOffset: String?,
    val ingestZoneId: String,
    val title: String?,
    val lastModified: String,
    val sourceKind: String,
    val stages: List<StageDto>,
)

@Serializable
internal data class StageDto(
    val start: String,
    val end: String,
    /** Health Connect stage constant. */
    val stage: Int,
    val type: String,
)

@Serializable
internal data class OverrideDto(
    val wakeDate: String,
    val fellAsleep: String?,
    val wokeUp: String?,
    val awakeMinutes: Int?,
    val napMinutes: Int?,
    val isManualNight: Boolean,
    val offset: String,
    val updatedAt: String,
)

@Serializable
internal data class ManualNapDto(val id: Long, val start: String, val end: String, val offset: String, val createdAt: String)

@Serializable
internal data class AddedAwakeDto(val id: Long, val wakeDate: String, val start: String, val end: String, val createdAt: String)

@Serializable
internal data class VitalDto(
    val id: String,
    val type: String,
    val time: String,
    val offset: String?,
    val value: Double,
    val origin: String,
    val sourceKind: String,
)

@Serializable
internal data class HabitDto(
    val type: String,
    val enabled: Boolean,
    val reminderEnabled: Boolean,
    val offsetMinutes: Int,
    val sortOrder: Int,
)

@Serializable
internal data class RoutineActivityDto(
    val id: Long,
    val label: String,
    val emoji: String,
    val isCustom: Boolean,
    val selected: Boolean,
    val sortOrder: Int,
)

@Serializable
internal data class ReviewDto(
    val wakeDate: String,
    val status: String,
    val at: String,
    val debtBeforeMin: Int?,
    val debtAfterMin: Int?,
)

internal fun AppSettings.toDto() = SettingsDto(
    sleepNeedMin = sleep.sleepNeed.toMinutes(),
    typicalWake = sleep.typicalWake.toString(),
    wakeTimeMode = sleep.wakeTimeMode.name,
    bedtime = sleep.bedtime.toString(),
    bedtimeMode = sleep.bedtimeMode.name,
    streakThresholdMin = sleep.streakThreshold.toMinutes(),
    lowDebtMaxMin = sleep.lowDebtMax.toMinutes(),
    highDebtMinMin = sleep.highDebtMin.toMinutes(),
    source = sleep.source.encode(),
    demoMode = demoMode,
    demoStartedAt = demoStartedAt?.toString(),
    clockFormat = clockFormat.name,
    morningReviewEnabled = morningReviewEnabled,
    onboardingDone = onboardingDone,
    needSuggestionDismissedValueMin = needSuggestionDismissedValue?.toMinutes(),
    needSuggestionDismissedAt = needSuggestionDismissedAt?.toString(),
)

internal fun SessionWithStages.toDto() = SessionDto(
    id = session.id,
    origin = session.originPackage,
    start = iso(session.startEpochMs),
    end = iso(session.endEpochMs),
    startOffset = session.startOffsetSec?.let(::offset),
    endOffset = session.endOffsetSec?.let(::offset),
    ingestZoneId = session.ingestZoneId,
    title = session.title,
    lastModified = iso(session.lastModifiedEpochMs),
    sourceKind = session.sourceKind,
    stages = stages.sortedBy { it.startEpochMs }.map { it.toDto() },
)

internal fun SleepStageEntity.toDto() = StageDto(
    start = iso(startEpochMs),
    end = iso(endEpochMs),
    stage = stage,
    type = SleepStageType.fromHealthConnect(stage).name,
)

internal fun NightOverrideEntity.toDto() = OverrideDto(
    wakeDate = wakeDate,
    fellAsleep = fellAsleepEpochMs?.let(::iso),
    wokeUp = wokeUpEpochMs?.let(::iso),
    awakeMinutes = awakeMinutes,
    napMinutes = napMinutes,
    isManualNight = isManualNight,
    offset = offset(offsetSec),
    updatedAt = iso(updatedAtEpochMs),
)

internal fun ManualNapEntity.toDto() =
    ManualNapDto(id = id, start = iso(startEpochMs), end = iso(endEpochMs), offset = offset(offsetSec), createdAt = iso(createdAtEpochMs))

internal fun AddedAwakeEntity.toDto() =
    AddedAwakeDto(id = id, wakeDate = wakeDate, start = iso(startEpochMs), end = iso(endEpochMs), createdAt = iso(createdAtEpochMs))

internal fun VitalEntity.toDto() = VitalDto(
    id = id,
    type = type,
    time = iso(timeEpochMs),
    offset = offsetSec?.let(::offset),
    value = value,
    origin = originPackage,
    sourceKind = sourceKind,
)

internal fun HabitEntity.toDto() = HabitDto(type, enabled, reminderEnabled, offsetMinutes, sortOrder)

internal fun RoutineActivityEntity.toDto() = RoutineActivityDto(id, label, emoji, isCustom, selected, sortOrder)

internal fun MorningReviewEntity.toDto() =
    ReviewDto(wakeDate = wakeDate, status = status, at = iso(atEpochMs), debtBeforeMin = debtBeforeMin, debtAfterMin = debtAfterMin)

private fun iso(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

private fun offset(seconds: Int): String = ZoneOffset.ofTotalSeconds(seconds).id
