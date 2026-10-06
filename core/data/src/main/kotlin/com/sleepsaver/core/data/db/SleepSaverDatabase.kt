package com.sleepsaver.core.data.db

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
    ],
    version = 1,
    exportSchema = true,
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

    companion object {
        const val NAME = "sleep_saver.db"
    }
}
