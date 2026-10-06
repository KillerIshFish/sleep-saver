package com.sleepsaver.core.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.sleepsaver.core.data.repository.SyncError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** What the sync layer remembers between runs. */
data class SyncStateSnapshot(
    val changesToken: String? = null,
    /** SleepDataSource.id that issued [changesToken]. */
    val tokenSourceId: String? = null,
    /** Change-feed permissions granted when [changesToken] was created (see HealthConnectPermissions.changeFeedScope). */
    val tokenScope: Set<String> = emptySet(),
    val lastSuccessAt: Instant? = null,
    val lastError: SyncError? = null,
    val historyImportDone: Boolean = false,
)

/** Sync bookkeeping in its own private DataStore file ("sync_state"), separate from user settings. */
@Singleton
class SyncStateStore internal constructor(private val dataStore: DataStore<Preferences>) {

    @Inject constructor(@ApplicationContext context: Context) : this(
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile(FILE_NAME) }),
    )

    val data: Flow<SyncStateSnapshot> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSnapshot() }

    suspend fun current(): SyncStateSnapshot = data.first()

    suspend fun saveToken(token: String, sourceId: String, scope: Set<String>) {
        dataStore.edit {
            it[TOKEN] = token
            it[TOKEN_SOURCE] = sourceId
            it[TOKEN_SCOPE] = scope
        }
    }

    suspend fun clearToken() {
        dataStore.edit {
            it.remove(TOKEN)
            it.remove(TOKEN_SOURCE)
            it.remove(TOKEN_SCOPE)
        }
    }

    suspend fun recordSuccess(at: Instant) {
        dataStore.edit {
            it[LAST_SUCCESS] = at.toEpochMilli()
            it.remove(LAST_ERROR)
        }
    }

    suspend fun recordError(error: SyncError) {
        dataStore.edit { it[LAST_ERROR] = error.name }
    }

    suspend fun setHistoryImportDone() {
        dataStore.edit { it[HISTORY_IMPORT_DONE] = true }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private fun Preferences.toSnapshot() = SyncStateSnapshot(
        changesToken = this[TOKEN],
        tokenSourceId = this[TOKEN_SOURCE],
        tokenScope = this[TOKEN_SCOPE].orEmpty(),
        lastSuccessAt = this[LAST_SUCCESS]?.let(Instant::ofEpochMilli),
        lastError = this[LAST_ERROR]?.let { name -> SyncError.entries.firstOrNull { it.name == name } },
        historyImportDone = this[HISTORY_IMPORT_DONE] ?: false,
    )

    companion object {
        const val FILE_NAME = "sync_state"
        private val TOKEN = stringPreferencesKey("changes_token")
        private val TOKEN_SOURCE = stringPreferencesKey("changes_token_source")
        private val TOKEN_SCOPE = stringSetPreferencesKey("changes_token_scope")
        private val LAST_SUCCESS = longPreferencesKey("last_success_epoch_ms")
        private val LAST_ERROR = stringPreferencesKey("last_error")
        private val HISTORY_IMPORT_DONE = booleanPreferencesKey("history_import_done")
    }
}
