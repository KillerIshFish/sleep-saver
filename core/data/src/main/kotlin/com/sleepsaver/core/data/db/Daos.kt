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

    @Upsert
    suspend fun upsertAll(entities: List<NightOverrideEntity>)

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

    @Query("SELECT * FROM added_awake WHERE id = :id")
    suspend fun get(id: Long): AddedAwakeEntity?

    @Query("DELETE FROM added_awake WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM added_awake WHERE wakeDate = :wakeDate")
    suspend fun deleteForNight(wakeDate: String)

    /** Keeps the rows' ids (used to restore saved rows exactly). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<AddedAwakeEntity>)

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

    /** Keeps the rows' ids (used to restore saved rows exactly). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ManualNapEntity>)

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

    @Upsert
    suspend fun upsertAll(entities: List<MorningReviewEntity>)

    @Query("SELECT * FROM morning_reviews ORDER BY wakeDate")
    suspend fun getAll(): List<MorningReviewEntity>

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

    @Query("UPDATE routine_activities SET leadMin = :leadMin, durationMin = :durationMin WHERE id = :id")
    suspend fun setTiming(id: Long, leadMin: Int, durationMin: Int)

    /** The first activity (in grid order) carrying Technique [key]. */
    @Query("SELECT * FROM routine_activities WHERE techniqueKey = :key ORDER BY sortOrder, id LIMIT 1")
    suspend fun findByTechnique(key: String): RoutineActivityEntity?

    /** null when the table is empty. */
    @Query("SELECT MAX(sortOrder) FROM routine_activities")
    suspend fun maxSortOrder(): Int?
}

// ---------------------------------------------------------------- Week plan (v2)

@Dao
interface DayPlanDao {
    @Query("SELECT * FROM day_plans ORDER BY dayOfWeek")
    fun observeAll(): Flow<List<DayPlanEntity>>

    @Query("SELECT * FROM day_plans ORDER BY dayOfWeek")
    suspend fun getAll(): List<DayPlanEntity>

    @Query("SELECT * FROM day_plans WHERE dayOfWeek = :day")
    suspend fun get(day: Int): DayPlanEntity?

    @Upsert
    suspend fun upsert(entity: DayPlanEntity)

    @Upsert
    suspend fun upsertAll(entities: List<DayPlanEntity>)

    /** Keeps the rows' keys (used to restore saved rows exactly). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<DayPlanEntity>)

    @Query("DELETE FROM day_plans")
    suspend fun deleteAll()
}

@Dao
interface DayRoutineItemDao {
    @Query("SELECT * FROM day_routine_items ORDER BY dayOfWeek, position")
    fun observeAll(): Flow<List<DayRoutineItemEntity>>

    @Query("SELECT * FROM day_routine_items WHERE dayOfWeek = :day ORDER BY position")
    suspend fun getForDay(day: Int): List<DayRoutineItemEntity>

    @Query("DELETE FROM day_routine_items WHERE dayOfWeek = :day")
    suspend fun deleteForDay(day: Int)

    /** Deletes [day]'s items, then inserts [items], atomically. */
    @Transaction
    suspend fun replaceForDay(day: Int, items: List<DayRoutineItemEntity>) {
        deleteForDay(day)
        if (items.isNotEmpty()) insertAll(items)
    }

    /** The ids of [ids] that still exist in routine_activities (a day's list can't point at a deleted activity). */
    @Query("SELECT id FROM routine_activities WHERE id IN (:ids)")
    suspend fun existingActivityIds(ids: List<Long>): List<Long>

    @Query("SELECT * FROM day_routine_items ORDER BY dayOfWeek, position")
    suspend fun getAll(): List<DayRoutineItemEntity>

    /** Keeps the rows' keys (used to restore saved rows exactly). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<DayRoutineItemEntity>)

    @Query("DELETE FROM day_routine_items")
    suspend fun deleteAll()
}

@Dao
interface PlanOverrideDao {
    /** Overrides on or after [date] (ISO), by date. */
    @Query("SELECT * FROM plan_overrides WHERE date >= :date ORDER BY date")
    fun observeFrom(date: String): Flow<List<PlanOverrideEntity>>

