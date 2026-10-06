package com.sleepsaver.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sleepsaver.core.data.di.SettingsDataStore
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.model.UserSleepSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AppSettings] in the "settings" preferences DataStore. Durations are stored as minutes, times of day as
 * minute-of-day, instants as epoch millis, the source as [SettingsKeys.ALL_SOURCES] or a package name.
 * Missing keys read as the [AppSettings] defaults.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @SettingsDataStore private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toAppSettings() }
        .distinctUntilChanged()

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun setSleepNeed(need: Duration) = edit { it[SettingsKeys.SLEEP_NEED_MIN] = need.toMinutes().toInt() }

    override suspend fun setTypicalWake(time: LocalTime) =
        edit { it[SettingsKeys.TYPICAL_WAKE_MIN_OF_DAY] = time.toSecondOfDay() / 60 }

    override suspend fun setStreakThreshold(threshold: Duration) =
        edit { it[SettingsKeys.STREAK_THRESHOLD_MIN] = threshold.toMinutes().toInt() }

    override suspend fun setDebtThresholds(lowMax: Duration, highMin: Duration) = edit {
        it[SettingsKeys.LOW_DEBT_MAX_MIN] = lowMax.toMinutes().toInt()
        it[SettingsKeys.HIGH_DEBT_MIN_MIN] = highMin.toMinutes().toInt()
    }

    override suspend fun setSource(source: SourceSelection) = edit { it[SettingsKeys.SELECTED_SOURCE] = source.encode() }

    override suspend fun setDemoModeFlag(enabled: Boolean, startedAt: Instant?) = edit {
        it[SettingsKeys.DEMO_MODE] = enabled
        it.putOrRemove(SettingsKeys.DEMO_STARTED_AT_MS, startedAt?.toEpochMilli())
    }

    override suspend fun setClockFormat(format: ClockFormat) = edit { it[SettingsKeys.CLOCK_FORMAT] = format.name }

    override suspend fun setMorningReviewEnabled(enabled: Boolean) =
        edit { it[SettingsKeys.MORNING_REVIEW_ENABLED] = enabled }

    override suspend fun setOnboardingDone(done: Boolean) = edit { it[SettingsKeys.ONBOARDING_DONE] = done }

    override suspend fun dismissNeedSuggestion(value: Duration, at: Instant) = edit {
        it[SettingsKeys.NEED_SUGGESTION_DISMISSED_VALUE_MIN] = value.toMinutes().toInt()
        it[SettingsKeys.NEED_SUGGESTION_DISMISSED_AT_MS] = at.toEpochMilli()
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    companion object {
        /** File name of the settings store (`settings.preferences_pb`). */
        const val STORE_NAME = "settings"
    }
}

internal object SettingsKeys {
    const val ALL_SOURCES = "*all*"

    val SLEEP_NEED_MIN = intPreferencesKey("sleepNeedMin")
    val TYPICAL_WAKE_MIN_OF_DAY = intPreferencesKey("typicalWakeMinOfDay")
    val STREAK_THRESHOLD_MIN = intPreferencesKey("streakThresholdMin")
    val LOW_DEBT_MAX_MIN = intPreferencesKey("lowDebtMaxMin")
    val HIGH_DEBT_MIN_MIN = intPreferencesKey("highDebtMinMin")
    val SELECTED_SOURCE = stringPreferencesKey("selectedSource")
    val DEMO_MODE = booleanPreferencesKey("demoMode")
    val DEMO_STARTED_AT_MS = longPreferencesKey("demoStartedAtEpochMs")
    val CLOCK_FORMAT = stringPreferencesKey("clockFormat")
    val MORNING_REVIEW_ENABLED = booleanPreferencesKey("morningReviewEnabled")
    val ONBOARDING_DONE = booleanPreferencesKey("onboardingDone")
    val NEED_SUGGESTION_DISMISSED_VALUE_MIN = intPreferencesKey("needSuggestionDismissedValueMin")
    val NEED_SUGGESTION_DISMISSED_AT_MS = longPreferencesKey("needSuggestionDismissedAtEpochMs")
}

internal fun Preferences.toAppSettings(): AppSettings {
    val defaults = AppSettings()
    val sleepDefaults = defaults.sleep
    return AppSettings(
        sleep = UserSleepSettings(
            sleepNeed = minutes(SettingsKeys.SLEEP_NEED_MIN) ?: sleepDefaults.sleepNeed,
            typicalWake = this[SettingsKeys.TYPICAL_WAKE_MIN_OF_DAY]
                ?.let { LocalTime.ofSecondOfDay(Math.floorMod(it, MINUTES_PER_DAY) * 60L) }
                ?: sleepDefaults.typicalWake,
            streakThreshold = minutes(SettingsKeys.STREAK_THRESHOLD_MIN) ?: sleepDefaults.streakThreshold,
            lowDebtMax = minutes(SettingsKeys.LOW_DEBT_MAX_MIN) ?: sleepDefaults.lowDebtMax,
            highDebtMin = minutes(SettingsKeys.HIGH_DEBT_MIN_MIN) ?: sleepDefaults.highDebtMin,
            source = this[SettingsKeys.SELECTED_SOURCE]?.let(::decodeSource) ?: sleepDefaults.source,
        ),
        demoMode = this[SettingsKeys.DEMO_MODE] ?: defaults.demoMode,
        demoStartedAt = this[SettingsKeys.DEMO_STARTED_AT_MS]?.let(Instant::ofEpochMilli),
        clockFormat = this[SettingsKeys.CLOCK_FORMAT]
            ?.let { name -> ClockFormat.entries.firstOrNull { it.name == name } }
            ?: defaults.clockFormat,
        morningReviewEnabled = this[SettingsKeys.MORNING_REVIEW_ENABLED] ?: defaults.morningReviewEnabled,
        onboardingDone = this[SettingsKeys.ONBOARDING_DONE] ?: defaults.onboardingDone,
        needSuggestionDismissedValue = minutes(SettingsKeys.NEED_SUGGESTION_DISMISSED_VALUE_MIN),
        needSuggestionDismissedAt = this[SettingsKeys.NEED_SUGGESTION_DISMISSED_AT_MS]?.let(Instant::ofEpochMilli),
    )
}

internal fun SourceSelection.encode(): String = when (this) {
    SourceSelection.All -> SettingsKeys.ALL_SOURCES
    is SourceSelection.Single -> packageName
}

internal fun decodeSource(value: String): SourceSelection =
    if (value == SettingsKeys.ALL_SOURCES) SourceSelection.All else SourceSelection.Single(value)

private const val MINUTES_PER_DAY = 24 * 60

private fun Preferences.minutes(key: Preferences.Key<Int>): Duration? = this[key]?.let { Duration.ofMinutes(it.toLong()) }

private fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?) {
    if (value == null) remove(key) else this[key] = value
}
