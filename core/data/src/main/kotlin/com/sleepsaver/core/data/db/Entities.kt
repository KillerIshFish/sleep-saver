package com.sleepsaver.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** Where a raw row came from. Demo rows are synthetic and deleted when demo mode is turned off. */
enum class SourceKind { HEALTH_CONNECT, DEMO }

/** Raw sleep session exactly as the data source reported it. Never edited by the user. */
@Entity(
    tableName = "sleep_sessions",
    indices = [Index("endEpochMs"), Index("originPackage"), Index("sourceKind")],
)
data class SleepSessionEntity(
    /** Health Connect metadata.id, or "demo:<n>". */
    @PrimaryKey val id: String,
    val originPackage: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    /** As recorded; null when the source didn't say (then [ingestZoneId] is used). */
    val startOffsetSec: Int?,
    val endOffsetSec: Int?,
    /** Device zone when the row was ingested. */
    val ingestZoneId: String,
    val title: String?,
    val lastModifiedEpochMs: Long,
    val sourceKind: String,
)

@Entity(
    tableName = "sleep_stages",
    primaryKeys = ["sessionId", "startEpochMs"],
    foreignKeys = [
        ForeignKey(
            entity = SleepSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class SleepStageEntity(
    val sessionId: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    /** Health Connect stage constant (SleepStageType.healthConnectValue). */
    val stage: Int,
)

data class SessionWithStages(
    @Embedded val session: SleepSessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val stages: List<SleepStageEntity>,
)

/** User edits for one night. Takes precedence over raw data; deleting the row reverts the night. */
@Entity(tableName = "night_overrides")
data class NightOverrideEntity(
    /** ISO-8601 local date, e.g. "2026-10-06". */
    @PrimaryKey val wakeDate: String,
    val fellAsleepEpochMs: Long?,
    val wokeUpEpochMs: Long?,
    val awakeMinutes: Int?,
    val napMinutes: Int?,
    val isManualNight: Boolean,
    val offsetSec: Int,
    val updatedAtEpochMs: Long,
)

/** Awake time added by long-pressing the sleep block. */
@Entity(tableName = "added_awake", indices = [Index("wakeDate")])
data class AddedAwakeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wakeDate: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "manual_naps", indices = [Index("startEpochMs")])
data class ManualNapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val offsetSec: Int,
    val createdAtEpochMs: Long,
)

/** Optional vitals (resting HR, HRV, average sleeping HR per session, daily steps). */
@Entity(tableName = "vitals", indices = [Index(value = ["type", "timeEpochMs"])])
data class VitalEntity(
    @PrimaryKey val id: String,
    /** VitalType.name */
    val type: String,
    val timeEpochMs: Long,
    val offsetSec: Int?,
    val value: Double,
    val originPackage: String,
    val sourceKind: String,
)

@Entity(tableName = "morning_reviews")
data class MorningReviewEntity(
    @PrimaryKey val wakeDate: String,
    /** ReviewStatus.name */
    val status: String,
    val atEpochMs: Long,
    val debtBeforeMin: Int?,
    val debtAfterMin: Int?,
)

@Entity(tableName = "habits")
data class HabitEntity(
    /** HabitType.name */
    @PrimaryKey val type: String,
    val enabled: Boolean,
    val reminderEnabled: Boolean,
    val offsetMinutes: Int,
    val sortOrder: Int,
)

/** An evening-routine activity (a Night step). [selected] = on the every-evening list. */
@Entity(tableName = "routine_activities")
data class RoutineActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val emoji: String,
    val isCustom: Boolean,
    val selected: Boolean,
    val sortOrder: Int,
    /** Technique.name; null = custom or unmapped. */
    @ColumnInfo(defaultValue = "NULL") val techniqueKey: String? = null,
    /** Minutes before the target bedtime the step starts. */
    @ColumnInfo(defaultValue = "45") val leadMin: Int = 45,
    /** Minutes the step lasts (0 = a checklist item). */
    @ColumnInfo(defaultValue = "10") val durationMin: Int = 10,
)

// ---------------------------------------------------------------- Week plan (v2)

/** One weekday's plan: morning (wake + alarm) and evening (bedtime + Night steps). */
@Entity(tableName = "day_plans")
data class DayPlanEntity(
    /** ISO day of week, 1 = Monday … 7 = Sunday. */
    @PrimaryKey val dayOfWeek: Int,
    /** Minute of day; null = usual (learned or Settings). */
    val wakeMinOfDay: Int?,
    /** AlarmMode.name */
    val alarmMode: String,
    val windowMin: Int,
    /** WakeStyle.name */
    val wakeStyle: String,
    /** AlarmTone.name */
    val tone: String,
    /** 10..100 */
    val volumePct: Int,
    /** SnoozeStyle.name */
    val snoozeStyle: String,
    val snoozeFixedMin: Int,
    val snoozeFixedCount: Int,
    val overrunMin: Int,
    /** Minute of day; null = the Settings bedtime rule. */
    val bedtimeMinOfDay: Int?,
    val nightStepsOn: Boolean,
    val windDownMin: Int,
    /** false = the every-evening list (routine_activities.selected); true = this day's day_routine_items. */
    val ownRoutine: Boolean,
)

