package com.sleepsaver.core.data.repository

import android.content.Context
import androidx.annotation.StringRes
import com.sleepsaver.core.data.R
import com.sleepsaver.core.data.db.HabitEntity
import com.sleepsaver.core.data.db.RoutineActivityEntity
import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.data.mapping.toDomain
import com.sleepsaver.core.data.mapping.toDomainOrNull
import com.sleepsaver.core.data.mapping.toEntity
import com.sleepsaver.core.domain.habits.HabitCatalog
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.RoutineActivity
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * its catalog defaults.
 */
@Singleton
class HabitsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    database: SleepSaverDatabase,
) : HabitsRepository {

    private val habitDao = database.habitDao()
    private val activityDao = database.routineActivityDao()
    private val catalog = HabitCatalog()
    private val seedLock = Mutex()

    override val habitSettings: Flow<List<HabitSettings>> = habitDao.observeAll()
        .transform { rows ->
            // Seeding writes the table, and Room then emits again with the full list.
            if (missingTypes(rows).isEmpty()) emit(rows.mapNotNull { it.toDomainOrNull() }) else seedHabits()
        }
        .distinctUntilChanged()

    override val routineActivities: Flow<List<RoutineActivity>> = activityDao.observeAll()
        .transform { rows -> if (rows.isNotEmpty()) emit(rows.map { it.toDomain() }) else seedActivities() }
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
     * Custom activities go to the end of the grid, already selected. Seeds the defaults first, since a table
     * holding only this row would never get them.
     */
    override suspend fun addCustomActivity(label: String, emoji: String): Long {
        val trimmed = label.trim()
        require(trimmed.isNotEmpty()) { "label must not be blank" }
        seedActivities()
        return seedLock.withLock {
            val nextOrder = (activityDao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
            activityDao.insert(
                RoutineActivityEntity(label = trimmed, emoji = emoji.trim(), isCustom = true, selected = true, sortOrder = nextOrder),
            )
        }
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

    private suspend fun seedActivities() = seedLock.withLock {
        if (activityDao.count() > 0) return@withLock
        activityDao.insertAll(
            DEFAULT_ACTIVITIES.mapIndexed { index, activity ->
                RoutineActivityEntity(
                    label = context.getString(activity.label),
                    emoji = activity.emoji,
                    isCustom = false,
                    selected = false,
                    sortOrder = index,
                )
            },
        )
    }

    private class DefaultActivity(@param:StringRes val label: Int, val emoji: String)

    private companion object {
        private val DEFAULT_ACTIVITIES = listOf(
            DefaultActivity(R.string.routine_reading, "📖"),
            DefaultActivity(R.string.routine_stretching, "🧘"),
            DefaultActivity(R.string.routine_warm_shower, "🚿"),
            DefaultActivity(R.string.routine_podcast, "🎧"),
            DefaultActivity(R.string.routine_journaling, "✍️"),
            DefaultActivity(R.string.routine_tv, "📺"),
            DefaultActivity(R.string.routine_gaming, "🎮"),
        )
    }
}
