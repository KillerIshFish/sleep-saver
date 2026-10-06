package com.sleepsaver.feature.progress.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.core.ui.format.LocalUse24HourClock
import com.sleepsaver.core.ui.testing.ScreenshotTest
import com.sleepsaver.core.ui.testing.captureScreen
import com.sleepsaver.feature.progress.NoNightsReason
import com.sleepsaver.feature.progress.ProgressFixtures
import com.sleepsaver.feature.progress.ProgressTab
import com.sleepsaver.feature.progress.ProgressUiState
import org.junit.Test

class ProgressScreenshotTest : ScreenshotTest() {

    /** Items: 0 header, 1 tabs, 2 chart card, 3 list header / summary, 4 list … */
    @Composable
    private fun Progress(state: ProgressUiState, tab: ProgressTab = ProgressTab.SLEEP_TIMES, firstItem: Int = 0) {
        ProgressContent(
            state = state,
            onPreviousWindow = {},
            onNextWindow = {},
            onLatestWindow = {},
            onOpenNight = {},
            onAddMissingNight = {},
            onOpenSettings = {},
            initialTab = tab,
            listState = rememberLazyListState(firstItem),
        )
    }

    private val sample get() = ProgressFixtures.ready()
    private val eventful get() = ProgressFixtures.ready(ProgressFixtures.eventfulNights())

    @Test
    fun sleepTimes() = captureScreen("progress_sleep_times") { Progress(sample) }

    @Test
    fun sleepTimesList() = captureScreen("progress_sleep_times_list") { Progress(sample, firstItem = 3) }

    @Test
    fun sleepTimes24h() = captureScreen("progress_sleep_times_24h") {
        CompositionLocalProvider(LocalUse24HourClock provides true) { Progress(eventful) }
    }

    @Test
    fun sleepDebt() = captureScreen("progress_sleep_debt") { Progress(sample, ProgressTab.SLEEP_DEBT) }

    @Test
    fun sleepDebtList() = captureScreen("progress_sleep_debt_list") { Progress(sample, ProgressTab.SLEEP_DEBT, firstItem = 3) }

    @Test
    fun sleepQuality() = captureScreen("progress_sleep_quality") { Progress(sample, ProgressTab.SLEEP_QUALITY) }

    @Test
    fun sleepQualityTrends() = captureScreen("progress_sleep_quality_trends") {
        Progress(sample, ProgressTab.SLEEP_QUALITY, firstItem = 3)
    }

    @Test
    fun sleepQualityNoVitals() = captureScreen("progress_sleep_quality_no_vitals") {
        Progress(ProgressFixtures.ready(withVitals = false), ProgressTab.SLEEP_QUALITY, firstItem = 3)
    }

    @Test
    fun missingNights() = captureScreen("progress_missing_nights") { Progress(eventful) }

    @Test
    fun missingNightsList() = captureScreen("progress_missing_nights_list") { Progress(eventful, firstItem = 3) }

    @Test
    fun missingNightsDebt() = captureScreen("progress_missing_nights_debt") { Progress(eventful, ProgressTab.SLEEP_DEBT) }

    @Test
    fun missingNightsQuality() = captureScreen("progress_missing_nights_quality") { Progress(eventful, ProgressTab.SLEEP_QUALITY) }

    @Test
    fun emptyWindow() = captureScreen("progress_empty_window") { Progress(emptyWindowState()) }

    @Test
    fun emptyWindowDebt() = captureScreen("progress_empty_window_debt") { Progress(emptyWindowState(), ProgressTab.SLEEP_DEBT) }

    @Test
    fun noNights() = captureScreen("progress_no_nights") { Progress(ProgressUiState.NoNights(SampleData.today)) }

    @Test
    fun loading() = captureScreen("progress_loading") { Progress(ProgressUiState.Loading) }

    @Test
    fun noNightsHealthConnectMissing() = captureScreen("progress_no_nights_health_connect") {
        Progress(ProgressUiState.NoNights(SampleData.today, NoNightsReason.HEALTH_CONNECT_UNAVAILABLE))
    }

    @Test
    fun noNightsPermissionDenied() = captureScreen("progress_no_nights_permission") {
        Progress(ProgressUiState.NoNights(SampleData.today, NoNightsReason.PERMISSION_DENIED))
    }

    @Test
    fun highDebt() = captureScreen("progress_high_debt") { Progress(ProgressFixtures.ready(ProgressFixtures.shortNights()), ProgressTab.SLEEP_DEBT) }

    @Test
    fun largeFontSleepTimes() = captureScreen("progress_large_font_sleep_times") { LargeFont { Progress(eventful) } }

    @Test
    fun largeFontSleepDebt() = captureScreen("progress_large_font_sleep_debt") { LargeFont { Progress(sample, ProgressTab.SLEEP_DEBT) } }

    @Test
    fun largeFontQuality() = captureScreen("progress_large_font_quality") {
        LargeFont { Progress(sample, ProgressTab.SLEEP_QUALITY, firstItem = 2) }
    }

    @Composable
    private fun LargeFont(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f), content = content)
    }

    /** The two weeks before the current window, with no nights in them (an older night exists before). */
    private fun emptyWindowState(): ProgressUiState.Ready {
        val nights = SampleData.nights()
        val recent = nights.filter { it.wakeDate > SampleData.today.minusDays(14) }
        val old = nights.first().copy(wakeDate = SampleData.today.minusDays(60))
        return ProgressFixtures.ready(listOf(old) + recent, windowsBack = 1)
    }
}
