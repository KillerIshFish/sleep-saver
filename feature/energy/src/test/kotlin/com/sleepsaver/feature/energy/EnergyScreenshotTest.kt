package com.sleepsaver.feature.energy

import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.core.ui.format.LocalUse24HourClock
import com.sleepsaver.core.ui.testing.ScreenshotTest
import com.sleepsaver.core.ui.testing.captureComponent
import com.sleepsaver.core.ui.testing.captureScreen
import com.sleepsaver.feature.energy.EnergyTestData.at
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class EnergyScreenshotTest : ScreenshotTest() {

    @Composable
    private fun Screen(state: EnergyUiState, focusTime: Instant? = null, sheet: SheetValue = SheetValue.PartiallyExpanded) =
        EnergyContent(
            state = state,
            onEditNight = {},
            onAddAwake = { _, _, _ -> },
            onAddHabit = {},
            onOpenHabit = {},
            onToggleReminder = { _, _ -> },
            onOpenSettings = {},
            focusTime = focusTime,
            initialSheetValue = sheet,
        )

    @Test
    fun midMorning() = captureScreen("energy_mid_morning") { Screen(EnergyTestData.ready()) }

    @Test
    fun midMorning24h() = captureScreen("energy_mid_morning_24h") {
        CompositionLocalProvider(LocalUse24HourClock provides true) { Screen(EnergyTestData.ready()) }
    }

    @Test
    fun eveningWindDown() = captureScreen("energy_evening_wind_down") { Screen(EnergyTestData.ready(now = at(21, 10))) }

    @Test
    fun waitingForLastNight() = captureScreen("energy_waiting_last_night") { Screen(EnergyTestData.waiting()) }

    @Test
    fun waitingForLastNightPlaceholder() = captureScreen("energy_waiting_placeholder") {
        val state = EnergyTestData.waiting()
        Screen(state, focusTime = state.schedule.typicalWake.toInstant() - Duration.ofHours(3))
    }

    @Test
    fun missingLastNight() = captureScreen("energy_missing_last_night") { Screen(EnergyTestData.waiting(now = at(15, 0))) }

    @Test
    fun nightOwl() = captureScreen("energy_night_owl") { Screen(EnergyTestData.nightOwl()) }

    @Test
    fun nightOwlLateEvening() = captureScreen("energy_night_owl_midnight") {
        val state = EnergyTestData.nightOwl()
        Screen(state, focusTime = at(state.today.plusDays(1), 0, 30))
    }

    @Test
    fun nightOwlAfterMidnight() = captureScreen("energy_night_owl_after_midnight") {
        Screen(EnergyTestData.nightOwlAfterMidnight())
    }

    @Test
    fun longNeedOverlappingPhases() = captureScreen("energy_long_need_evening") {
        Screen(EnergyTestData.ready(now = at(17, 30), settings = EnergyTestData.longNeedSettings))
    }

    @Test
    fun fallBackDst() = captureScreen("energy_dst_fall_back_night") {
        val wakeDate = LocalDate.of(2026, 11, 1)
        val nights = EnergyTestData.patternNights(wakeDate, java.time.LocalTime.of(23, 0), java.time.LocalTime.of(7, 0), count = 14)
        val state = EnergyTestData.ready(now = at(wakeDate, 9, 30), nights = nights)
        Screen(state, focusTime = at(wakeDate, 3, 0))
    }

    // The whole timeline is ~2,000 dp tall: capture it top to bottom in slices.

    @Test
    fun sliceNight() = captureScreen("energy_slice_1_night") {
        val state = EnergyTestData.ready()
        Screen(state, focusTime = state.lastNight!!.fellAsleep!! + Duration.ofHours(2))
    }

    @Test
    fun sliceMorning() = captureScreen("energy_slice_2_morning") {
        val state = EnergyTestData.ready()
        Screen(state, focusTime = state.lastNight!!.wokeUp!! + Duration.ofHours(3))
    }

    @Test
    fun sliceAfternoon() = captureScreen("energy_slice_3_afternoon") { Screen(EnergyTestData.ready(), focusTime = at(15, 30)) }

    @Test
    fun sliceEvening() = captureScreen("energy_slice_4_evening") { Screen(EnergyTestData.ready(), focusTime = at(21, 30)) }

    @Test
    fun habitsSheetExpanded() = captureScreen("energy_habits_sheet") { Screen(EnergyTestData.ready(), sheet = SheetValue.Expanded) }

    @Test
    fun addAwakeDialog() = captureComponent("energy_add_awake_dialog") {
        val night = SampleData.nights().last()
        val start = night.fellAsleep!! + Duration.ofMinutes(155)
        AddAwakeDialogContent(
            draft = AwakeDraft(night.wakeDate, night.fellAsleep!!, night.wokeUp!!, start.minusSeconds(start.epochSecond % 300)),
            zone = SampleData.zone,
            onDismiss = {},
            onConfirm = { _, _ -> },
        )
    }

    @Test
    fun empty() = captureScreen("energy_empty") { Screen(EnergyUiState.NoNights()) }

    @Test
    fun emptyHealthConnectMissing() = captureScreen("energy_empty_hc_missing") {
        Screen(EnergyUiState.NoNights(EmptyReason.HEALTH_CONNECT_MISSING))
    }

    @Test
    fun emptyPermissionDenied() = captureScreen("energy_empty_permission") {
        Screen(EnergyUiState.NoNights(EmptyReason.PERMISSION_DENIED))
    }

    // Loading and importing show the LoadingOrb: see EnergyAnimatedScreenshotTest.

    @Test
    fun largeFont() = captureScreen("energy_large_font") {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.6f)) {
            Screen(EnergyTestData.ready(now = at(13, 40)))
        }
    }

    @Test
    fun largeFontEvening() = captureScreen("energy_large_font_evening") {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.6f)) {
            Screen(EnergyTestData.ready(now = at(21, 10)))
        }
    }

    /** At night the now pill reads "Now", with no time. */
    @Test
    fun nightMode() = captureScreen("energy_night_mode") { Screen(EnergyTestData.nightMode()) }

    @Test
    fun nightModeLargeFont() = captureScreen("energy_night_mode_large_font") {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
            Screen(EnergyTestData.nightMode())
        }
    }

    @Test
    fun eveningWindDown24h() = captureScreen("energy_evening_24h") {
        CompositionLocalProvider(LocalUse24HourClock provides true) { Screen(EnergyTestData.ready(now = at(21, 10))) }
    }
}