    @Query("SELECT * FROM plan_overrides WHERE date >= :date ORDER BY date")
    suspend fun getFrom(date: String): List<PlanOverrideEntity>

    @Upsert
    suspend fun upsert(entity: PlanOverrideEntity)

    @Query("DELETE FROM plan_overrides WHERE date = :date")
    suspend fun delete(date: String)

    /** Prunes rows strictly before [date]. */
    @Query("DELETE FROM plan_overrides WHERE date < :date")
    suspend fun deleteBefore(date: String)

    @Query("SELECT * FROM plan_overrides ORDER BY date")
    suspend fun getAll(): List<PlanOverrideEntity>

    /** Keeps the rows' keys (used to restore saved rows exactly). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<PlanOverrideEntity>)

    @Query("DELETE FROM plan_overrides")
    suspend fun deleteAll()
}

@Dao
interface PlanPauseDao {
    @Query("SELECT * FROM plan_pauses ORDER BY id LIMIT 1")
    fun observe(): Flow<PlanPauseEntity?>

    @Query("SELECT * FROM plan_pauses ORDER BY id LIMIT 1")
    suspend fun get(): PlanPauseEntity?

    @Upsert
    suspend fun upsert(entity: PlanPauseEntity)

    @Query("DELETE FROM plan_pauses")
    suspend fun clear()

    @Query("SELECT * FROM plan_pauses ORDER BY id")
    suspend fun getAll(): List<PlanPauseEntity>

    /** Keeps the rows' keys (used to restore saved rows exactly). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<PlanPauseEntity>)

    @Query("DELETE FROM plan_pauses")
    suspend fun deleteAll()
}

// ---------------------------------------------------------------- Journal (v2)

@Dao
interface NightStepLogDao {
    @Query("SELECT * FROM night_step_logs ORDER BY nightOf, doneAtEpochMs")
    fun observeAll(): Flow<List<NightStepLogEntity>>

    /** Logs of evenings on or after [nightOf] (ISO). */
    @Query("SELECT * FROM night_step_logs WHERE nightOf >= :nightOf ORDER BY nightOf, doneAtEpochMs")
    fun observeFrom(nightOf: String): Flow<List<NightStepLogEntity>>

    @Query("SELECT * FROM night_step_logs WHERE nightOf = :nightOf ORDER BY doneAtEpochMs")
    fun observeFor(nightOf: String): Flow<List<NightStepLogEntity>>

    @Upsert
    suspend fun upsert(entity: NightStepLogEntity)

    @Query("DELETE FROM night_step_logs WHERE nightOf = :nightOf AND activityId = :activityId")
    suspend fun delete(nightOf: String, activityId: Long)

    /** The first evening anything was ever checked (ISO), null without logs. */
    @Query("SELECT MIN(nightOf) FROM night_step_logs")
    suspend fun firstNightOf(): String?

    @Query("SELECT * FROM night_step_logs ORDER BY nightOf, doneAtEpochMs")
    suspend fun getAll(): List<NightStepLogEntity>

    /** Keeps the rows' keys (used to restore saved rows exactly). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<NightStepLogEntity>)

    @Query("DELETE FROM night_step_logs")
    suspend fun deleteAll()
}

@Dao
interface TomorrowListDao {
    @Query("SELECT * FROM tomorrow_lists WHERE nightOf = :nightOf")
    fun observe(nightOf: String): Flow<TomorrowListEntity?>

    @Upsert
    suspend fun upsert(entity: TomorrowListEntity)

    @Query("DELETE FROM tomorrow_lists WHERE nightOf = :nightOf")
    suspend fun delete(nightOf: String)

    @Query("SELECT * FROM tomorrow_lists ORDER BY nightOf")
    suspend fun getAll(): List<TomorrowListEntity>

    /** Keeps the rows' keys (used to restore saved rows exactly). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<TomorrowListEntity>)

    @Query("DELETE FROM tomorrow_lists")
    suspend fun deleteAll()
}

/** One row of NightMarkDao.firstDates(): a stored mark and the first wake date it was set for (ISO string). */
data class MarkFirstDate(val mark: String, val first: String)

@Dao
interface NightMarkDao {
    @Query("SELECT * FROM night_marks ORDER BY wakeDate, mark")
    fun observeAll(): Flow<List<NightMarkEntity>>

