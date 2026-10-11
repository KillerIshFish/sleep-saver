package com.sleepsaver.feature.tonight.week

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.OverrideKind
import com.sleepsaver.core.domain.schedule.WeekPreset
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.feature.tonight.TestRingEvent
import com.sleepsaver.feature.tonight.TonightFakes
import com.sleepsaver.feature.tonight.TonightSamples
import com.sleepsaver.feature.tonight.TonightSamples.at
import com.sleepsaver.feature.tonight.TonightSamples.writer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class WeekPlanViewModelTest {

    private val config = SleepConfig.DEFAULT

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(fakes: TonightFakes, handle: SavedStateHandle = SavedStateHandle()): WeekPlanViewModel {
        val viewModel = WeekPlanViewModel(
            savedStateHandle = handle,
            schedule = fakes.schedule,
            nightSteps = fakes.nightSteps,
            patterns = fakes.patterns,
            nightController = fakes.nightController,
            nightClock = fakes.clockSource(),
            time = fakes.time,
            writer = writer(),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel
    }

    private fun WeekPlanViewModel.ready(): WeekPlanUiState.Ready = uiState.value as WeekPlanUiState.Ready

    @Test
    fun `seven rows with their evenings`() = runTest {
        val state = viewModel(TonightFakes()).ready()

        assertThat(state.rows.map { it.day }).containsExactlyElementsIn(DayOfWeek.entries).inOrder()
        val tuesday = state.rows.first { it.day == DayOfWeek.TUESDAY }
        assertThat(tuesday.alarmOn).isTrue()
        // Bedtime 23:00 − 30 min melatonin half-width − 60 min wind-down.
        assertThat(tuesday.stepsFrom).isEqualTo(at(21, 30))
        assertThat(tuesday.steps).isEqualTo(3)
        val saturday = state.rows.first { it.day == DayOfWeek.SATURDAY }
        assertThat(saturday.alarmOn).isFalse()
        assertThat(saturday.usualWake).isEqualTo(LocalTime.of(8, 10))
        assertThat(saturday.stepsFrom).isEqualTo(at(21, 30, date = TonightSamples.today.plusDays(4)))
    }

    @Test
    fun `the subtitle names the next alarm, and at night says so`() = runTest {
        val day = viewModel(TonightFakes()).ready()
        assertThat(day.next?.occurrence?.date).isEqualTo(TonightSamples.today.plusDays(1))
        assertThat(day.night).isFalse()

        assertThat(viewModel(TonightFakes(now = at(23, 30))).ready().night).isTrue()
    }

    @Test
    fun `I'm up tapped mid-minute clears the night subtitle at once`() = runTest {
        val fakes = TonightFakes(now = at(23, 30))
        val viewModel = viewModel(fakes)
        assertThat(viewModel.ready().night).isTrue()

        // The last tick came at 23:30:00; the tap is 45 s later, before the 23:31 tick.
        fakes.time.now = at(23, 30).plusSeconds(45)
        fakes.endedNights.endedNow(fakes.time.now())

        assertThat(viewModel.ready().night).isFalse()
    }

    @Test
    fun `turning an alarm on uses the default mode, off turns it off`() = runTest {
        val fakes = TonightFakes()
        val viewModel = viewModel(fakes)

        viewModel.onAlarmSwitch(DayOfWeek.SATURDAY, true)
        viewModel.onAlarmSwitch(DayOfWeek.MONDAY, false)

        val week = fakes.schedule.weekState.value
        assertThat(week.day(DayOfWeek.SATURDAY).morning.alarm.mode).isEqualTo(config.alarm.defaultMode)
        assertThat(week.day(DayOfWeek.MONDAY).morning.alarm.mode).isEqualTo(AlarmMode.OFF)
        assertThat(fakes.schedule.updatedDays).containsExactly(DayOfWeek.SATURDAY, DayOfWeek.MONDAY).inOrder()
        assertThat(viewModel.ready().rows.first { it.day == DayOfWeek.SATURDAY }.alarmOn).isTrue()
    }

    @Test
    fun `quick setup applies a preset`() = runTest {
        val fakes = TonightFakes()
        val viewModel = viewModel(fakes)
        assertThat(viewModel.ready().presetWake).isEqualTo(LocalTime.of(6, 45))

        viewModel.onApplyPreset(WeekPreset.SAME_EVERY_DAY, LocalTime.of(7, 15))

        assertThat(fakes.schedule.presets).containsExactly(WeekPreset.SAME_EVERY_DAY to LocalTime.of(7, 15))
    }

    @Test
    fun `the plan notice stays dismissed for this editing session`() = runTest {
        val fakes = TonightFakes()
        fakes.patterns.planCheckState.value = PlanSamples.planCheck(warn = true)
        val handle = SavedStateHandle()
        val viewModel = viewModel(fakes, handle)
        assertThat(viewModel.ready().planCheck?.warn).isTrue()
        assertThat(viewModel.ready().noticeDismissed).isFalse()

        viewModel.onDismissNotice()

        assertThat(viewModel.ready().noticeDismissed).isTrue()
        assertThat(handle.get<Boolean>(WeekPlanViewModel.KEY_NOTICE_DISMISSED)).isTrue()
        assertThat(viewModel(fakes, handle).ready().noticeDismissed).isTrue()
    }

    @Test
    fun `the plan check carries the measured keel`() = runTest {
        val state = viewModel(TonightFakes()).ready()
        assertThat(state.planCheck?.plannedKeel).isEqualTo(91)
        assertThat(state.keel).isEqualTo(PlanSamples.keelReady())
    }

    // ---------------------------------------------------------------- Test ring

    @Test
    fun `a test ring says when it rings`() = runTest {
        val fakes = TonightFakes()
        val viewModel = viewModel(fakes)

        viewModel.onTestRing()

        assertThat(viewModel.events.first()).isEqualTo(TestRingEvent.Set(at(14, 0) + config.alarm.testRingDelay, TonightSamples.zone))
        assertThat(fakes.nightController.nightControllerCalls).containsExactly("testRing")
    }

    @Test
    fun `a test ring at night carries no time`() = runTest {
        val viewModel = viewModel(TonightFakes(now = at(23, 30)))

        viewModel.onTestRing()

        assertThat(viewModel.events.first()).isEqualTo(TestRingEvent.Set(null, TonightSamples.zone))
    }

    @Test
    fun `a refused test ring says a ring is already set`() = runTest {
        val fakes = TonightFakes()
        fakes.nightController.testRingAllowed = false
        val viewModel = viewModel(fakes)

        viewModel.onTestRing()

        assertThat(viewModel.events.first()).isEqualTo(TestRingEvent.Refused)
    }

    // ---------------------------------------------------------------- One-offs

    @Test
    fun `one-offs are listed and undone without touching the week`() = runTest {
        val fakes = TonightFakes()
        val viewModel = viewModel(fakes)
        val wednesday = TonightSamples.today.plusDays(1)

        viewModel.onJustOnce(wednesday, LocalTime.of(5, 30), AlarmMode.FIXED)
        assertThat(viewModel.ready().oneOffs.overrides)
            .containsExactly(DateOverride(wednesday, OverrideKind.JUST_ONCE, LocalTime.of(5, 30), AlarmMode.FIXED))

        viewModel.onClearOverride(wednesday)
        viewModel.onPause(TonightSamples.today.plusDays(3))
        assertThat(viewModel.ready().oneOffs.pause?.until).isEqualTo(TonightSamples.today.plusDays(3))
        viewModel.onResumeAlarms()

        assertThat(viewModel.ready().oneOffs.overrides).isEmpty()
        assertThat(viewModel.ready().oneOffs.pause).isNull()
        assertThat(fakes.schedule.updatedDays).isEmpty()
    }

    @Test
    fun `the presets wake time comes from Monday, rounded`() = runTest {
        val fakes = TonightFakes()
        fakes.schedule.usualWakeState.value = DayOfWeek.entries.associateWith { LocalTime.of(7, 3) }

        assertThat(viewModel(fakes).ready().presetWake).isEqualTo(LocalTime.of(7, 5))
        assertThat(Duration.ofMinutes(5)).isEqualTo(config.week.timeRounding)
    }
}
