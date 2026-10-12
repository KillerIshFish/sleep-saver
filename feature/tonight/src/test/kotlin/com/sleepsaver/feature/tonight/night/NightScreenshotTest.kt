package com.sleepsaver.feature.tonight.night

import androidx.compose.runtime.Composable
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.ui.components.HushControlsState
import com.sleepsaver.core.ui.testing.ScreenshotTest
import com.sleepsaver.feature.tonight.LargeFont
import org.junit.Test
import java.time.LocalTime

/** The Night screen (no clock): the alarm line, the movement log, Hush, "Can't sleep?" and "I'm up". */
class NightScreenshotTest : ScreenshotTest() {

    private val config = SleepConfig.DEFAULT
    private val alarm = NightAlarm.NeverLater(LocalTime.of(6, 45))
    private val hush = HushControlsState(
        color = HushColor.PINK,
        timer = config.hush.defaultTimer,
        volume = config.hush.defaultVolume,
        playing = false,
        untilWindowAvailable = true,
        mediaVolumeHigh = false,
    )

    /** As NightScreen lays it out. */
    @Composable
    private fun Night(model: NightModel, hush: HushControlsState = this.hush) {
        NightScreenFrame { NightContent(night = model, hush = hush, actions = NightActions()) }
    }

    @Test
    fun night() = captureScreen("night_screen") { Night(NightModel(alarm, log = null)) }

    @Test
    fun logStopped() = captureScreen("night_screen_log_stopped") { Night(NightModel(alarm, log = MovementLog.STOPPED)) }

    @Test
    fun logging() = captureScreen("night_screen_logging") { Night(NightModel(NightAlarm.At(LocalTime.of(6, 30)), log = MovementLog.LOGGING)) }

    @Test
    fun hushPlaying() = captureScreen("night_screen_hush") {
        Night(NightModel(alarm, log = null), hush = hush.copy(color = HushColor.BROWN, playing = true, mediaVolumeHigh = true))
    }

    @Test
    fun noAlarm() = captureScreen("night_screen_no_alarm") {
        Night(NightModel(NightAlarm.None, log = null), hush = hush.copy(untilWindowAvailable = false))
    }

    @Test
    fun largeFont() = captureScreen("night_screen_large_font") { LargeFont { Night(NightModel(alarm, log = MovementLog.LOGGING)) } }

    // The status bar and a three-button navigation bar (grey bands): nothing readable or tappable under them.

    @Test
    fun systemBars() = captureClearOfSystemBars("bars_night_screen") { Night(NightModel(alarm, log = MovementLog.STOPPED)) }

    @Test
    fun systemBarsLargeFont() = captureClearOfSystemBars("bars_night_screen_large_font") {
        LargeFont { Night(NightModel(alarm, log = MovementLog.LOGGING)) }
    }
}
