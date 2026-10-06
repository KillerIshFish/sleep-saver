package com.sleepsaver.feature.sleep

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.domain.model.Confidence
import com.sleepsaver.core.domain.model.SleepNeedEstimate
import com.sleepsaver.core.domain.model.SleepNeedEstimateResult
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.core.ui.format.LocalUse24HourClock
import com.sleepsaver.core.ui.testing.ScreenshotTest
import com.sleepsaver.core.ui.testing.captureComponent
import com.sleepsaver.core.ui.testing.captureScreen
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

class SleepScreenshotTest : ScreenshotTest() {

    private val today = SampleData.today

    private fun at(hour: Int, minute: Int): Instant = ZonedDateTime.of(today, LocalTime.of(hour, minute), SampleData.zone).toInstant()

    /** Renders the screen scrolled to the bottom (the phone is 914 dp tall; the home screen is longer). */
    @Composable
    private fun Scrolled(state: SleepUiState) =
        SleepContent(state, SleepActions(), scrollState = rememberScrollState(Int.MAX_VALUE))

    @Composable
    private fun LargeFont(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f), content = content)
    }

    // ---------------------------------------------------------------- Ready

    @Test
    fun normalDay() = captureScreen("sleep_home_normal") { SleepContent(ready(), SleepActions()) }

    @Test
    fun normalDayScrolled() = captureScreen("sleep_home_normal_scrolled") { Scrolled(ready()) }

    @Test
    fun waitingForLastNight() = captureScreen("sleep_home_waiting") {
        SleepContent(ready(SampleData.insights(nights = SampleData.nights(includeLastNight = false))), SleepActions())
    }

    @Test
    fun lastNightMissing() = captureScreen("sleep_home_missing") {
        SleepContent(
            ready(SampleData.insights(now = at(15, 10), nights = SampleData.nights(includeLastNight = false))),
            SleepActions(),
        )
    }

    @Test
    fun reviewReady() = captureScreen("sleep_home_review_ready") {
        SleepContent(ready(pendingReview = today), SleepActions())
    }

    /** A 10 h need the nights don't meet: high debt (coral gauge), energy potential at its 50 % floor. */
    private fun highDebt(): SleepUiState.Ready {
        val sleep = UserSleepSettings(sleepNeed = Duration.ofHours(10))
        return ready(SampleData.insights(settings = sleep), settings = AppSettings(sleep = sleep, onboardingDone = true))
    }

    @Test
    fun highDebtDay() = captureScreen("sleep_home_high_debt") { SleepContent(highDebt(), SleepActions()) }

    /** An 8 h 30 m need: moderate debt (amber gauge). */
    @Test
    fun moderateDebtDay() = captureScreen("sleep_home_moderate_debt") {
        val sleep = UserSleepSettings(sleepNeed = Duration.ofMinutes(8 * 60 + 30))
        SleepContent(
            ready(SampleData.insights(settings = sleep), settings = AppSettings(sleep = sleep, onboardingDone = true)),
            SleepActions(),
        )
    }

    /** Two nights without data in the window, and a need estimate worth offering. */
    private fun suggestionAndMissingNights(): SleepUiState.Ready {
        val nights = SampleData.nights().filterNot { it.wakeDate == today.minusDays(3) || it.wakeDate == today.minusDays(9) }
        val insights = SampleData.insights(nights = nights).copy(
            needEstimate = SleepNeedEstimateResult.Available(
                SleepNeedEstimate(
                    need = Duration.ofMinutes(8 * 60 + 35),
                    freeNightsUsed = 9,
                    interquartileRange = Duration.ofMinutes(55),
                    confidence = Confidence.MEDIUM,
                    typicalWeekdayWake = LocalTime.of(6, 50),
                ),
            ),
        )
        return ready(insights)
    }

    @Test
    fun suggestionCards() = captureScreen("sleep_home_suggestion") {
        SleepContent(suggestionAndMissingNights(), SleepActions(), scrollState = rememberScrollState(560))
    }

    /** A new watch: only the last 5 nights exist, so 9 nights in the window are missing. */
    @Test
    fun manyMissingNights() = captureScreen("sleep_home_many_missing") {
        val nights = SampleData.nights().filter { it.wakeDate > today.minusDays(5) }
        SleepContent(ready(SampleData.insights(nights = nights)), SleepActions(), scrollState = rememberScrollState(420))
    }

    @Test
    fun lateEvening() = captureScreen("sleep_home_tonight_scrolled") {
        Scrolled(ready(SampleData.insights(now = at(23, 55))))
    }

    @Test
    fun twentyFourHourClock() = captureScreen("sleep_home_24h_scrolled") {
        CompositionLocalProvider(LocalUse24HourClock provides true) { Scrolled(ready()) }
    }

    @Test
    fun largeFont() = captureScreen("sleep_home_large_font") {
        LargeFont { SleepContent(ready(), SleepActions()) }
    }

    @Test
    fun largeFontScrolled() = captureScreen("sleep_home_large_font_scrolled") {
        LargeFont { Scrolled(ready()) }
    }

    // ---------------------------------------------------------------- Loading / empty

    // Loading and "syncing" show the infinitely animated LoadingOrb: see SleepAnimatedScreenshotTest.

    @Test
    fun noNightsHealthConnectMissing() = captureScreen("sleep_empty_install") {
        SleepContent(SleepUiState.Empty(today, EmptyReason.INSTALL_HEALTH_CONNECT), SleepActions())
    }

    @Test
    fun noNightsPermissionDenied() = captureScreen("sleep_empty_permission") {
        SleepContent(SleepUiState.Empty(today, EmptyReason.PERMISSION_DENIED), SleepActions())
    }

    @Test
    fun noNightsNotSupported() = captureScreen("sleep_empty_unsupported") {
        SleepContent(SleepUiState.Empty(today, EmptyReason.NOT_SUPPORTED), SleepActions())
    }

    @Test
    fun noNightsYet() = captureScreen("sleep_empty_no_data") {
        SleepContent(SleepUiState.Empty(today, EmptyReason.NO_DATA), SleepActions())
    }

    @Test
    fun noNightsLargeFont() = captureScreen("sleep_empty_install_large_font") {
        LargeFont { SleepContent(SleepUiState.Empty(today, EmptyReason.INSTALL_HEALTH_CONNECT), SleepActions()) }
    }

    // ---------------------------------------------------------------- Data-source sheet

    @Composable
    private fun Sheet(state: SleepUiState.Ready) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
            SourceSheetContent(state, onSyncNow = {}, onChangeInSettings = {}, modifier = Modifier.fillMaxWidth().padding(top = 24.dp))
        }
    }

    @Test
    fun sourceSheet() = captureComponent("sleep_source_sheet") { Sheet(ready()) }

    @Test
    fun sourceSheetError() = captureComponent("sleep_source_sheet_error") {
        Sheet(ready(sync = SyncState(lastSuccessAt = at(7, 5), lastError = SyncError.PERMISSION_DENIED)))
    }

    @Test
    fun sourceSheetDemo() = captureComponent("sleep_source_sheet_demo") { Sheet(ready(source = SourceLabel.Demo)) }
}
