package com.sleepsaver.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.db.RoutineActivityEntity
import com.sleepsaver.core.data.testing.inMemoryDatabase
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.nightsteps.Technique
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class RoutineActivityStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = inMemoryDatabase(context)
    private val store = RoutineActivityStore(db, context)
    private val steps = SleepConfig.DEFAULT.steps

    @After
    fun tearDown() = db.close()

    private fun custom(label: String) =
        RoutineActivityEntity(label = label, emoji = "🧩", isCustom = true, selected = true, sortOrder = 0)

    private val seededTechniques = listOf(
        Technique.SCREENS_DOWN, Technique.TOMORROW_LIST, Technique.SLOW_TIDE, Technique.READ_ON_PAPER,
        Technique.STRETCHING, Technique.WARM_SHOWER, Technique.JOURNALING,
    )

    @Test
    fun `seeds the seven Night steps defaults with their technique, timing and default selection`() = runTest {
        store.seedIfEmpty()

        val rows = db.routineActivityDao().getAll()
        assertThat(rows.map { it.label }).containsExactly(
            "Screens down", "Tomorrow list", "Slow tide breathing", "Read on paper", "Stretching", "Warm shower or bath", "Journaling",
        ).inOrder()
        assertThat(rows.map { it.techniqueKey }).containsExactlyElementsIn(seededTechniques.map { it.name }).inOrder()
        assertThat(rows.map { it.sortOrder }).containsExactly(0, 1, 2, 3, 4, 5, 6).inOrder()
        for ((row, technique) in rows.zip(seededTechniques)) {
            val timing = steps.timing(technique)
            assertThat(row.emoji).isEqualTo(technique.emoji)
            assertThat(row.selected).isEqualTo(technique.defaultOn)
            assertThat(row.isCustom).isFalse()
            assertThat(row.leadMin.toLong()).isEqualTo(timing.lead.toMinutes())
            assertThat(row.durationMin.toLong()).isEqualTo(timing.duration.toMinutes())
        }
        assertThat(rows.filter { it.selected }.map { it.techniqueKey })
            .containsExactly("SCREENS_DOWN", "TOMORROW_LIST", "SLOW_TIDE").inOrder()
    }

    @Test
    fun `a first write through the store seeds the defaults before it, then goes last`() = runTest {
        val id = store.insertAtEnd(custom("Puzzles"))

        val rows = db.routineActivityDao().getAll()
        assertThat(rows).hasSize(8)
        assertThat(rows.take(7).map { it.techniqueKey }).containsExactlyElementsIn(seededTechniques.map { it.name }).inOrder()
        assertThat(rows.last().id).isEqualTo(id)
        assertThat(rows.last().sortOrder).isEqualTo(7)
    }

    @Test
    fun `concurrent first writes seed once and get distinct sort orders`() = runTest {
        listOf(
            async { store.seedIfEmpty() },
            async { store.insertAtEnd(custom("A")) },
            async { store.insertAtEnd(custom("B")) },
            async { store.locked { dao -> dao.count() } },
        ).awaitAll()

        val rows = db.routineActivityDao().getAll()
        assertThat(rows).hasSize(9)
        assertThat(rows.map { it.sortOrder }.toSet()).hasSize(9)
    }

    @Test
    fun `locked seeds before running its block`() = runTest {
        val seen = store.locked { dao -> dao.count() }

        assertThat(seen).isEqualTo(7)
    }

    @Test
    fun `an existing table is never re-seeded`() = runTest {
        db.routineActivityDao().insert(custom("Only me").copy(sortOrder = 3))

        store.seedIfEmpty()
        store.insertAtEnd(custom("Next"))

        assertThat(db.routineActivityDao().getAll().map { it.label to it.sortOrder }).containsExactly("Only me" to 3, "Next" to 4).inOrder()
    }

    @Test
    fun `timing is stored in minutes within the pickers' ranges`() = runTest {
        val id = store.insertAtEnd(custom("Puzzles"))

        store.setTiming(id, lead = Duration.ofMinutes(500), duration = Duration.ofMinutes(-5))
        val clamped = db.routineActivityDao().getAll().single { it.id == id }
        store.setTiming(id, lead = Duration.ofMinutes(35), duration = Duration.ofSeconds(150))
        val set = db.routineActivityDao().getAll().single { it.id == id }

        assertThat(clamped.leadMin.toLong()).isEqualTo(steps.leadMax.toMinutes())
        assertThat(clamped.durationMin).isEqualTo(0)
        assertThat(set.leadMin to set.durationMin).isEqualTo(35 to 2)
    }
}
