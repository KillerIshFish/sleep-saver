package com.sleepsaver.core.data.export

import com.sleepsaver.core.data.db.AddedAwakeEntity
import com.sleepsaver.core.data.db.AlarmEventEntity
import com.sleepsaver.core.data.db.AlarmTuningEntity
import com.sleepsaver.core.data.db.AnchorEntity
import com.sleepsaver.core.data.db.DayPlanEntity
import com.sleepsaver.core.data.db.DayRoutineItemEntity
import com.sleepsaver.core.data.db.HabitEntity
import com.sleepsaver.core.data.db.HushPlayEntity
import com.sleepsaver.core.data.db.ManualNapEntity
import com.sleepsaver.core.data.db.MorningFeelEntity
import com.sleepsaver.core.data.db.MorningReviewEntity
import com.sleepsaver.core.data.db.MovementLogEntity
import com.sleepsaver.core.data.db.NightMarkEntity
import com.sleepsaver.core.data.db.NightOverrideEntity
import com.sleepsaver.core.data.db.NightStepLogEntity
import com.sleepsaver.core.data.db.PlanOverrideEntity
import com.sleepsaver.core.data.db.PlanPauseEntity
import com.sleepsaver.core.data.db.RoutineActivityEntity
import com.sleepsaver.core.data.db.SessionWithStages
import com.sleepsaver.core.data.db.SleepStageEntity
import com.sleepsaver.core.data.db.TomorrowListEntity
import com.sleepsaver.core.data.db.VitalEntity
import com.sleepsaver.core.data.mapping.minuteOfDayToTime
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.encode
import com.sleepsaver.core.domain.model.SleepStageType
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset

/**
 * The full JSON export. Instants are ISO-8601 UTC strings, offsets "+02:00", dates ISO local dates, times of day
 * "06:45", days of week ISO numbers (1 = Monday) and durations whole minutes. Raw rows are exported exactly as stored
 * (null offsets stay null).
 *
 * Schema 2 added the week plan, one-offs, Night steps, the journal (marks, Morning feel, Tomorrow lists) and the night
 * log (anchors, movement logs without their epochs, alarm history, tuning, Hush plays); each list is empty in files
 * exported before it existed.
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
    val dayPlans: List<DayPlanDto> = emptyList(),
    val dayRoutineItems: List<DayRoutineItemDto> = emptyList(),
    val planOverrides: List<PlanOverrideDto> = emptyList(),
    val planPauses: List<PlanPauseDto> = emptyList(),
    val nightStepLogs: List<NightStepLogDto> = emptyList(),
    val tomorrowLists: List<TomorrowListDto> = emptyList(),
    val nightMarks: List<NightMarkDto> = emptyList(),
    val morningFeel: List<MorningFeelDto> = emptyList(),
    val anchors: List<AnchorDto> = emptyList(),
    val movementLogs: List<MovementLogDto> = emptyList(),
    val alarmEvents: List<AlarmEventDto> = emptyList(),
    val alarmTuning: List<AlarmTuningDto> = emptyList(),
    val hushPlays: List<HushPlayDto> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 2
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
    /** Technique name; null = custom. Absent in files exported before it existed. */
    val techniqueKey: String? = null,
    /** Night steps lead before the bedtime. Absent in files exported before it existed. */
    val leadMin: Int? = null,
    /** Absent in files exported before it existed. */
    val durationMin: Int? = null,
)

@Serializable
internal data class ReviewDto(
    val wakeDate: String,
    val status: String,
    val at: String,
    val debtBeforeMin: Int?,
    val debtAfterMin: Int?,
)

// ---------------------------------------------------------------- Schema 2: week plan

@Serializable
internal data class DayPlanDto(
    /** ISO day of week, 1 = Monday. */
    val dayOfWeek: Int,
    /** null = the usual wake time. */
    val wakeTime: String?,
    val alarmMode: String,
    val windowMin: Int,
    val wakeStyle: String,
    val tone: String,
    val volumePct: Int,
    val snoozeStyle: String,
    val snoozeFixedMin: Int,
    val snoozeFixedCount: Int,
    val overrunMin: Int,
    /** null = the Settings bedtime rule. */
    val bedtime: String?,
    val nightStepsOn: Boolean,
    val windDownMin: Int,
    /** true = the evening uses its own list (dayRoutineItems). */
    val ownRoutine: Boolean,
)

@Serializable
internal data class DayRoutineItemDto(val dayOfWeek: Int, val activityId: Long, val position: Int)

@Serializable
internal data class PlanOverrideDto(
    val date: String,
    val kind: String,
    val wakeTime: String?,
    val alarmMode: String?,
    val createdAt: String,
    val offset: String,
)

@Serializable
internal data class PlanPauseDto(val from: String, val until: String, val createdAt: String, val offset: String)

// ---------------------------------------------------------------- Schema 2: journal

@Serializable
internal data class NightStepLogDto(val nightOf: String, val activityId: Long, val technique: String?, val doneAt: String, val offset: String)

@Serializable
internal data class TomorrowListDto(val nightOf: String, val text: String, val updatedAt: String, val offset: String)

@Serializable
internal data class NightMarkDto(val wakeDate: String, val mark: String, val createdAt: String, val offset: String)

@Serializable
internal data class MorningFeelDto(
    val wakeDate: String,
    /** 1 = Foggy … 5 = Bright. */
    val level: Int,
    val at: String,
    val offset: String,
    val source: String,
)

// ---------------------------------------------------------------- Schema 2: night log

@Serializable
internal data class AnchorDto(
    val id: Long,
    val at: String,
    val offset: String,
    val wakeDate: String,
    val source: String,
    val wholeNightLog: Boolean,
)