    @Query("SELECT * FROM night_marks WHERE wakeDate = :wakeDate ORDER BY mark")
    fun observe(wakeDate: String): Flow<List<NightMarkEntity>>

    /** Setting a mark twice keeps the first row (and its time). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: NightMarkEntity)

    @Query("DELETE FROM night_marks WHERE wakeDate = :wakeDate AND mark = :mark")
    suspend fun delete(wakeDate: String, mark: String)

    // `first` is an SQL keyword (NULLS FIRST), hence the backticks.
    @Query("SELECT mark, MIN(wakeDate) AS `first` FROM night_marks GROUP BY mark")
    suspend fun firstDates(): List<MarkFirstDate>

    @Query("SELECT * FROM night_marks ORDER BY wakeDate, mark")
    suspend fun getAll(): List<NightMarkEntity>

    /** Keeps the rows' keys (used to restore saved rows exactly). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<NightMarkEntity>)

    @Query("DELETE FROM night_marks")
    suspend fun deleteAll()
}

@Dao
interface MorningFeelDao {
    @Query("SELECT * FROM morning_feel ORDER BY wakeDate")
    fun observeAll(): Flow<List<MorningFeelEntity>>

    @Query("SELECT * FROM morning_feel WHERE wakeDate = :wakeDate")
    fun observe(wakeDate: String): Flow<MorningFeelEntity?>

    @Upsert
    suspend fun upsert(entity: MorningFeelEntity)

    @Query("DELETE FROM morning_feel WHERE wakeDate = :wakeDate")
    suspend fun delete(wakeDate: String)

    @Query("SELECT * FROM morning_feel ORDER BY wakeDate")
    suspend fun getAll(): List<MorningFeelEntity>

    /** Keeps the rows' keys (used to restore saved rows exactly). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<MorningFeelEntity>)

    @Query("DELETE FROM morning_feel")
    suspend fun deleteAll()
}

@Dao
interface PromptLogDao {
    @Query("SELECT * FROM prompt_log WHERE wakeDate = :wakeDate AND kind = :kind")
    fun observe(wakeDate: String, kind: String): Flow<PromptLogEntity?>

    /** Showing a prompt twice keeps the first row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PromptLogEntity)

    @Query("SELECT * FROM prompt_log ORDER BY wakeDate, kind")
    suspend fun getAll(): List<PromptLogEntity>

    /** Keeps the rows' keys (used to restore saved rows exactly). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<PromptLogEntity>)

    @Query("DELETE FROM prompt_log")
    suspend fun deleteAll()
}

// ---------------------------------------------------------------- Night log (v2)

@Dao
interface AnchorDao {
    @Query("SELECT * FROM anchors ORDER BY atEpochMs, id")
    fun observeAll(): Flow<List<AnchorEntity>>

    @Query("SELECT * FROM anchors WHERE wakeDate = :wakeDate ORDER BY atEpochMs, id")
    fun observeFor(wakeDate: String): Flow<List<AnchorEntity>>

    @Query("SELECT * FROM anchors ORDER BY atEpochMs DESC, id DESC LIMIT 1")
    fun observeLatest(): Flow<AnchorEntity?>

    @Insert
    suspend fun insert(entity: AnchorEntity): Long

    @Query("DELETE FROM anchors WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM anchors ORDER BY atEpochMs, id")
    suspend fun getAll(): List<AnchorEntity>

    /** Keeps the rows' ids (used to restore saved rows exactly). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<AnchorEntity>)

    @Query("DELETE FROM anchors")
    suspend fun deleteAll()
}

@Dao
interface MovementLogDao {
    @Insert
    suspend fun insert(entity: MovementLogEntity): Long

    @Query("UPDATE movement_logs SET endEpochMs = :endEpochMs WHERE id = :id")
    suspend fun setEnd(id: Long, endEpochMs: Long)

    @Query("SELECT * FROM movement_logs WHERE wakeDate = :wakeDate ORDER BY startEpochMs, id")
    fun observeFor(wakeDate: String): Flow<List<MovementLogEntity>>

    /** An epoch already stored for its slot is kept. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEpochs(epochs: List<MovementEpochEntity>)

    @Query("SELECT * FROM movement_epochs WHERE logId = :logId ORDER BY startEpochMs")
    fun observeEpochs(logId: Long): Flow<List<MovementEpochEntity>>

    /** Every log without its epochs (export). */
    @Query("SELECT * FROM movement_logs ORDER BY startEpochMs, id")
    suspend fun getAllLogs(): List<MovementLogEntity>

