package com.sleepsaver.feature.progress

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SleepRepository
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepDebt
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.model.VitalSample
import com.sleepsaver.feature.progress.chart.DebtChartGeometry
import com.sleepsaver.feature.progress.chart.QualityGeometry
import com.sleepsaver.feature.progress.chart.SleepTimesGeometry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * The Progress tab: pages through 14-day windows and combines the window's nights, daily debt, vitals and the
 * user's thresholds into one [ProgressUiState].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProgressViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val sleepRepository: SleepRepository,
    private val insightsRepository: InsightsRepository,
    settingsRepository: SettingsRepository,
    timeProvider: TimeProvider,
) : ViewModel() {

    /** 0 = the window ending today, 1 = the two weeks before it, … Survives process death. */
    private val windowsBack: StateFlow<Int> = savedStateHandle.getStateFlow(KEY_WINDOWS_BACK, 0)

    /** Today and the device zone; moves on at midnight and when the zone changes. */
    private val clock: Flow<Pair<LocalDate, ZoneId>> = timeProvider.minuteTicks
        .map { now ->
            val zone = timeProvider.zone()
            LocalDateTime.ofInstant(now, zone).toLocalDate() to zone
        }
        .distinctUntilChanged()

    private val sleepSettings: Flow<UserSleepSettings> =
        settingsRepository.settings.map { it.sleep }.distinctUntilChanged()

    val uiState: StateFlow<ProgressUiState> =
        combine(clock, windowsBack) { (today, zone), back -> Triple(today, zone, back) }
            .flatMapLatest { (today, zone, back) -> windowState(today, zone, back) }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProgressUiState.Loading)

    /** Two weeks back, unless the current window already holds the oldest night. */
    fun showPreviousWindow() {
        val state = uiState.value as? ProgressUiState.Ready ?: return
        if (state.window.canGoBack) savedStateHandle[KEY_WINDOWS_BACK] = windowsBack.value + 1
    }

    /** Two weeks forward; no-op on the window ending today. */
    fun showNextWindow() {
        if (windowsBack.value > 0) savedStateHandle[KEY_WINDOWS_BACK] = windowsBack.value - 1
    }

    /** Back to the window ending today. */
    fun showLatestWindow() {
        savedStateHandle[KEY_WINDOWS_BACK] = 0
    }

    private fun windowState(today: LocalDate, zone: ZoneId, back: Int): Flow<ProgressUiState> {
        // The window's dates only depend on today and the page, so its queries are built once per page.
        val dates = ProgressWindow.of(today, back, oldestNight = null)
        return combine(
            sleepRepository.nights,
            insightsRepository.debtSeries(dates.from, dates.to),
            sleepRepository.vitals(
                from = dates.from.minusDays(1).atStartOfDay(zone).toInstant(),
                to = dates.to.plusDays(1).atStartOfDay(zone).toInstant(),
            ),
            sleepSettings,
        ) { nights, series, vitals, settings ->
            ProgressStateBuilder.build(today, zone, back, nights, series, vitals, settings)
        }
    }

    private companion object {
        const val KEY_WINDOWS_BACK = "progress.windowsBack"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Builds the screen state from plain data, so it can be tested without coroutines. */
internal object ProgressStateBuilder {
    private val sleepTimes = SleepTimesGeometry()
    private val debtChart = DebtChartGeometry()

    fun build(
        today: LocalDate,
        zone: ZoneId,
        windowsBack: Int,
        nights: List<Night>,
        debtSeries: List<SleepDebt>,
        vitals: List<VitalSample>,
        settings: UserSleepSettings,
    ): ProgressUiState {
        val present = nights.filter { it.isPresent }
        if (present.isEmpty()) return ProgressUiState.NoNights(today)
        val window = ProgressWindow.of(today, windowsBack, oldestNight = present.minOf { it.wakeDate })
        val dates = window.dates
        val byDate = nights.filter { it.wakeDate in window.from..window.to }.associateBy { it.wakeDate }
        return ProgressUiState.Ready(
            today = today,
            window = window,
            sleepTimes = sleepTimes.chart(dates, byDate, zone),
            nightRows = dates.reversed().map { NightRow.of(it, byDate[it]) },
            debt = debtChart.chart(dates, debtSeries, byDate, settings, today),
            quality = QualityGeometry.summary(dates, byDate, vitals, zone),
        )
    }
}