/** One movement stream; its epochs are not exported. */
@Serializable
internal data class MovementLogDto(
    val id: Long,
    val start: String,
    val end: String?,
    val offset: String,
    val mode: String,
    val wakeDate: String,
    val anchorId: Long?,
    val occurrenceId: String?,
)

@Serializable
internal data class AlarmEventDto(
    val id: Long,
    val kind: String,
    val occurrenceId: String?,
    val wakeDate: String,
    val target: String,
    val windowStart: String,
    val mode: String,
    val wakeStyle: String,
    val firstRing: String,
    val reason: String,
    val maxZ: Double?,
    val snoozeTimes: List<String>,
    val stoppedAt: String?,
    val outcome: String?,
    val sensingStart: String?,
    val offset: String,
)

@Serializable
internal data class AlarmTuningDto(val zThreshold: Double, val reason: String, val changedAt: String, val offset: String)

@Serializable
internal data class HushPlayDto(val id: Long, val start: String, val end: String?, val offset: String, val color: String, val wakeDate: String)

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

internal fun RoutineActivityEntity.toDto() =
    RoutineActivityDto(id, label, emoji, isCustom, selected, sortOrder, techniqueKey, leadMin, durationMin)

internal fun MorningReviewEntity.toDto() =
    ReviewDto(wakeDate = wakeDate, status = status, at = iso(atEpochMs), debtBeforeMin = debtBeforeMin, debtAfterMin = debtAfterMin)

internal fun DayPlanEntity.toDto() = DayPlanDto(
    dayOfWeek = dayOfWeek,
    wakeTime = wakeMinOfDay?.let(::timeOfDay),
    alarmMode = alarmMode,
    windowMin = windowMin,
    wakeStyle = wakeStyle,
    tone = tone,
    volumePct = volumePct,
    snoozeStyle = snoozeStyle,
    snoozeFixedMin = snoozeFixedMin,
    snoozeFixedCount = snoozeFixedCount,
    overrunMin = overrunMin,
    bedtime = bedtimeMinOfDay?.let(::timeOfDay),
    nightStepsOn = nightStepsOn,
    windDownMin = windDownMin,
    ownRoutine = ownRoutine,
)

internal fun DayRoutineItemEntity.toDto() = DayRoutineItemDto(dayOfWeek, activityId, position)

internal fun PlanOverrideEntity.toDto() = PlanOverrideDto(
    date = date,
    kind = kind,
    wakeTime = wakeMinOfDay?.let(::timeOfDay),
    alarmMode = alarmMode,
    createdAt = iso(createdAtEpochMs),
    offset = offset(offsetSec),
)

internal fun PlanPauseEntity.toDto() =
    PlanPauseDto(from = fromDate, until = untilDate, createdAt = iso(createdAtEpochMs), offset = offset(offsetSec))

internal fun NightStepLogEntity.toDto() =
    NightStepLogDto(nightOf = nightOf, activityId = activityId, technique = techniqueKey, doneAt = iso(doneAtEpochMs), offset = offset(offsetSec))

internal fun TomorrowListEntity.toDto() =
    TomorrowListDto(nightOf = nightOf, text = text, updatedAt = iso(updatedAtEpochMs), offset = offset(offsetSec))

internal fun NightMarkEntity.toDto() =
    NightMarkDto(wakeDate = wakeDate, mark = mark, createdAt = iso(createdAtEpochMs), offset = offset(offsetSec))

internal fun MorningFeelEntity.toDto() =
    MorningFeelDto(wakeDate = wakeDate, level = level, at = iso(atEpochMs), offset = offset(offsetSec), source = source)

internal fun AnchorEntity.toDto() = AnchorDto(
    id = id,
    at = iso(atEpochMs),
    offset = offset(offsetSec),
    wakeDate = wakeDate,
    source = source,
    wholeNightLog = wholeNightLog,
)

internal fun MovementLogEntity.toDto() = MovementLogDto(
    id = id,
    start = iso(startEpochMs),
    end = endEpochMs?.let(::iso),
    offset = offset(offsetSec),
    mode = mode,
    wakeDate = wakeDate,
    anchorId = anchorId,
    occurrenceId = occurrenceId,
)

internal fun AlarmEventEntity.toDto() = AlarmEventDto(
    id = id,
    kind = kind,
    occurrenceId = occurrenceId,
    wakeDate = wakeDate,
    target = iso(targetEpochMs),
    windowStart = iso(windowStartEpochMs),
    mode = mode,
    wakeStyle = wakeStyle,
    firstRing = iso(firstRingEpochMs),
    reason = reason,
    maxZ = maxZ,
    snoozeTimes = snoozeTimes.split(',').mapNotNull { it.trim().toLongOrNull()?.let(::iso) },
    stoppedAt = stoppedAtEpochMs?.let(::iso),
    outcome = outcome,
    sensingStart = sensingStartEpochMs?.let(::iso),
    offset = offset(offsetSec),
)

internal fun AlarmTuningEntity.toDto() =
    AlarmTuningDto(zThreshold = zThreshold, reason = reason, changedAt = iso(changedAtEpochMs), offset = offset(offsetSec))

internal fun HushPlayEntity.toDto() = HushPlayDto(
    id = id,
    start = iso(startEpochMs),
    end = endEpochMs?.let(::iso),
    offset = offset(offsetSec),
    color = color,
    wakeDate = wakeDate,
)

private fun iso(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

/** A stored minute of day as "06:45". */
private fun timeOfDay(minuteOfDay: Int): String = minuteOfDayToTime(minuteOfDay).toString()

private fun offset(seconds: Int): String = ZoneOffset.ofTotalSeconds(seconds).id