    /** Keeps the rows' ids (used to restore saved rows exactly). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<MovementLogEntity>)

    /** Deletes every log; their epochs go with them (cascade). */
    @Query("DELETE FROM movement_logs")
    suspend fun deleteAll()
}

@Dao
interface AlarmEventDao {
    /** Room's row id on insert, −1 when an existing row was updated (never use it for an update). */
    @Upsert
    suspend fun upsert(entity: AlarmEventEntity): Long

    @Query("SELECT * FROM alarm_events ORDER BY firstRingEpochMs DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AlarmEventEntity>>

    @Query("SELECT * FROM alarm_events WHERE wakeDate = :wakeDate AND kind = 'PLANNED' ORDER BY firstRingEpochMs DESC LIMIT 1")
    fun observePlannedFor(wakeDate: String): Flow<AlarmEventEntity?>

    @Query("SELECT * FROM alarm_events WHERE kind = 'TEST' ORDER BY firstRingEpochMs DESC, id DESC LIMIT 1")
    fun observeLastTest(): Flow<AlarmEventEntity?>

    /** Occurrence ids of PLANNED events that ended (any outcome) with wakeDate ≥ [sinceWakeDate] (ISO). */
    @Query(
        "SELECT occurrenceId FROM alarm_events WHERE kind = 'PLANNED' AND outcome IS NOT NULL " +
            "AND occurrenceId IS NOT NULL AND wakeDate >= :sinceWakeDate",
    )
    suspend fun handledIds(sinceWakeDate: String): List<String>

    @Query("SELECT * FROM alarm_events WHERE kind = 'PLANNED' AND wakeDate >= :wakeDate ORDER BY wakeDate, firstRingEpochMs")
    fun observePlannedFrom(wakeDate: String): Flow<List<AlarmEventEntity>>

    @Query("SELECT * FROM alarm_events ORDER BY firstRingEpochMs, id")
    suspend fun getAll(): List<AlarmEventEntity>

    /** Keeps the rows' ids (used to restore saved rows exactly). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<AlarmEventEntity>)

    @Query("DELETE FROM alarm_events")
    suspend fun deleteAll()
}

@Dao
interface AlarmTuningDao {
    @Query("SELECT * FROM alarm_tuning ORDER BY id LIMIT 1")
    fun observe(): Flow<AlarmTuningEntity?>

    @Query("SELECT * FROM alarm_tuning ORDER BY id LIMIT 1")
    suspend fun get(): AlarmTuningEntity?

    @Upsert
    suspend fun upsert(entity: AlarmTuningEntity)

    @Query("DELETE FROM alarm_tuning")
    suspend fun clear()

    @Query("SELECT * FROM alarm_tuning ORDER BY id")
    suspend fun getAll(): List<AlarmTuningEntity>

    /** Keeps the rows' keys (used to restore saved rows exactly). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<AlarmTuningEntity>)

    @Query("DELETE FROM alarm_tuning")
    suspend fun deleteAll()
}

@Dao
interface HushPlayDao {
    @Insert
    suspend fun insert(entity: HushPlayEntity): Long

    @Query("UPDATE hush_plays SET endEpochMs = :endEpochMs WHERE id = :id")
    suspend fun setEnd(id: Long, endEpochMs: Long)

    @Query("SELECT * FROM hush_plays ORDER BY startEpochMs, id")
    fun observeAll(): Flow<List<HushPlayEntity>>

    @Query("SELECT * FROM hush_plays ORDER BY startEpochMs, id")
    suspend fun getAll(): List<HushPlayEntity>

    /** Keeps the rows' ids (used to restore saved rows exactly). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<HushPlayEntity>)

    @Query("DELETE FROM hush_plays")
    suspend fun deleteAll()
}