/** A day's own Night steps list (only read when day_plans.ownRoutine). */
@Entity(
    tableName = "day_routine_items",
    primaryKeys = ["dayOfWeek", "activityId"],
    foreignKeys = [
        ForeignKey(
            entity = RoutineActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("activityId")],
)
data class DayRoutineItemEntity(val dayOfWeek: Int, val activityId: Long, val position: Int)

/** A one-off for one date (Day off / Just this once). Deleting it reverts to the week plan. */
@Entity(tableName = "plan_overrides")
data class PlanOverrideEntity(
    /** ISO-8601 local date. */
    @PrimaryKey val date: String,
    /** OverrideKind.name */
    val kind: String,
    val wakeMinOfDay: Int?,
    /** AlarmMode.name */
    val alarmMode: String?,
    val createdAtEpochMs: Long,
    val offsetSec: Int,
)

/** "Pause alarms until …" (at most one row, id = 1). */
@Entity(tableName = "plan_pauses")
data class PlanPauseEntity(
    @PrimaryKey val id: Int = 1,
    val fromDate: String,
    val untilDate: String,
    val createdAtEpochMs: Long,
    val offsetSec: Int,
)

// ---------------------------------------------------------------- Journal (v2)

/** A checked Night step. No FK: history survives deleting a custom activity. */
@Entity(tableName = "night_step_logs", primaryKeys = ["nightOf", "activityId"])
data class NightStepLogEntity(
    /** The evening, ISO-8601 local date. */
    val nightOf: String,
    val activityId: Long,
    /** Technique.name */
    val techniqueKey: String?,
    val doneAtEpochMs: Long,
    val offsetSec: Int,
)

/** The Tomorrow list written on the evening [nightOf]. */
@Entity(tableName = "tomorrow_lists")
data class TomorrowListEntity(
    @PrimaryKey val nightOf: String,
    val text: String,
    val updatedAtEpochMs: Long,
    val offsetSec: Int,
)

/** A night mark, keyed by the wake date of the night it goes with. */
@Entity(tableName = "night_marks", primaryKeys = ["wakeDate", "mark"])
data class NightMarkEntity(
    val wakeDate: String,
    /** Mark.name (stored marks only) */
    val mark: String,
    val createdAtEpochMs: Long,
    val offsetSec: Int,
)

/** One wake date's Morning feel. */
@Entity(tableName = "morning_feel")
data class MorningFeelEntity(
    @PrimaryKey val wakeDate: String,
    /** 1..5 */
    val level: Int,
    val atEpochMs: Long,
    val offsetSec: Int,
    /** FeelSource.name */
    val source: String,
)

// ---------------------------------------------------------------- Night log (v2)

/** A Drop anchor tap. */
@Entity(tableName = "anchors", indices = [Index("wakeDate")])
data class AnchorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atEpochMs: Long,
    val offsetSec: Int,
    val wakeDate: String,
    /** AnchorSource.name */
    val source: String,
    val wholeNightLog: Boolean,
)

/** One stream of phone movement (whole night after Drop anchor, or one surfacing window). */
@Entity(tableName = "movement_logs", indices = [Index("wakeDate")])
data class MovementLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochMs: Long,
    val endEpochMs: Long?,
    val offsetSec: Int,
    /** WHOLE_NIGHT | WINDOW */
    val mode: String,
    val wakeDate: String,
    val anchorId: Long?,
    val occurrenceId: String?,
)

/** Non-missing epochs only (a missing slot has no row). */
@Entity(
    tableName = "movement_epochs",
    primaryKeys = ["logId", "startEpochMs"],
    foreignKeys = [
        ForeignKey(
            entity = MovementLogEntity::class,
            parentColumns = ["id"],
            childColumns = ["logId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MovementEpochEntity(val logId: Long, val startEpochMs: Long, val activity: Double)

/** One alarm occurrence's history (updated across snoozes / backup). */
@Entity(tableName = "alarm_events", indices = [Index("wakeDate"), Index("occurrenceId")])
data class AlarmEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** RingKind.name */
    val kind: String,
    val occurrenceId: String?,
    val wakeDate: String,
    val targetEpochMs: Long,
    val windowStartEpochMs: Long,
    /** AlarmMode.name */
    val mode: String,
    /** WakeStyle.name */
    val wakeStyle: String,
    val firstRingEpochMs: Long,
    /** RingReason.name */
    val reason: String,
    val maxZ: Double?,
    /** Comma-separated epoch ms, one per snooze tap. */
    val snoozeTimes: String,
    val stoppedAtEpochMs: Long?,
    /** RingOutcome.name */
    val outcome: String?,
    val sensingStartEpochMs: Long?,
    val offsetSec: Int,
)

/** The restless-trigger tuning (at most one row, id = 1). "Reset to default" stores a RESET row at the default threshold. */
@Entity(tableName = "alarm_tuning")
data class AlarmTuningEntity(
    @PrimaryKey val id: Int = 1,
    val zThreshold: Double,
    /** TuningReason.name */
    val reason: String,
    val changedAtEpochMs: Long,
    val offsetSec: Int,
)

/** One Hush play. [endEpochMs] is null while it plays (or when the process died mid-play). */
@Entity(tableName = "hush_plays", indices = [Index("wakeDate")])
data class HushPlayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochMs: Long,
    val endEpochMs: Long?,
    val offsetSec: Int,
    /** HushColor.name */
    val color: String,
    val wakeDate: String,
)

/** Prompts shown, so each shows at most once per wake date. */
@Entity(tableName = "prompt_log", primaryKeys = ["wakeDate", "kind"])
data class PromptLogEntity(
    val wakeDate: String,
    /** PromptKind.name */
    val kind: String,
    val shownAtEpochMs: Long,
    val offsetSec: Int,
)
