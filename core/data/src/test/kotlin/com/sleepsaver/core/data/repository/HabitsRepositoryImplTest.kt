package com.sleepsaver.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.db.HabitEntity
import com.sleepsaver.core.data.testing.inMemoryDatabase
import com.sleepsaver.core.domain.habits.HabitCatalog
import com.sleepsaver.core.domain.model.HabitType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class HabitsRepositoryImplTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = inMemoryDatabase(context)
    private val repository = HabitsRepositoryImpl(context, db)

    @After
    fun tearDown() = db.close()

    @Test
    fun `first read seeds one row per habit type in catalog order`() = runTest {
        val settings = repository.habitSettings.first()

        assertThat(settings).isEqualTo(HabitCatalog().defaultSettings())
        assertThat(settings.map { it.type }).containsExactlyElementsIn(HabitType.entries)
        assertThat(db.habitDao().getAll().map { it.sortOrder }).isEqualTo(HabitType.entries.indices.toList())
    }

    @Test
    fun `a habit type missing from the table is added after the existing rows`() = runTest {
        db.habitDao().upsert(HabitEntity(HabitType.BEDTIME.name, enabled = false, reminderEnabled = false, offsetMinutes = 5, sortOrder = 0))

        val settings = repository.habitSettings.first()

        assertThat(settings).hasSize(HabitType.entries.size)
        assertThat(settings.first().type).isEqualTo(HabitType.BEDTIME)
        assertThat(settings.first().enabled).isFalse()
        assertThat(settings.first().offset).isEqualTo(Duration.ofMinutes(5))
    }

    @Test
    fun `setters persist and resetOffset restores the catalog default`() = runTest {
        repository.setEnabled(HabitType.ALCOHOL_CUTOFF, true)
        repository.setReminder(HabitType.ALCOHOL_CUTOFF, true)
        repository.setOffset(HabitType.CAFFEINE_CUTOFF, Duration.ofHours(-8))

        val changed = repository.habitSettings.first().associateBy { it.type }
        assertThat(changed.getValue(HabitType.ALCOHOL_CUTOFF).enabled).isTrue()
        assertThat(changed.getValue(HabitType.ALCOHOL_CUTOFF).reminderEnabled).isTrue()
        assertThat(changed.getValue(HabitType.CAFFEINE_CUTOFF).offset).isEqualTo(Duration.ofHours(-8))

        repository.resetOffset(HabitType.CAFFEINE_CUTOFF)
        val reset = repository.habitSettings.first().associateBy { it.type }
        assertThat(reset.getValue(HabitType.CAFFEINE_CUTOFF).offset).isEqualTo(Duration.ofHours(-10))
        assertThat(reset).hasSize(HabitType.entries.size)
    }

    @Test
    fun `routine defaults are seeded once, unselected, in order`() = runTest {
        val activities = listOf(
            async { repository.routineActivities.first() },
            async { repository.routineActivities.first() },
        ).awaitAll().first()

        assertThat(activities.map { it.label })
            .containsExactly("Reading", "Stretching", "Warm shower", "Podcast", "Journaling", "TV", "Gaming").inOrder()
        assertThat(activities.map { it.emoji })
            .containsExactly("📖", "🧘", "🚿", "🎧", "✍️", "📺", "🎮").inOrder()
        assertThat(activities.none { it.selected || it.isCustom }).isTrue()
        assertThat(db.routineActivityDao().count()).isEqualTo(7)
    }

    @Test
    fun `custom activities go last, selected, and can be deleted`() = runTest {
        repository.routineActivities.first()

        val id = repository.addCustomActivity("  Puzzles ", "🧩")

        val activities = repository.routineActivities.first()
        assertThat(activities.last().id).isEqualTo(id)
        assertThat(activities.last().label).isEqualTo("Puzzles")
        assertThat(activities.last().isCustom).isTrue()
        assertThat(activities.last().selected).isTrue()

        repository.setActivitySelected(activities.first().id, true)
        repository.deleteActivity(id)
        val after = repository.routineActivities.first()
        assertThat(after.map { it.id }).doesNotContain(id)
        assertThat(after.first().selected).isTrue()
    }

    @Test
    fun `blank custom label is rejected`() = runTest {
        val error = runCatching { repository.addCustomActivity("   ", "x") }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `defaults come back after the tables are cleared`() = runTest {
        repository.routineActivities.first()
        repository.setEnabled(HabitType.NAP_WINDOW, true)

        db.clearAllTables()

        assertThat(repository.routineActivities.first()).hasSize(7)
        assertThat(repository.habitSettings.first()).isEqualTo(HabitCatalog().defaultSettings())
    }
}
