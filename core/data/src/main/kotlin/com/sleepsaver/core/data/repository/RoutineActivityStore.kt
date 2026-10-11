package com.sleepsaver.core.data.repository

import android.content.Context
import androidx.annotation.StringRes
import com.sleepsaver.core.data.R
import com.sleepsaver.core.data.db.RoutineActivityDao
import com.sleepsaver.core.data.db.RoutineActivityEntity
import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.nightsteps.Technique
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single writer of routine_activities' seeding and ordering, shared by Habits and Night steps. Any first write to
 * the table, from either repository, seeds the seven Night steps defaults first, and one Mutex orders every sortOrder.
 *
 * New installs (and the table after "Reset all data") get, in this order: Screens down, Tomorrow list, Slow tide
 * breathing, Read on paper, Stretching, Warm shower or bath, Journaling; each with its technique, its default lead
 * and duration (`config.steps.timing`) and selected when the technique is on by default.
 */
@Singleton
internal class RoutineActivityStore @Inject constructor(
    database: SleepSaverDatabase,
    @ApplicationContext private val context: Context,
) {
    private val dao = database.routineActivityDao()
    private val lock = Mutex()
    private val steps = SleepConfig.DEFAULT.steps

    /** Seeds DEFAULT_ACTIVITIES when the table is empty; under the store's own Mutex. */
    suspend fun seedIfEmpty() = lock.withLock { seedLocked() }

    /** seedIfEmpty(), then [block] under the Mutex (find-or-insert, reorder). */
    suspend fun <T> locked(block: suspend (RoutineActivityDao) -> T): T = lock.withLock {
        seedLocked()
        block(dao)
    }

    /** seedIfEmpty(), then inserts [entity] with sortOrder = (maxSortOrder() ?: −1) + 1 under the Mutex; returns the id. */
    suspend fun insertAtEnd(entity: RoutineActivityEntity): Long = locked { dao ->
        dao.insert(entity.copy(sortOrder = (dao.maxSortOrder() ?: -1) + 1))
    }

    /** Lead and duration of one activity, in whole minutes, clamped to the pickers' ranges (0..leadMax, 0..durationMax). */
    suspend fun setTiming(id: Long, lead: Duration, duration: Duration) {
        dao.setTiming(
            id = id,
            leadMin = lead.coerceIn(Duration.ZERO, steps.leadMax).toMinutes().toInt(),
            durationMin = duration.coerceIn(Duration.ZERO, steps.durationMax).toMinutes().toInt(),
        )
    }

    /** Callers hold [lock]. */
    private suspend fun seedLocked() {
        if (dao.count() > 0) return
        dao.insertAll(
            DEFAULT_ACTIVITIES.mapIndexed { index, default ->
                val timing = steps.timing(default.technique)
                RoutineActivityEntity(
                    label = context.getString(default.label),
                    emoji = default.technique.emoji,
                    isCustom = false,
                    selected = default.technique.defaultOn,
                    sortOrder = index,
                    techniqueKey = default.technique.name,
                    leadMin = timing.lead.toMinutes().toInt(),
                    durationMin = timing.duration.toMinutes().toInt(),
                )
            },
        )
    }

    private class DefaultActivity(val technique: Technique, @param:StringRes val label: Int)

    private companion object {
        val DEFAULT_ACTIVITIES = listOf(
            DefaultActivity(Technique.SCREENS_DOWN, R.string.routine_screens_down),
            DefaultActivity(Technique.TOMORROW_LIST, R.string.routine_tomorrow_list),
            DefaultActivity(Technique.SLOW_TIDE, R.string.routine_slow_tide),
            DefaultActivity(Technique.READ_ON_PAPER, R.string.routine_reading),
            DefaultActivity(Technique.STRETCHING, R.string.routine_stretching),
            DefaultActivity(Technique.WARM_SHOWER, R.string.routine_warm_shower),
            DefaultActivity(Technique.JOURNALING, R.string.routine_journaling),
        )
    }
}
