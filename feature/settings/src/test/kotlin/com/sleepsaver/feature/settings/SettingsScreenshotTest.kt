package com.sleepsaver.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.sleepsaver.core.domain.model.BedtimeMode
import com.sleepsaver.core.domain.model.LearnedSleepTiming
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.model.WakeTimeMode
import com.sleepsaver.core.ui.format.LocalUse24HourClock
import com.sleepsaver.core.ui.platform.AlarmBlocker
import com.sleepsaver.core.ui.testing.ScreenshotTest
import com.sleepsaver.core.ui.testing.captureScreen
import com.sleepsaver.core.ui.theme.Spacing
import org.junit.Test
import java.time.Duration
import java.time.LocalTime

/** The settings screen is taller than a phone, so it's captured in slices, plus every dialog. */
class SettingsScreenshotTest : ScreenshotTest() {

    private val noActions = object : SettingsActions {}

    @Composable
    private fun Screen(state: SettingsUiState, scrollTo: SettingsSection? = null, alarms: AlarmsStatus = AlarmsStatus()) {
        SettingsContent(
            state = state,
            actions = noActions,
            listState = rememberLazyListState(initialFirstVisibleItemIndex = scrollTo?.listIndex() ?: 0),
            alarms = alarms,
        )
    }

    /** A dialog card over the dimmed screen, the way the dialog window shows it. */
    @Composable
    private fun WithDialog(state: SettingsUiState = SettingsSamples.ready, card: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            Screen(state)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal)
                    .align(Alignment.Center),
                contentAlignment = Alignment.Center,
            ) { card() }
        }
    }

    // ---------------------------------------------------------------- Slices

    @Test
    fun top() = captureScreen("settings_top") { Screen(SettingsSamples.ready) }

    /** The Sleep group with the Week plan row and the usual wake time and bedtime under it. */
    @Test
    fun weekPlanRow() = captureScreen("settings_week_plan_row") { Screen(SettingsSamples.ready, SettingsSection.SLEEP) }

    @Test
    fun weekPlanRowVaried() = captureScreen("settings_week_plan_row_varied") {
        Screen(SettingsSamples.busy, SettingsSection.SLEEP)
    }

    @Test
    fun alarms() = captureScreen("settings_alarms") { Screen(SettingsSamples.ready, SettingsSection.ALARMS) }

    /** Two things to fix, a test ring pending, and the notice left by a test ring the "Alarm" category stopped. */
    @Test
    fun alarmsBlocked() = captureScreen("settings_alarms_blocked") {
        Screen(
            SettingsSamples.testRingSet,
            SettingsSection.ALARMS,
            alarms = AlarmsStatus(issues = 2, testRingBlocker = AlarmBlocker.RING_CHANNEL_OFF),
        )
    }

    @Test
    fun alarms24h() = captureScreen("settings_alarms_24h") {
        CompositionLocalProvider(LocalUse24HourClock provides true) {
            Screen(SettingsSamples.testRingSet, SettingsSection.SLEEP)
        }
    }

    @Test
    fun alarmsLargeFont() = captureScreen("settings_alarms_large_font") {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
            Screen(
                SettingsSamples.ready,
                SettingsSection.ALARMS,
                alarms = AlarmsStatus(issues = 1, testRingBlocker = AlarmBlocker.NOTIFICATIONS_OFF),
            )
        }
    }

    @Test
    fun data() = captureScreen("settings_data") { Screen(SettingsSamples.ready, SettingsSection.DATA) }

    @Test
    fun notifications() = captureScreen("settings_notifications") {
        Screen(SettingsSamples.ready, SettingsSection.NOTIFICATIONS)
    }

    @Test
    fun bottom() = captureScreen("settings_bottom") { Screen(SettingsSamples.ready, SettingsSection.ABOUT) }

    @Test
    fun freshInstall() = captureScreen("settings_fresh_top") { Screen(SettingsSamples.fresh) }

    @Test
    fun freshInstallData() = captureScreen("settings_fresh_data") { Screen(SettingsSamples.fresh, SettingsSection.DATA) }

    @Test
    fun freshInstallNotifications() = captureScreen("settings_fresh_notifications") {
        Screen(SettingsSamples.fresh, SettingsSection.NOTIFICATIONS)
    }

    @Test
    fun busyData() = captureScreen("settings_busy_data") {
        CompositionLocalProvider(LocalUse24HourClock provides true) { Screen(SettingsSamples.busy, SettingsSection.DATA) }
    }

    @Test
    fun busyNotifications() = captureScreen("settings_busy_notifications") {
        CompositionLocalProvider(LocalUse24HourClock provides true) {
            Screen(SettingsSamples.busy, SettingsSection.NOTIFICATIONS)
        }
    }

    @Test
    fun resetting() = captureScreen("settings_resetting") {
        Screen(SettingsSamples.ready.copy(resetting = true), SettingsSection.ABOUT)
    }

    @Test
    fun loading() = captureScreen("settings_loading") { Screen(SettingsUiState()) }

    @Test
    fun healthConnectMissing() = captureScreen("settings_hc_missing_data") {
        Screen(SettingsSamples.healthConnectMissing, SettingsSection.DATA)
    }

    @Test
    fun largeFont() = captureScreen("settings_large_font") {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.6f)) {
            Screen(SettingsSamples.ready)
        }
    }

    @Test
    fun largeFontData() = captureScreen("settings_large_font_data") {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
            Screen(SettingsSamples.fresh, SettingsSection.DATA)
        }
    }

    // ---------------------------------------------------------------- Dialogs

    @Test
    fun sleepNeedDialog() = captureScreen("settings_dialog_sleep_need") {
        WithDialog {
            SleepNeedDialogCard(
                current = Duration.ofHours(8),
                suggestion = SettingsSamples.ready.needSuggestion,
                onSave = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun wakeTimeDialog() = captureScreen("settings_dialog_wake_time") {
        WithDialog {
            WakeTimeDialogCard(
                mode = WakeTimeMode.FROM_DATA,
                ownTime = LocalTime.of(7, 0),
                learned = SettingsSamples.ready.learned,
                onSave = { _, _ -> },
                onDismiss = {},
            )
        }
    }

    @Test
    fun wakeTimeDialogFixed24h() = captureScreen("settings_dialog_wake_time_fixed_24h") {
        CompositionLocalProvider(LocalUse24HourClock provides true) {
            WithDialog {
                WakeTimeDialogCard(
                    mode = WakeTimeMode.FIXED,
                    ownTime = LocalTime.of(6, 35),
                    learned = LearnedSleepTiming(),
                    onSave = { _, _ -> },
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun bedtimeDialog() = captureScreen("settings_dialog_bedtime") {
        WithDialog {
            BedtimeDialogCard(
                mode = BedtimeMode.FROM_DATA,
                ownTime = LocalTime.of(23, 0),
                learned = SettingsSamples.ready.learned,
                sleepNeed = Duration.ofMinutes(500),
                onSave = { _, _ -> },
                onDismiss = {},
            )
        }
    }

    @Test
    fun bedtimeDialogSleepNeed() = captureScreen("settings_dialog_bedtime_need") {
        WithDialog {
            BedtimeDialogCard(
                mode = BedtimeMode.SLEEP_NEED,
                ownTime = LocalTime.of(23, 0),
                learned = LearnedSleepTiming(),
                sleepNeed = Duration.ofMinutes(500),
                onSave = { _, _ -> },
                onDismiss = {},
            )
        }
    }

    @Test
    fun streakDialog() = captureScreen("settings_dialog_streak") {
        WithDialog { StreakDialogCard(current = Duration.ofHours(5), onSave = {}, onDismiss = {}) }
    }

    @Test
    fun debtLevelsDialog() = captureScreen("settings_dialog_debt_levels") {
        WithDialog {
            DebtLevelsDialogCard(lowMax = Duration.ofHours(2), highMin = Duration.ofHours(5), onSave = { _, _ -> }, onDismiss = {})
        }
    }

    @Test
    fun debtLevelsDialogTight() = captureScreen("settings_dialog_debt_levels_tight") {
        WithDialog {
            DebtLevelsDialogCard(
                lowMax = Duration.ofMinutes(210),
                highMin = Duration.ofMinutes(240),
                onSave = { _, _ -> },
                onDismiss = {},
            )
        }
    }

    @Test
    fun debtLevelsDialogLargeFont() = captureScreen("settings_dialog_debt_levels_large_font") {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
            WithDialog {
                DebtLevelsDialogCard(lowMax = Duration.ofHours(2), highMin = Duration.ofHours(5), onSave = { _, _ -> }, onDismiss = {})
            }
        }
    }

    @Test
    fun sourceDialog() = captureScreen("settings_dialog_source") {
        WithDialog {
            SourceDialogCard(
                options = SettingsSamples.options,
                selected = SourceSelection.All,
                demoMode = false,
                onSave = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun sourceDialogDemo() = captureScreen("settings_dialog_source_demo") {
        WithDialog(SettingsSamples.busy) {
            SourceDialogCard(
                options = SourceOption.defaults(garminHasData = false),
                selected = SourceOption.defaults().first().selection,
                demoMode = true,
                onSave = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun demoOffDialog() = captureScreen("settings_dialog_demo_off") {
        WithDialog(SettingsSamples.busy) { DemoOffDialogCard(onConfirm = {}, onDismiss = {}) }
    }

    @Test
    fun resetDialog() = captureScreen("settings_dialog_reset") {
        WithDialog { ResetDialogCard(resetting = false, onConfirm = {}, onDismiss = {}) }
    }
}
