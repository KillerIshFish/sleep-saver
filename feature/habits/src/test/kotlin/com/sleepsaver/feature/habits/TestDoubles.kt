package com.sleepsaver.feature.habits

import com.sleepsaver.core.data.hooks.ReminderPlanner
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.testing.FakeHabitsRepository
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.time.Duration

/** Counts replans so tests can check that every reminder-relevant change re-arms the alarms. */
class RecordingReminderPlanner : ReminderPlanner {
    var replans = 0
        private set
    var cancels = 0
        private set

    override suspend fun replan() {
        replans++
    }

    override fun cancelAll() {
        cancels++
    }
}

/** Every write fails, the way a broken database would. Reads still work. */
class FailingHabitsRepository(private val delegate: FakeHabitsRepository = FakeHabitsRepository()) :
    HabitsRepository by delegate {
    override suspend fun setEnabled(type: HabitType, enabled: Boolean) = throw IOException("disk full")
    override suspend fun setReminder(type: HabitType, enabled: Boolean) = throw IOException("disk full")
    override suspend fun setOffset(type: HabitType, offset: Duration) = throw IOException("disk full")
    override suspend fun resetOffset(type: HabitType) = throw IOException("disk full")
    override suspend fun setActivitySelected(id: Long, selected: Boolean) = throw IOException("disk full")
    override suspend fun addCustomActivity(label: String, emoji: String): Long = throw IOException("disk full")
    override suspend fun deleteActivity(id: Long) = throw IOException("disk full")
}

/** Offset writes wait for [gate], like a slow database; everything else goes straight to the fake. */
class SlowOffsetHabitsRepository(val delegate: FakeHabitsRepository = FakeHabitsRepository()) :
    HabitsRepository by delegate {
    val gate = CompletableDeferred<Unit>()
    override suspend fun setOffset(type: HabitType, offset: Duration) {
        gate.await()
        delegate.setOffset(type, offset)
    }

    override suspend fun resetOffset(type: HabitType) {
        gate.await()
        delegate.resetOffset(type)
    }
}

/** The fake's default activities plus one custom tile. */
val ActivitiesWithCustom: List<RoutineActivity> =
    FakeHabitsRepository.DEFAULT_ACTIVITIES + RoutineActivity(8, "Herbal tea", "🍵", isCustom = true, selected = true)
