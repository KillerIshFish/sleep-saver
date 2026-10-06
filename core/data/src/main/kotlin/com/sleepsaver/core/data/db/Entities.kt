package com.sleepsaver.core.data.db

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

@Entity(tableName = "routine_activities")
data class RoutineActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val emoji: String,
    val isCustom: Boolean,
    val selected: Boolean,
    val sortOrder: Int,
)
