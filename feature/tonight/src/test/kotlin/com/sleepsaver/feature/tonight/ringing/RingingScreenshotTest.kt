package com.sleepsaver.feature.tonight.ringing

import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.alarm.SnoozeOffer
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.PlanSamples.NightVariant
import com.sleepsaver.core.ui.testing.ScreenshotTest
import com.sleepsaver.feature.tonight.Clock24
import com.sleepsaver.feature.tonight.LargeFont
import com.sleepsaver.feature.tonight.TonightSamples
import org.junit.Test
import java.time.Duration

/** The ringing screen by ring and reason, and after Stop. */
class RingingScreenshotTest : ScreenshotTest() {

    private val s = TonightSamples
    private val actions = RingingActions()
    private val wakeDate = PlanSamples.today.plusDays(1)

    private val restless get() = s.sounding(NightVariant.RINGING_PLANNED)

    /** The planned ring at the wake time, with a full Tapering snooze left. */
    private val wakeTime get() = s.sounding(NightVariant.RINGING_PLANNED) { ringing ->
        val target = checkNotNull(ringing.target)
        ringing.copy(reason = RingReason.DEADLINE, since = target, earlyBy = Duration.ZERO, snooze = SnoozeOffer(target + Duration.ofMinutes(9), lastOne = false))
    }

    @Test
    fun restless() = captureScreen("ringing_restless") { RingingContent(restless, actions) }

    @Test
    fun wakeTime() = captureScreen("ringing_wake_time") { RingingContent(wakeTime, actions) }

    @Test
    fun lastSnooze() = captureScreen("ringing_last_snooze") {
        RingingContent(s.sounding(NightVariant.RINGING_PLANNED) { it.copy(reason = RingReason.SNOOZE_END, snooze = it.snooze.copy(lastOne = true)) }, actions)
    }

    @Test
    fun noSnooze() = captureScreen("ringing_no_snooze") {
        RingingContent(s.sounding(NightVariant.RINGING_PLANNED) { it.copy(reason = RingReason.BACKUP, snooze = SnoozeOffer(null, false)) }, actions)
    }

    @Test
    fun catnap() = captureScreen("ringing_catnap") { RingingContent(s.sounding(NightVariant.RINGING_CATNAP), actions) }

    @Test
    fun test() = captureScreen("ringing_test") { RingingContent(s.sounding(NightVariant.RINGING_TEST), actions) }

    @Test
    fun afterStopFeel() = captureScreen("after_stop_feel") {
        RingingContent(RingingUiState.AfterStop(RingKind.PLANNED, wakeDate, MorningFeel.CLEAR, unlocked = true), actions)
    }

    @Test
    fun afterStopLocked() = captureScreen("after_stop_locked") {
        RingingContent(RingingUiState.AfterStop(RingKind.PLANNED, wakeDate, feel = null, unlocked = false), actions)
    }

    @Test
    fun afterStopTest() = captureScreen("after_stop_test") {
        RingingContent(RingingUiState.AfterStop(RingKind.TEST, null, null, unlocked = true), actions)
    }

    @Test
    fun clock24() = captureScreen("ringing_24h") { Clock24 { RingingContent(s.sounding(NightVariant.RINGING_PLANNED, use24h = true), actions) } }

    @Test
    fun largeFont() = captureScreen("ringing_large_font") { LargeFont { RingingContent(restless, actions) } }

    @Test
    fun afterStopLargeFont() = captureScreen("after_stop_large_font") {
        LargeFont { RingingContent(RingingUiState.AfterStop(RingKind.PLANNED, wakeDate, MorningFeel.OKAY, unlocked = true), actions) }
    }

    // The status bar and a three-button navigation bar (grey bands): nothing readable or tappable under them.

    @Test
    fun systemBars() = captureClearOfSystemBars("bars_ringing") { RingingContent(wakeTime, actions) }

    /** Morning feel, after Stop. */
    @Test
    fun systemBarsAfterStop() = captureClearOfSystemBars("bars_after_stop_feel") {
        RingingContent(RingingUiState.AfterStop(RingKind.PLANNED, wakeDate, MorningFeel.CLEAR, unlocked = true), actions)
    }

    @Test
    fun systemBarsLargeFont() = captureClearOfSystemBars("bars_ringing_large_font") { LargeFont { RingingContent(restless, actions) } }
}
