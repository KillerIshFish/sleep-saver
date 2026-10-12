package com.sleepsaver.feature.tonight.day

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.TimingSource
import com.sleepsaver.core.domain.model.WakeTimeMode
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.AlarmTone
import com.sleepsaver.core.domain.schedule.DayPart
import com.sleepsaver.core.domain.schedule.SnoozeStyle
import com.sleepsaver.core.domain.schedule.WakeStyle
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.feature.tonight.TonightFakes
import com.sleepsaver.feature.tonight.TonightSamples
import com.sleepsaver.feature.tonight.TonightSamples.writer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
class DayPlanViewModelTest {

    private val today = TonightSamples.today

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        fakes: TonightFakes,
        day: DayOfWeek = DayOfWeek.TUESDAY,
        part: DayPart = DayPart.MORNING,
    ): DayPlanViewModel {
        val viewModel = DayPlanViewModel(
            savedStateHandle = SavedStateHandle(mapOf("day" to day.value, "part" to part.name)),
            schedule = fakes.schedule,
            nightSteps = fakes.nightSteps,
            patterns = fakes.patterns,
            nightController = fakes.nightController,
            time = fakes.time,
            writer = writer(),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel
    }

    private fun DayPlanViewModel.ready(): DayPlanUiState.Ready = uiState.value as DayPlanUiState.Ready

    @Test
    fun `today's weekday previews this morning until its alarm has rung`() = runTest {
        val early = TonightFakes(now = TonightSamples.at(5, 0))
        assertThat(viewModel(early).ready().morningDate).isEqualTo(today)

        // Rung and stopped at 6:20: the alarm leaves the upcoming list, and the preview moves to next week.
        early.schedule.upcomingState.value = early.schedule.upcomingState.value.filterNot { it.occurrence.date == today }
        early.time.advance(Duration.ofMinutes(80))
        assertThat(viewModel(early).ready().morningDate).isEqualTo(today.plusDays(7))
    }

    @Test
    fun `without an alarm today the morning is ahead until the wake time`() = runTest {
        val dayOff = PlanSamples.oneOffs(dayOff = today)
        assertThat(viewModel(TonightFakes(now = TonightSamples.at(6, 0), oneOffs = dayOff)).ready().morningDate).isEqualTo(today)
        assertThat(viewModel(TonightFakes(now = TonightSamples.at(9, 0), oneOffs = dayOff)).ready().morningDate).isEqualTo(today.plusDays(7))
    }

    @Test
    fun `the day from the route, with its dates and previews`() = runTest {
        val state = viewModel(TonightFakes(), part = DayPart.EVENING).ready()

        assertThat(state.day).isEqualTo(DayOfWeek.TUESDAY)
        assertThat(state.initialPart).isEqualTo(DayPart.EVENING)
        // Today is Tuesday: the next Tuesday morning is a week away; the evening is tonight.
        assertThat(state.morningDate).isEqualTo(today.plusDays(7))
        assertThat(state.eveningDate).isEqualTo(today)
        assertThat(state.alarmDays).containsExactly(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        )
        assertThat(state.ruleBedtime).isEqualTo(LocalTime.of(23, 0))
        assertThat(state.morningPreview?.wakeDate).isEqualTo(today.plusDays(7))
        assertThat(state.eveningPreview?.wakeDate).isEqualTo(today.plusDays(1))
        assertThat(state.stepsCount).isEqualTo(3)
        assertThat(state.usualWake).isEqualTo(LocalTime.of(6, 45))
    }

    @Test
    fun `the usual wake says where it comes from`() = runTest {
        val learned = viewModel(TonightFakes()).ready()
        assertThat(learned.usualSource).isEqualTo(TimingSource.LEARNED)

        val fakes = TonightFakes()
        val inputs = checkNotNull(fakes.patterns.debtPreviewInputsState.value)
        fakes.patterns.debtPreviewInputsState.value = inputs.copy(settings = inputs.settings.copy(wakeTimeMode = WakeTimeMode.FIXED))
        assertThat(viewModel(fakes).ready().usualSource).isEqualTo(TimingSource.FIXED)

        val loading = TonightFakes()
        loading.patterns.debtPreviewInputsState.value = null
        val state = viewModel(loading).ready()
        assertThat(state.usualSource).isNull()
        assertThat(state.morningPreview).isNull()
    }

    @Test
    fun `the day tabs switch days`() = runTest {
        val viewModel = viewModel(TonightFakes())

        viewModel.onSelectDay(DayOfWeek.SATURDAY)

        assertThat(viewModel.ready().day).isEqualTo(DayOfWeek.SATURDAY)
        assertThat(viewModel.ready().alarmOn).isFalse()
        assertThat(viewModel.ready().shownWake).isEqualTo(LocalTime.of(8, 10))
    }

    @Test
    fun `alarm settings are saved one change at a time`() = runTest {
        val fakes = TonightFakes()
        val viewModel = viewModel(fakes)

        viewModel.onAlarmMode(AlarmMode.FIXED)
        viewModel.onWindow(Duration.ofMinutes(45))
        viewModel.onStyle(WakeStyle.FIRM)
        viewModel.onTone(AlarmTone.WARM_RISE)
        viewModel.onSnoozeStyle(SnoozeStyle.FIXED)
        viewModel.onFixedLength(Duration.ofMinutes(5))
        viewModel.onFixedCount(2)
        viewModel.onOverrun(Duration.ofMinutes(15))

        val alarm = fakes.schedule.weekState.value.day(DayOfWeek.TUESDAY).morning.alarm
        assertThat(alarm.mode).isEqualTo(AlarmMode.FIXED)
        assertThat(alarm.window).isEqualTo(Duration.ofMinutes(45))
        assertThat(alarm.style).isEqualTo(WakeStyle.FIRM)
        assertThat(alarm.tone).isEqualTo(AlarmTone.WARM_RISE)
        assertThat(alarm.snooze.style).isEqualTo(SnoozeStyle.FIXED)
        assertThat(alarm.snooze.fixedLength).isEqualTo(Duration.ofMinutes(5))
        assertThat(alarm.snooze.fixedCount).isEqualTo(2)
        assertThat(alarm.snooze.overrun).isEqualTo(Duration.ofMinutes(15))
        assertThat(fakes.schedule.updatedDays).hasSize(8)
        assertThat(fakes.schedule.updatedDays.toSet()).containsExactly(DayOfWeek.TUESDAY)
    }

    @Test
    fun `the wake scrubber saves once it settles`() = runTest {
        val fakes = TonightFakes()
        val viewModel = viewModel(fakes)

        viewModel.onWakeTime(LocalTime.of(6, 30))
        viewModel.onWakeTime(LocalTime.of(6, 20))

        assertThat(viewModel.ready().plan.morning.wakeTime).isEqualTo(LocalTime.of(6, 20))
        assertThat(fakes.schedule.updatedDays).isEmpty()

        advanceTimeBy(DayPlanViewModel.SCRUB_SETTLE + 1)
        runCurrent()

        assertThat(fakes.schedule.updatedDays).containsExactly(DayOfWeek.TUESDAY)
        assertThat(fakes.schedule.weekState.value.day(DayOfWeek.TUESDAY).morning.wakeTime).isEqualTo(LocalTime.of(6, 20))
        assertThat(viewModel.ready().plan.morning.wakeTime).isEqualTo(LocalTime.of(6, 20))
    }

    @Test
    fun `a pending scrub is saved before another day opens`() = runTest {
        val fakes = TonightFakes()
        val viewModel = viewModel(fakes)

        viewModel.onBedtimeSet()
        viewModel.onBedtime(LocalTime.of(22, 40))
        viewModel.onSelectDay(DayOfWeek.WEDNESDAY)

        assertThat(fakes.schedule.weekState.value.day(DayOfWeek.TUESDAY).evening.bedtime).isEqualTo(LocalTime.of(22, 40))
        assertThat(viewModel.ready().day).isEqualTo(DayOfWeek.WEDNESDAY)
        assertThat(viewModel.ready().plan.evening.bedtime).isNull()
    }

    @Test
    fun `set a time starts from the usual wake, usual clears it`() = runTest {
        val fakes = TonightFakes()
        val viewModel = viewModel(fakes)
        viewModel.onSelectDay(DayOfWeek.SATURDAY)

        viewModel.onWakeSet()
        assertThat(fakes.schedule.weekState.value.day(DayOfWeek.SATURDAY).morning.wakeTime).isEqualTo(LocalTime.of(8, 10))

        viewModel.onWakeUsual()
        assertThat(fakes.schedule.weekState.value.day(DayOfWeek.SATURDAY).morning.wakeTime).isNull()
    }

    @Test
    fun `usual wake time right after a scrub wins over the scrubbed time`() = runTest {
        val fakes = TonightFakes()
        val viewModel = viewModel(fakes)
        viewModel.onSelectDay(DayOfWeek.SATURDAY)
        viewModel.onWakeSet()

        viewModel.onWakeTime(LocalTime.of(7, 10))
        viewModel.onWakeUsual()

        assertThat(viewModel.ready().plan.morning.wakeTime).isNull()
        advanceTimeBy(DayPlanViewModel.SCRUB_SETTLE + 1)
        runCurrent()
        assertThat(fakes.schedule.weekState.value.day(DayOfWeek.SATURDAY).morning.wakeTime).isNull()

        // Nor does leaving bring the scrubbed time back.
        viewModel.onSelectDay(DayOfWeek.SUNDAY)
        assertThat(fakes.schedule.weekState.value.day(DayOfWeek.SATURDAY).morning.wakeTime).isNull()
    }

    @Test
    fun `the Settings rule right after a scrub wins over the scrubbed bedtime`() = runTest {
        val fakes = TonightFakes()
        val viewModel = viewModel(fakes, part = DayPart.EVENING)
        viewModel.onBedtimeSet()

        viewModel.onBedtime(LocalTime.of(22, 40))
        viewModel.onBedtimeRule()

        assertThat(viewModel.ready().plan.evening.bedtime).isNull()
        viewModel.onSelectDay(DayOfWeek.WEDNESDAY)
        advanceTimeBy(DayPlanViewModel.SCRUB_SETTLE + 1)
        runCurrent()
        assertThat(fakes.schedule.weekState.value.day(DayOfWeek.TUESDAY).evening.bedtime).isNull()
    }

    @Test
    fun `the volume shows while dragging and saves when let go`() = runTest {
        val fakes = TonightFakes()
        val viewModel = viewModel(fakes)

        viewModel.onVolume(0.5f)
        assertThat(viewModel.ready().plan.morning.alarm.volume).isEqualTo(0.5f)
        assertThat(fakes.schedule.updatedDays).isEmpty()

        viewModel.onVolumeDone()
        assertThat(fakes.schedule.weekState.value.day(DayOfWeek.TUESDAY).morning.alarm.volume).isEqualTo(0.5f)
    }

    @Test
    fun `a sound preview plays in the day's style and volume`() = runTest {
        val fakes = TonightFakes()
        val viewModel = viewModel(fakes)

        viewModel.onPreviewTone(AlarmTone.BRIGHT_BELLS)
        viewModel.stopPreview()

        assertThat(fakes.nightController.nightControllerCalls)
            .containsExactly("previewTone(BRIGHT_BELLS, STANDARD, 0.8)", "stopPreview").inOrder()
    }

    @Test
    fun `evening settings`() = runTest {
        val fakes = TonightFakes()
        val viewModel = viewModel(fakes)

        viewModel.onNightSteps(false)
        viewModel.onStepsStart(120)

        val evening = fakes.schedule.weekState.value.day(DayOfWeek.TUESDAY).evening
        assertThat(evening.nightStepsOn).isFalse()
        // 2 h before bedtime = a 90-min wind-down + the 30-min melatonin half-width.
        assertThat(evening.windDownLength).isEqualTo(Duration.ofMinutes(90))
    }

    @Test
    fun `copy to other days, never to itself`() = runTest {
        val fakes = TonightFakes()
        val viewModel = viewModel(fakes)

        viewModel.onCopy(setOf(DayOfWeek.TUESDAY), setOf(DayPart.MORNING))
        viewModel.onCopy(setOf(DayOfWeek.SATURDAY, DayOfWeek.TUESDAY), setOf(DayPart.MORNING, DayPart.EVENING))

        assertThat(fakes.schedule.copies).containsExactly(
            Triple(DayOfWeek.TUESDAY, setOf(DayOfWeek.SATURDAY), setOf(DayPart.MORNING, DayPart.EVENING)),
        )
        assertThat(viewModel.ready().alarmDays).contains(DayOfWeek.SATURDAY)
    }
}
