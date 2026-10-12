package com.sleepsaver.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.testing.preferencesStore
import com.sleepsaver.core.domain.alarm.HushColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Duration

/**
 * The store's file reads and writes run off the test scheduler, so a test waits for a save by waiting for the store
 * (or the repository) to show it, never with advanceUntilIdle.
 */
class HushSettingsRepositoryImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val colorKey = stringPreferencesKey("hushColor")

    private fun TestScope.store(): DataStore<Preferences> = preferencesStore(backgroundScope, tmp.root)

    private fun TestScope.repository(store: DataStore<Preferences>) = HushSettingsRepositoryImpl(store, backgroundScope)

    @Test
    fun `nothing picked yet reads as pink for 45 min at 25 percent`() = runTest {
        assertThat(repository(store()).settings.first()).isEqualTo(HushSettings(HushColor.PINK, Duration.ofMinutes(45), 0.25f))
    }

    @Test
    fun `a pick shows at once and is stored for every screen and the next launch`() = runTest {
        val store = store()
        val repository = repository(store)

        repository.update { it.copy(color = HushColor.BROWN, timer = null) }

        val picked = HushSettings(HushColor.BROWN, null, 0.25f)
        assertThat(repository.settings.first()).isEqualTo(picked)
        val raw = store.data.first { it[colorKey] == "BROWN" }
        assertThat(raw[booleanPreferencesKey("hushUntilWindow")]).isTrue()
        assertThat(raw[floatPreferencesKey("hushVolume")]).isEqualTo(0.25f)
        assertThat(repository(store).settings.first()).isEqualTo(picked)
    }

    @Test
    fun `quick changes in a row keep each other and only the last one is stored`() = runTest {
        val store = store()
        val repository = repository(store)
        repository.settings.first()

        // A dragged slider: no change waits for the one before it to be saved.
        repository.update { it.copy(color = HushColor.WHITE) }
        listOf(0.3f, 0.35f, 0.4f).forEach { volume -> repository.update { it.copy(volume = volume) } }
        repository.update { it.copy(timer = Duration.ofMinutes(30)) }

        val last = HushSettings(HushColor.WHITE, Duration.ofMinutes(30), 0.4f)
        assertThat(repository.settings.first()).isEqualTo(last)
        val raw = store.data.first { it[colorKey] != null }
        assertThat(raw[floatPreferencesKey("hushVolume")]).isEqualTo(0.4f)
        assertThat(raw[intPreferencesKey("hushTimerMin")]).isEqualTo(30)
        assertThat(repository(store).settings.first()).isEqualTo(last)
    }

    @Test
    fun `once a pick is saved, a change to the store itself shows (Reset all data)`() = runTest {
        val store = store()
        val repository = repository(store)
        repository.update { it.copy(color = HushColor.BROWN) }
        store.data.first { it[colorKey] == "BROWN" }

        store.edit { it.clear() }

        // Waits until the saved pick no longer covers the store (the test's timeout fails it otherwise).
        assertThat(repository.settings.first { it.color == HushColor.PINK }).isEqualTo(HushSettings())
    }

    @Test
    fun `unknown colours, timers that aren't offered and odd volumes read as the defaults`() = runTest {
        val store = store()
        store.edit {
            it[colorKey] = "TEAL"
            it[intPreferencesKey("hushTimerMin")] = 20
            it[floatPreferencesKey("hushVolume")] = 3f
        }

        assertThat(repository(store).settings.first()).isEqualTo(HushSettings())
    }

    @Test
    fun `a store that can't be read yields the defaults instead of crashing`() = runTest {
        val unreadable = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("disk unavailable") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk unavailable")
        }
        val repository = repository(unreadable)

        assertThat(repository.settings.first()).isEqualTo(HushSettings())

        // A pick still shows for the session even though it can't be saved.
        repository.update { it.copy(color = HushColor.WHITE) }
        advanceUntilIdle()
        assertThat(repository.settings.first().color).isEqualTo(HushColor.WHITE)
    }
}
