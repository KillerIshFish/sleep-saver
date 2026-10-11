package com.sleepsaver.feature.tonight.check

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.ui.platform.AlarmBlocker
import com.sleepsaver.core.ui.platform.AlarmCheck
import com.sleepsaver.core.ui.testing.ScreenshotTest
import com.sleepsaver.feature.tonight.LargeFont
import com.sleepsaver.feature.tonight.TonightSamples
import org.junit.Test

/** Alarm check: everything fine, a few blockers, a tuned restless trigger. */
class AlarmCheckScreenshotTest : ScreenshotTest() {

    private val s = TonightSamples

    private fun checks(vararg failing: AlarmBlocker) = AlarmBlocker.entries.map { AlarmCheck(it, passed = it !in failing) }

    private val ready = AlarmCheckUiState.Ready(lastTest = null, tuning = null, today = s.today, zone = s.zone)
    private val tuned = ready.copy(lastTest = PlanSamples.alarmEvent(RingReason.TEST), tuning = PlanSamples.tuning())

    @Composable
    private fun Check(state: AlarmCheckUiState, checks: List<AlarmCheck> = checks(), scrollTo: Int = 0) {
        AlarmCheckContent(state, checks, AlarmCheckActions(), scrollState = remember { ScrollState(scrollTo) })
    }

    @Test
    fun ready() = captureScreen("alarm_check_ready") { Check(ready) }

    @Test
    fun readyScrolled() = captureScreen("alarm_check_ready_scrolled") { Check(ready, scrollTo = Int.MAX_VALUE) }

    @Test
    fun blocked() = captureScreen("alarm_check_blocked") {
        Check(ready, checks(AlarmBlocker.NOTIFICATIONS_OFF, AlarmBlocker.EXACT_ALARMS_OFF, AlarmBlocker.BATTERY_OPTIMIZED, AlarmBlocker.BLUETOOTH_OUTPUT))
    }

    @Test
    fun tuned() = captureScreen("alarm_check_tuned") { Check(tuned, scrollTo = Int.MAX_VALUE) }

    @Test
    fun largeFont() = captureScreen("alarm_check_large_font") { LargeFont { Check(ready) } }
}
