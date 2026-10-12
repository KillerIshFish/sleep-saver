package com.sleepsaver.feature.tonight.day

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.DayPart
import com.sleepsaver.core.domain.schedule.SnoozeStyle
import com.sleepsaver.core.ui.platform.AlarmBlocker
import com.sleepsaver.core.ui.testing.ScreenshotTest
import com.sleepsaver.core.ui.testing.rememberScrolledToEnd
import com.sleepsaver.feature.tonight.Clock24
import com.sleepsaver.feature.tonight.LargeFont
import com.sleepsaver.feature.tonight.TonightSamples
import com.sleepsaver.feature.tonight.TonightSamples.withAlarm
import org.junit.Test
import java.time.DayOfWeek

/** A day's plan: the morning (surfacing, fixed, off), the evening, the copy dialog and the arming notices. */
class DayPlanScreenshotTest : ScreenshotTest() {

    private val s = TonightSamples

    @Composable
    private fun DayPlan(
        state: DayPlanUiState,
        scrollTo: Int = 0,
        armBlocker: AlarmBlocker? = null,
        copyOpen: Boolean = false,
        scrollState: ScrollState = remember { ScrollState(scrollTo) },
    ) {
        DayPlanContent(
            state,
            DayPlanActions(),
            scrollState = scrollState,
            armBlocker = armBlocker,
            initialCopyOpen = copyOpen,
        )
    }

    private val fixed = s.week.withAlarm(DayOfWeek.TUESDAY) {
        it.copy(mode = AlarmMode.FIXED, snooze = it.snooze.copy(style = SnoozeStyle.FIXED))
    }

    @Test
    fun morning() = captureScreen("day_plan_morning") { DayPlan(s.dayPlan()) }

    @Test
    fun morningScrolled() = captureScreen("day_plan_morning_scrolled") { DayPlan(s.dayPlan(), scrollTo = 1400) }

    @Test
    fun morningFixed() = captureScreen("day_plan_morning_fixed") { DayPlan(s.dayPlan(week = fixed), scrollTo = 700) }

    @Test
    fun evening() = captureScreen("day_plan_evening") { DayPlan(s.dayPlan(part = DayPart.EVENING)) }

    @Test
    fun off() = captureScreen("day_plan_off") { DayPlan(s.dayPlan(day = DayOfWeek.SATURDAY)) }

    @Test
    fun copyDialog() = captureScreen("day_plan_copy_dialog") { DayPlan(s.dayPlan(), copyOpen = true) }

    @Test
    fun notificationsNeeded() = captureScreen("day_plan_notifications_needed") {
        DayPlan(s.dayPlan(day = DayOfWeek.SATURDAY), armBlocker = AlarmBlocker.NOTIFICATIONS_OFF)
    }

    @Test
    fun ringChannelOff() = captureScreen("day_plan_ring_channel_off") {
        DayPlan(s.dayPlan(day = DayOfWeek.SATURDAY), armBlocker = AlarmBlocker.RING_CHANNEL_OFF)
    }

    @Test
    fun largeFont() = captureScreen("day_plan_large_font") { LargeFont { DayPlan(s.dayPlan()) } }

    @Test
    fun clock24() = captureScreen("day_plan_24h") { Clock24 { DayPlan(s.dayPlan()) } }

    // The status bar and a three-button navigation bar (grey bands): nothing readable or tappable under them.

    @Test
    fun systemBars() = captureClearOfSystemBars("bars_day_plan") { DayPlan(s.dayPlan()) }

    /** The end of the evening: "Copy to…" stays above the navigation bar. */
    @Test
    fun systemBarsEnd() = captureClearOfSystemBars("bars_day_plan_end") { DayPlan(s.dayPlan(), scrollState = rememberScrolledToEnd()) }
}
