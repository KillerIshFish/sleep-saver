package com.sleepsaver.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepSessionDao {
    @Transaction
    @Query("SELECT * FROM sleep_sessions ORDER BY startEpochMs")
    fun observeAllWithStages(): Flow<List<SessionWithStages>>

    @Transaction
    @Query("SELECT * FROM sleep_sessions WHERE endEpochMs >= :fromMs AND startEpochMs <= :toMs ORDER BY startEpochMs")
    suspend fun getWithStages(fromMs: Long, toMs: Long): List<SessionWithStages>

    @Upsert
    suspend fun upsertSessions(sessions: List<SleepSessionEntity>)

    @Query("DELETE FROM sleep_stages WHERE sessionId IN (:sessionIds)")
    suspend fun deleteStagesFor(sessionIds: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStages(stages: List<SleepStageEntity>)

    /** Upserts sessions and replaces their stages atomically. */
    @Transaction
    suspend fun replace(sessions: List<SessionWithStages>) {
        if (sessions.isEmpty()) return
        upsertSessions(sessions.map { it.session })
        deleteStagesFor(sessions.map { it.session.id })
        insertStages(sessions.flatMap { it.stages })
    }

    @Query("DELETE FROM sleep_sessions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM sleep_sessions WHERE sourceKind = :sourceKind")
    suspend fun deleteBySourceKind(sourceKind: String)

    @Query("SELECT DISTINCT originPackage FROM sleep_sessions WHERE sourceKind = :sourceKind ORDER BY originPackage")
    fun observeOrigins(sourceKind: String): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM sleep_sessions WHERE sourceKind = :sourceKind")
    suspend fun count(sourceKind: String): Int

    @Query("SELECT MAX(endEpochMs) FROM sleep_sessions WHERE sourceKind = :sourceKind")
    suspend fun latestEnd(sourceKind: String): Long?

    @Query("DELETE FROM sleep_sessions")
    suspend fun deleteAll()
}

@Dao
interface NightOverrideDao {
    @Query("SELECT * FROM night_overrides ORDER BY wakeDate")
    fun observeAll(): Flow<List<NightOverrideEntity>>

    @Query("SELECT * FROM night_overrides WHERE wakeDate = :wakeDate")
    suspend fun get(wakeDate: String): NightOverrideEntity?

    @Upsert
    suspend fun upsert(entity: NightOverrideEntity)

    @Query("DELETE FROM night_overrides WHERE wakeDate = :wakeDate")
    suspend fun delete(wakeDate: String)

    @Query("DELETE FROM night_overrides WHERE updatedAtEpochMs >= :sinceMs")
    suspend fun deleteUpdatedSince(sinceMs: Long)

    @Query("SELECT * FROM night_overrides")
    suspend fun getAll(): List<NightOverrideEntity>

    @Query("DELETE FROM night_overrides")
    suspend fun deleteAll()
}

@Dao
interface AddedAwakeDao {
    @Query("SELECT * FROM added_awake ORDER BY startEpochMs")
    fun observeAll(): Flow<List<AddedAwakeEntity>>

    @Insert
    suspend fun insert(entity: AddedAwakeEntity): Long

    @Query("DELETE FROM added_awake WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM added_awake WHERE wakeDate = :wakeDate")
    suspend fun deleteForNight(wakeDate: String)

    @Query("DELETE FROM added_awake WHERE createdAtEpochMs >= :sinceMs")
    suspend fun deleteCreatedSince(sinceMs: Long)

    @Query("SELECT * FROM added_awake")
    suspend fun getAll(): List<AddedAwakeEntity>

    @Query("DELETE FROM added_awake")
    suspend fun deleteAll()
}

@Dao
interface ManualNapDao {
    @Query("SELECT * FROM manual_naps ORDER BY startEpochMs")
    fun observeAll(): Flow<List<ManualNapEntity>>

    @Insert
    suspend fun insert(entity: ManualNapEntity): Long

    @Query("DELETE FROM manual_naps WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM manual_naps WHERE createdAtEpochMs >= :sinceMs")
    suspend fun deleteCreatedSince(sinceMs: Long)

    @Query("SELECT * FROM manual_naps")
    suspend fun getAll(): List<ManualNapEntity>

    @Query("DELETE FROM manual_naps")
    suspend fun deleteAll()
}

@Dao
interface VitalDao {
    @Query("SELECT * FROM vitals WHERE timeEpochMs BETWEEN :fromMs AND :toMs ORDER BY timeEpochMs")
    fun observeRange(fromMs: Long, toMs: Long): Flow<List<VitalEntity>>

    @Upsert
    suspend fun upsert(vitals: List<VitalEntity>)

    @Query("DELETE FROM vitals WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM vitals WHERE sourceKind = :sourceKind")
    suspend fun deleteBySourceKind(sourceKind: String)

    @Query("SELECT * FROM vitals")
    suspend fun getAll(): List<VitalEntity>

    @Query("DELETE FROM vitals")
    suspend fun deleteAll()
}

@Dao
interface MorningReviewDao {
    @Query("SELECT * FROM morning_reviews WHERE wakeDate = :wakeDate")
    fun observe(wakeDate: String): Flow<MorningReviewEntity?>

    @Query("SELECT * FROM morning_reviews WHERE wakeDate = :wakeDate")
    suspend fun get(wakeDate: String): MorningReviewEntity?

    @Upsert
    suspend fun upsert(entity: MorningReviewEntity)

    @Query("DELETE FROM morning_reviews WHERE atEpochMs >= :sinceMs")
    suspend fun deleteSince(sinceMs: Long)

    @Query("DELETE FROM morning_reviews")
    suspend fun deleteAll()
}

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits ORDER BY sortOrder")
    fun observeAll(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits ORDER BY sortOrder")
    suspend fun getAll(): List<HabitEntity>

    @Upsert
    suspend fun upsert(entity: HabitEntity)

    @Upsert
    suspend fun upsertAll(entities: List<HabitEntity>)

    @Query("DELETE FROM habits")
    suspend fun deleteAll()
}

@Dao
interface RoutineActivityDao {
    @Query("SELECT * FROM routine_activities ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<RoutineActivityEntity>>

    @Query("SELECT COUNT(*) FROM routine_activities")
    suspend fun count(): Int

    @Insert
    suspend fun insert(entity: RoutineActivityEntity): Long

    @Insert
    suspend fun insertAll(entities: List<RoutineActivityEntity>)

    @Query("UPDATE routine_activities SET selected = :selected WHERE id = :id")
    suspend fun setSelected(id: Long, selected: Boolean)

    @Query("DELETE FROM routine_activities WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM routine_activities ORDER BY sortOrder, id")
    suspend fun getAll(): List<RoutineActivityEntity>

    @Query("DELETE FROM routine_activities")
    suspend fun deleteAll()
}
