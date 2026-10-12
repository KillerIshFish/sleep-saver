package com.sleepsaver.feature.tonight.week

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.OverrideKind
import com.sleepsaver.core.domain.schedule.PlanPause
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.ui.testing.ScreenshotTest
import com.sleepsaver.feature.tonight.Clock24
import com.sleepsaver.feature.tonight.LargeFont
import com.sleepsaver.feature.tonight.SheetOver
import com.sleepsaver.feature.tonight.TonightSamples
import com.sleepsaver.feature.tonight.oneoff.OneOffSheetContent
import com.sleepsaver.feature.tonight.oneoff.OneOffSheetActions
import com.sleepsaver.feature.tonight.oneoff.OneOffSheetState
import com.sleepsaver.feature.tonight.oneoff.oneOffDates
import org.junit.Test
import java.time.LocalTime

/** The week plan, its one-offs and plan notice, and the one-off sheet. */
class WeekPlanScreenshotTest : ScreenshotTest() {

    private val s = TonightSamples

    @Composable
    private fun WeekPlan(state: WeekPlanUiState, scrollTo: Int = 0) {
        WeekPlanContent(state, WeekPlanActions(), scrollState = remember { ScrollState(scrollTo) })
    }

    private val oneOffs = PlanSamples.oneOffs(
        dayOff = s.today.plusDays(1),
        justOnce = DateOverride(s.today.plusDays(2), OverrideKind.JUST_ONCE, LocalTime.of(5, 30), AlarmMode.FIXED),
        pause = PlanPause(s.today.plusDays(5), s.today.plusDays(6)),
    )

    @Test
    fun weekPlan() = captureScreen("week_plan") { WeekPlan(s.weekPlanState()) }

    @Test
    fun weekPlanScrolled() = captureScreen("week_plan_scrolled") { WeekPlan(s.weekPlanState(), scrollTo = Int.MAX_VALUE) }

    @Test
    fun withOneOffs() = captureScreen("week_plan_with_oneoffs") { WeekPlan(s.weekPlanState(oneOffs = oneOffs), scrollTo = 500) }

    @Test
    fun warning() = captureScreen("week_plan_warning") {
        WeekPlan(s.weekPlanState(planCheck = PlanSamples.planCheck(warn = true)), scrollTo = Int.MAX_VALUE)
    }

    @Test
    fun empty() = captureScreen("week_plan_empty") { WeekPlan(s.weekPlanState(week = s.allOff, planCheck = null, keel = null)) }

    @Test
    fun largeFont() = captureScreen("week_plan_large_font") { LargeFont { WeekPlan(s.weekPlanState()) } }

    @Test
    fun clock24() = captureScreen("week_plan_24h") { Clock24 { WeekPlan(s.weekPlanState(oneOffs = oneOffs)) } }

    /** Tonight's window widened on a short night: the header keeps the row's 30 min and says why it is 40. */
    private val widened get() = s.weekPlanState(next = PlanSamples.plannedAlarm(date = s.today.plusDays(1), debtAware = true))

    @Test
    fun debtAware() = captureScreen("week_plan_debt_aware") { WeekPlan(widened) }

    @Test
    fun debtAwareLargeFont() = captureScreen("week_plan_debt_aware_large_font") { LargeFont { WeekPlan(widened) } }

    @Test
    fun oneOffSheet() = captureScreen("oneoff_sheet") {
        SheetOver(screen = { WeekPlan(s.weekPlanState()) }) {
            OneOffSheetContent(
                state = OneOffSheetState(
                    date = s.today.plusDays(1),
                    // At 2 PM: from tomorrow, the next morning a one-off can change.
                    dates = oneOffDates(s.today.plusDays(1)),
                    today = s.today,
                    alarmOn = true,
                    time = LocalTime.of(6, 45),
                    mode = AlarmMode.SURFACING,
                    pauseDates = (1L..60L).map { s.today.plusDays(it) },
                    pauseUntil = s.today.plusDays(5),
                ),
                actions = OneOffSheetActions(),
            )
        }
    }
}
