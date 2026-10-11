package com.sleepsaver.core.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SleepSessionEntity::class,
        SleepStageEntity::class,
        NightOverrideEntity::class,
        AddedAwakeEntity::class,
        ManualNapEntity::class,
        VitalEntity::class,
        MorningReviewEntity::class,
        HabitEntity::class,
        RoutineActivityEntity::class,
        // v2
        DayPlanEntity::class,
        DayRoutineItemEntity::class,
        PlanOverrideEntity::class,
        PlanPauseEntity::class,
        NightStepLogEntity::class,
        TomorrowListEntity::class,
        NightMarkEntity::class,
        MorningFeelEntity::class,
        AnchorEntity::class,
        MovementLogEntity::class,
        MovementEpochEntity::class,
        AlarmEventEntity::class,
        AlarmTuningEntity::class,
        HushPlayEntity::class,
        PromptLogEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2, spec = Migration1To2::class)],
)
abstract class SleepSaverDatabase : RoomDatabase() {
    abstract fun sleepSessionDao(): SleepSessionDao
    abstract fun nightOverrideDao(): NightOverrideDao
    abstract fun addedAwakeDao(): AddedAwakeDao
    abstract fun manualNapDao(): ManualNapDao
    abstract fun vitalDao(): VitalDao
    abstract fun morningReviewDao(): MorningReviewDao
    abstract fun habitDao(): HabitDao
    abstract fun routineActivityDao(): RoutineActivityDao

    // v2
    abstract fun dayPlanDao(): DayPlanDao
    abstract fun dayRoutineItemDao(): DayRoutineItemDao
    abstract fun planOverrideDao(): PlanOverrideDao
    abstract fun planPauseDao(): PlanPauseDao
    abstract fun nightStepLogDao(): NightStepLogDao
    abstract fun tomorrowListDao(): TomorrowListDao
    abstract fun nightMarkDao(): NightMarkDao
    abstract fun morningFeelDao(): MorningFeelDao
    abstract fun anchorDao(): AnchorDao
    abstract fun movementLogDao(): MovementLogDao
    abstract fun alarmEventDao(): AlarmEventDao
    abstract fun alarmTuningDao(): AlarmTuningDao
    abstract fun hushPlayDao(): HushPlayDao
    abstract fun promptLogDao(): PromptLogDao

    companion object {
        const val NAME = "sleep_saver.db"
    }
}
