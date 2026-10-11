package com.sleepsaver.core.data.repository

import com.sleepsaver.core.data.db.HabitEntity
import com.sleepsaver.core.data.db.RoutineActivityEntity
import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.data.mapping.toDomain
import com.sleepsaver.core.data.mapping.toDomainOrNull
import com.sleepsaver.core.data.mapping.toEntity
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.habits.HabitCatalog
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.RoutineActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Habit settings and evening-routine activities in Room. Both tables are seeded with defaults whenever they
 * are read empty (first launch, or after "Reset all data"); a habit type missing from the table is added with
 * its catalog defaults. Routine activities are seeded and ordered by [RoutineActivityStore], which Night steps
 * shares, so the first write from either side seeds the same defaults.
 */
@Singleton
class HabitsRepositoryImpl @Inject internal constructor(
    database: SleepSaverDatabase,
    private val store: RoutineActivityStore,
) : HabitsRepository {

    private val habitDao = database.habitDao()
    private val activityDao = database.routineActivityDao()
    private val catalog = HabitCatalog()
    private val steps = SleepConfig.DEFAULT.steps

    /** Guards the habits table only; routine_activities has the store's own lock. */
    private val seedLock = Mutex()

    /** Falls back to the catalog defaults if the table can't be read or seeded (it feeds the insights state). */
    override val habitSettings: Flow<List<HabitSettings>> = habitDao.observeAll()
        .transform { rows ->
            // Seeding writes the table, and Room then emits again with the full list.
            if (missingTypes(rows).isEmpty()) emit(rows.mapNotNull { it.toDomainOrNull() }) else seedHabits()
        }
        .orFallback(TAG, "Habit settings") { catalog.defaultSettings() }
        .distinctUntilChanged()

    override val routineActivities: Flow<List<RoutineActivity>> = activityDao.observeAll()
        .transform { rows -> if (rows.isNotEmpty()) emit(rows.map { it.toDomain() }) else store.seedIfEmpty() }
        .orFallback(TAG, "Routine activities") { emptyList() }
        .distinctUntilChanged()

    override suspend fun setEnabled(type: HabitType, enabled: Boolean) = updateHabit(type) { it.copy(enabled = enabled) }

    override suspend fun setReminder(type: HabitType, enabled: Boolean) =
        updateHabit(type) { it.copy(reminderEnabled = enabled) }

    override suspend fun setOffset(type: HabitType, offset: Duration) =
        updateHabit(type) { it.copy(offsetMinutes = offset.toMinutes().toInt()) }

    override suspend fun resetOffset(type: HabitType) =
        setOffset(type, catalog.definition(type).defaultOffset)

    override suspend fun setActivitySelected(id: Long, selected: Boolean) = activityDao.setSelected(id, selected)

    /**
     * Custom activities go to the end of the grid, already selected, with the custom lead and duration. The store
     * seeds the defaults first, since a table holding only this row would never get them.
     */
    override suspend fun addCustomActivity(label: String, emoji: String): Long {
        val trimmed = label.trim()
        require(trimmed.isNotEmpty()) { "label must not be blank" }
        return store.insertAtEnd(
            RoutineActivityEntity(
                label = trimmed,
                emoji = emoji.trim(),
                isCustom = true,
                selected = true,
                sortOrder = 0, // the store puts it last
                techniqueKey = null,
                leadMin = steps.customLead.toMinutes().toInt(),
                durationMin = steps.customDuration.toMinutes().toInt(),
            ),
        )
    }

    override suspend fun deleteActivity(id: Long) = activityDao.delete(id)

    private suspend fun updateHabit(type: HabitType, change: (HabitEntity) -> HabitEntity) {
        seedHabits()
        seedLock.withLock {
            val current = habitDao.getAll().first { it.type == type.name }
            habitDao.upsert(change(current))
        }
    }

    private fun missingTypes(rows: List<HabitEntity>): List<HabitType> {
        val stored = rows.mapTo(HashSet()) { it.type }
        return catalog.all.map { it.type }.filter { it.name !in stored }
    }

    private suspend fun seedHabits() = seedLock.withLock {
        val rows = habitDao.getAll()
        val missing = missingTypes(rows).toSet()
        if (missing.isEmpty()) return@withLock
        // Fresh table: catalog order. A type added in a later version goes after the existing rows.
        val firstFreeOrder = (rows.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val seeded = catalog.defaultSettings()
            .filter { it.type in missing }
            .mapIndexed { i, settings -> settings.toEntity(sortOrder = firstFreeOrder + i) }
        habitDao.upsertAll(seeded)
    }

    private companion object {
        const val TAG = "HabitsRepository"
    }
}
