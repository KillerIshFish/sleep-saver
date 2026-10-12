package com.sleepsaver.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sleepsaver.core.data.di.ApplicationScope
import com.sleepsaver.core.data.di.SettingsDataStore
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.config.SleepConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [HushSettings] in the "settings" preferences DataStore, so "Reset all data" clears them with the rest: the colour by
 * name, the timer in minutes with "Until my window" as its own flag, and the volume. Missing or unknown values read as
 * the defaults.
 *
 * A pick shows at once, before it is saved: a dragged volume slider sends a change per frame, and waiting for each
 * write would make it jump back. The newest unsaved pick overlays the store until the store holds it. Saves run one at
 * a time in [scope] (the app's, so leaving a screen never drops one), and a save already overtaken by a newer pick is
 * skipped, so the last pick is the one stored.
 *
 * Only screens inject this (never anything reachable before the first unlock), so reading the credential-protected
 * store when it is first created is safe.
 */
@Singleton
class HushSettingsRepositoryImpl @Inject constructor(
    @SettingsDataStore private val dataStore: DataStore<Preferences>,
    @ApplicationScope private val scope: CoroutineScope,
) : HushSettingsRepository {

    /** What the store holds; null until it has been read. */
    private val stored: StateFlow<HushSettings?> = dataStore.data
        .orFallback(TAG, "Reading Hush settings") { emptyPreferences() }
        .map { it.toHushSettings() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The newest pick the store doesn't hold yet. */
    private val pending = MutableStateFlow<HushSettings?>(null)

    private val saving = Mutex()

    init {
        // Once the store holds the pending pick, the overlay is no longer needed.
        stored.onEach { if (it != null) pending.compareAndSet(it, null) }.launchIn(scope)
    }

    override val settings: Flow<HushSettings> =
        combine(stored.filterNotNull(), pending) { stored, pending -> pending ?: stored }.distinctUntilChanged()

    override fun update(transform: (HushSettings) -> HushSettings) {
        lateinit var next: HushSettings
        pending.update { current -> transform(current ?: stored.value ?: HushSettings()).also { next = it } }
        scope.launch {
            saving.withLock {
                if (pending.value != next) return@withLock
                try {
                    dataStore.edit { it.putHushSettings(next) }
                } catch (e: Exception) {
                    // The pick stays on screen for this session; the next one tries again.
                    logFailure(TAG, "Saving Hush settings", e)
                    return@withLock
                }
                // Unchanged values never re-emit from the store, so the overlay goes here too.
                if (stored.value == next) pending.compareAndSet(next, null)
            }
        }
    }

    private companion object {
        const val TAG = "SleepSaverHush"
    }
}

internal object HushKeys {
    val COLOR = stringPreferencesKey("hushColor")
    val TIMER_MIN = intPreferencesKey("hushTimerMin")
    val UNTIL_WINDOW = booleanPreferencesKey("hushUntilWindow")
    val VOLUME = floatPreferencesKey("hushVolume")
}

/** Unknown colours, timers that aren't offered and volumes out of range read as the defaults ([config]'s Hush). */
internal fun Preferences.toHushSettings(config: SleepConfig = SleepConfig.DEFAULT): HushSettings {
    val hush = config.hush
    return HushSettings(
        color = this[HushKeys.COLOR]?.let { name -> HushColor.entries.firstOrNull { it.name == name } } ?: hush.defaultColor,
        timer = if (this[HushKeys.UNTIL_WINDOW] == true) {
            null
        } else {
            this[HushKeys.TIMER_MIN]?.let { Duration.ofMinutes(it.toLong()) }?.takeIf { it in hush.timers } ?: hush.defaultTimer
        },
        volume = this[HushKeys.VOLUME]?.takeIf { it in hush.minVolume..1f } ?: hush.defaultVolume,
    )
}

private fun MutablePreferences.putHushSettings(settings: HushSettings) {
    this[HushKeys.COLOR] = settings.color.name
    this[HushKeys.UNTIL_WINDOW] = settings.timer == null
    if (settings.timer != null) this[HushKeys.TIMER_MIN] = settings.timer.toMinutes().toInt()
    this[HushKeys.VOLUME] = settings.volume
}
