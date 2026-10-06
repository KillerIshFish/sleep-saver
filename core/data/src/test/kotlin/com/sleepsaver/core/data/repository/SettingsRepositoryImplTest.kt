package com.sleepsaver.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.testing.instant
import com.sleepsaver.core.data.testing.preferencesStore
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.model.UserSleepSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Duration
import java.time.LocalTime

class SettingsRepositoryImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.store(): DataStore<Preferences> = preferencesStore(backgroundScope, tmp.root)

    @Test
    fun `empty store reads as the defaults`() = runTest {
        val repository = SettingsRepositoryImpl(store())

        val settings = repository.current()

        assertThat(settings).isEqualTo(AppSettings())
        assertThat(settings.sleep).isEqualTo(UserSleepSettings())
        assertThat(settings.sleep.source).isEqualTo(SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE))
        assertThat(settings.morningReviewEnabled).isTrue()
    }

    @Test
    fun `every field round-trips`() = runTest {
        val repository = SettingsRepositoryImpl(store())
        val demoStart = instant("2026-10-01T09:00:00Z")
        val dismissedAt = instant("2026-10-02T09:00:00Z")

        repository.setSleepNeed(Duration.ofMinutes(455))
        repository.setTypicalWake(LocalTime.of(6, 45))
        repository.setStreakThreshold(Duration.ofHours(4))
        repository.setDebtThresholds(Duration.ofMinutes(90), Duration.ofMinutes(330))
        repository.setSource(SourceSelection.All)
        repository.setDemoModeFlag(true, demoStart)
        repository.setClockFormat(ClockFormat.H24)
        repository.setMorningReviewEnabled(false)
        repository.setOnboardingDone(true)
        repository.dismissNeedSuggestion(Duration.ofMinutes(510), dismissedAt)

        assertThat(repository.current()).isEqualTo(
            AppSettings(
                sleep = UserSleepSettings(
                    sleepNeed = Duration.ofMinutes(455),
                    typicalWake = LocalTime.of(6, 45),
                    streakThreshold = Duration.ofHours(4),
                    lowDebtMax = Duration.ofMinutes(90),
                    highDebtMin = Duration.ofMinutes(330),
                    source = SourceSelection.All,
                ),
                demoMode = true,
                demoStartedAt = demoStart,
                clockFormat = ClockFormat.H24,
                morningReviewEnabled = false,
                onboardingDone = true,
                needSuggestionDismissedValue = Duration.ofMinutes(510),
                needSuggestionDismissedAt = dismissedAt,
            ),
        )
    }

    @Test
    fun `values are stored as minutes, minute-of-day and encoded source`() = runTest {
        val dataStore = store()
        val repository = SettingsRepositoryImpl(dataStore)

        repository.setSleepNeed(Duration.ofHours(8).plusMinutes(15))
        repository.setTypicalWake(LocalTime.of(7, 30))
        repository.setSource(SourceSelection.All)
        val raw = dataStore.data.first()

        assertThat(raw[intPreferencesKey("sleepNeedMin")]).isEqualTo(495)
        assertThat(raw[intPreferencesKey("typicalWakeMinOfDay")]).isEqualTo(450)
        assertThat(raw[stringPreferencesKey("selectedSource")]).isEqualTo("*all*")

        repository.setSource(SourceSelection.Single("com.example.tracker"))
        assertThat(dataStore.data.first()[stringPreferencesKey("selectedSource")]).isEqualTo("com.example.tracker")
        assertThat(repository.current().sleep.source).isEqualTo(SourceSelection.Single("com.example.tracker"))
    }

    @Test
    fun `turning demo mode off clears its start time`() = runTest {
        val repository = SettingsRepositoryImpl(store())

        repository.setDemoModeFlag(true, instant("2026-10-01T09:00:00Z"))
        repository.setDemoModeFlag(false, null)

        assertThat(repository.current().demoMode).isFalse()
        assertThat(repository.current().demoStartedAt).isNull()
    }

    @Test
    fun `unknown clock format and out-of-range wake minute fall back safely`() = runTest {
        val dataStore = store()
        dataStore.edit {
            it[stringPreferencesKey("clockFormat")] = "H36"
            it[intPreferencesKey("typicalWakeMinOfDay")] = 1440 + 60
        }

        val settings = SettingsRepositoryImpl(dataStore).current()

        assertThat(settings.clockFormat).isEqualTo(ClockFormat.SYSTEM)
        assertThat(settings.sleep.typicalWake).isEqualTo(LocalTime.of(1, 0))
    }

    @Test
    fun `a store that can't be read yields the defaults instead of crashing`() = runTest {
        val unreadable = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("disk unavailable") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk unavailable")
        }

        assertThat(SettingsRepositoryImpl(unreadable).current()).isEqualTo(AppSettings())
    }

    @Test
    fun `settings flow emits on change`() = runTest {
        val repository = SettingsRepositoryImpl(store())

        assertThat(repository.settings.first().onboardingDone).isFalse()
        repository.setOnboardingDone(true)

        assertThat(repository.settings.first().onboardingDone).isTrue()
    }
}
