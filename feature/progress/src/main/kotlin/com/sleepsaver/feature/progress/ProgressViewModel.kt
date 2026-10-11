package com.sleepsaver.feature.progress

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.di.DefaultDispatcher
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.PatternsRepository
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SleepRepository
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.data.repository.SyncRepository
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepDebt
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.model.VitalSample
import com.sleepsaver.core.domain.patterns.KeelResult
import com.sleepsaver.core.domain.patterns.MarkEffect
import com.sleepsaver.core.domain.patterns.SocialJetlag
import com.sleepsaver.feature.progress.chart.DebtChartGeometry
import com.sleepsaver.feature.progress.chart.QualityGeometry
import com.sleepsaver.feature.progress.chart.SleepTimesGeometry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * The Progress tab: pages through 14-day windows and combines the window's nights, daily debt, vitals and the
 * user's thresholds into one [ProgressUiState], with Keel, social jetlag and the mark patterns (as of today) from
 * [patternsRepository]. The charts are laid out on [computeDispatcher], off the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProgressViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val sleepRepository: SleepRepository,
    private val insightsRepository: InsightsRepository,
    settingsRepository: SettingsRepository,
    syncRepository: SyncRepository,
    timeProvider: TimeProvider,
    @DefaultDispatcher computeDispatcher: CoroutineDispatcher,
    patternsRepository: PatternsRepository,
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

    /** Only used to explain an empty tab: why no nights may have come in. */
    private val noNightsReason: Flow<NoNightsReason> =
        syncRepository.state.map { ProgressStateBuilder.noNightsReason(it.lastError) }.distinctUntilChanged()

    private data class Patterns(val keel: KeelResult?, val socialJetlag: SocialJetlag?, val markEffects: List<MarkEffect>)

    /**
     * Keel, social jetlag and mark effects. Each starts empty and stays empty on failure, so the charts never wait.
     * Shared across pages: they are "as of today", so paging must neither recompute them nor blank the cards.
     */
    private val patterns: StateFlow<Patterns> = combine(
        patternsRepository.keel.orDefault(null),
        patternsRepository.socialJetlag.orDefault(null),
        patternsRepository.markEffects.orDefault(emptyList()),
        ::Patterns,
    )
        .flowOn(computeDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Patterns(null, null, emptyList()))

    val uiState: StateFlow<ProgressUiState> =
        combine(clock, windowsBack) { (today, zone), back -> Triple(today, zone, back) }
            .flatMapLatest { (today, zone, back) -> windowState(today, zone, back) }
            .flowOn(computeDispatcher)
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProgressUiState.Loading)

    /**
     * Two weeks back, unless the window on screen already holds the oldest night. Ignored while the screen still
     * shows an older page than the one requested (a quick double tap), so paging can't run past the oldest night.
     */
    fun showPreviousWindow() {
        val state = uiState.value as? ProgressUiState.Ready ?: return
        val back = windowsBack.value
        val requested = ProgressWindow.of(state.today, back, oldestNight = null)
        if (state.window.to == requested.to && state.window.canGoBack) savedStateHandle[KEY_WINDOWS_BACK] = back + 1
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
            combine(sleepSettings, noNightsReason, ::Pair),
            patterns,
        ) { nights, series, vitals, (settings, reason), patterns ->
            ProgressStateBuilder.build(
                today, zone, back, nights, series, vitals, settings, reason,
                keel = patterns.keel,
                socialJetlag = patterns.socialJetlag,
                markEffects = patterns.markEffects,
            )
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
        noNightsReason: NoNightsReason = NoNightsReason.NO_DATA,
        keel: KeelResult? = null,
        socialJetlag: SocialJetlag? = null,
        markEffects: List<MarkEffect> = emptyList(),
    ): ProgressUiState {
        val present = nights.filter { it.isPresent }
        if (present.isEmpty()) return ProgressUiState.NoNights(today, noNightsReason)
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
            keel = keel,
            socialJetlag = socialJetlag,
            marks = MarkPatterns.of(markEffects),
        )
    }

    fun noNightsReason(lastSyncError: SyncError?): NoNightsReason = when (lastSyncError) {
        SyncError.HEALTH_CONNECT_NOT_INSTALLED, SyncError.HEALTH_CONNECT_UPDATE_REQUIRED -> NoNightsReason.HEALTH_CONNECT_UNAVAILABLE
        SyncError.PERMISSION_DENIED -> NoNightsReason.PERMISSION_DENIED
        else -> NoNightsReason.NO_DATA
    }
}

/** Starts with [default] and falls back to it on failure. */
private fun <T> Flow<T>.orDefault(default: T): Flow<T> = onStart { emit(default) }.catch { emit(default) }
