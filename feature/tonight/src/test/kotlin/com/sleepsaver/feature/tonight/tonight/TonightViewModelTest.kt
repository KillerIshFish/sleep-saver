package com.sleepsaver.feature.tonight.tonight

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.OverrideKind
import com.sleepsaver.core.domain.schedule.PlanPause
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.feature.tonight.TonightFakes
import com.sleepsaver.feature.tonight.TonightPhase
import com.sleepsaver.feature.tonight.TonightSamples
import com.sleepsaver.feature.tonight.TonightSamples.at
import com.sleepsaver.feature.tonight.TonightSamples.writer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
import java.io.IOException
import java.time.Duration
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class TonightViewModelTest {

    private val wednesday = TonightSamples.today.plusDays(1)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(fakes: TonightFakes, schedule: ScheduleRepository = fakes.schedule): TonightViewModel {
        val viewModel = TonightViewModel(
            insights = fakes.insights,
            schedule = schedule,
            nightSteps = fakes.nightSteps,
            journal = fakes.journal,
            nightLog = fakes.nightLog,
            patterns = fakes.patterns,
            nightController = fakes.nightController,
            settings = fakes.settings,
            time = fakes.time,
            writer = writer(),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel
    }

    private fun TonightViewModel.ready(): TonightUiState.Ready = uiState.value as TonightUiState.Ready

    @Test
    fun `the evening comes together from the repositories`() = runTest {
        val viewModel = viewModel(TonightFakes(now = at(21, 45)))

        val state = viewModel.ready()
        assertThat(state.phase).isEqualTo(TonightPhase.EVENING)
        assertThat(state.wakeDate).isEqualTo(wednesday)
        assertThat((state.alarmCard as AlarmCardModel.On).time).isEqualTo(LocalTime.of(6, 45))
        assertThat(state.steps?.steps).hasSize(3)
        assertThat(state.debtPreview).isNotNull()
        assertThat(state.adherence?.countedRecent).isEqualTo(5)
    }

    @Test
    fun `tomorrow's alarm that already rang reads as done once its event is in`() = runTest {
        // Wednesday's 6:45 rang, then the clock went back to Tuesday evening: the planner moved on to Thursday.
        val now = at(20, 55)
        val fakes = TonightFakes(now = now)
        val rang = PlanSamples.alarmEvent(RingReason.DEADLINE, wakeDate = wednesday)
        fakes.schedule.upcomingState.value = TonightSamples.alarms(now, exclude = setOf(checkNotNull(rang.occurrenceId)), endedDates = setOf(wednesday))
        val viewModel = viewModel(fakes)

        fakes.nightLog.recordAlarmEvent(rang)

        val next = NextAlarm(wednesday.plusDays(1), LocalTime.of(6, 45))
        assertThat(viewModel.ready().alarmCard).isEqualTo(AlarmCardModel.Done(wednesday, rang = true, next = next))
    }

    @Test
    fun `loading until the insights are read`() = runTest {
        val viewModel = viewModel(TonightFakes(insights = InsightsState.Loading))
        assertThat(viewModel.uiState.value).isEqualTo(TonightUiState.Loading)
    }

    @Test
    fun `a plan that can't be read is unavailable`() = runTest {
        val fakes = TonightFakes()
        val broken = object : ScheduleRepository by fakes.schedule {
            override val weekPlan: Flow<WeekPlan> = flow { throw IOException("disk") }
        }
        assertThat(viewModel(fakes, broken).uiState.value).isEqualTo(TonightUiState.Unavailable)
    }

    @Test
    fun `a mark is saved for tomorrow's wake date`() = runTest {
        val fakes = TonightFakes(now = at(21, 45))
        val viewModel = viewModel(fakes)

        viewModel.onMark(Mark.ALCOHOL, true)

        assertThat(fakes.journal.marksSet).containsExactly(Triple(wednesday, Mark.ALCOHOL, true))
        assertThat(viewModel.ready().tonightMarks).containsExactly(Mark.ALCOHOL)
    }

    // ---------------------------------------------------------------- Drop anchor

    @Test
    fun `drop anchor starts the night and offers undo`() = runTest {
        val fakes = TonightFakes(now = at(22, 10))
        val viewModel = viewModel(fakes)
        assertThat(viewModel.ready().phase).isEqualTo(TonightPhase.EVENING)

        viewModel.onDropAnchor(wholeNightLog = true)

        assertThat(viewModel.events.first()).isEqualTo(TonightEvent.AnchorDropped(1))
        assertThat(fakes.nightController.nightControllerCalls).containsExactly("dropAnchor(TONIGHT, true)")
        assertThat(viewModel.ready().phase).isEqualTo(TonightPhase.NIGHT)

        viewModel.onUndoAnchor(1)

        assertThat(fakes.nightController.nightControllerCalls).contains("undoAnchor(1)")
        assertThat(viewModel.ready().phase).isEqualTo(TonightPhase.EVENING)
    }

    @Test
    fun `an anchor that couldn't be saved says so`() = runTest {
        val fakes = TonightFakes(now = at(22, 10))
        fakes.nightController.dropAnchorResult = null
        val viewModel = viewModel(fakes)

        viewModel.onDropAnchor(wholeNightLog = false)

        assertThat(viewModel.events.first()).isEqualTo(TonightEvent.AnchorFailed)
        assertThat(viewModel.ready().phase).isEqualTo(TonightPhase.EVENING)
    }

    @Test
    fun `I'm up ends night mode until the next anchor`() = runTest {
        val fakes = TonightFakes(now = at(23, 30))
        val viewModel = viewModel(fakes)
        assertThat(viewModel.ready().phase).isEqualTo(TonightPhase.NIGHT)

        viewModel.onEndNight()

        assertThat(fakes.nightController.nightControllerCalls).contains("endNight")
        assertThat(viewModel.ready().phase).isNotEqualTo(TonightPhase.NIGHT)
        assertThat(fakes.nightController.state.value.endedAt).isEqualTo(at(23, 30))

        fakes.time.advance(Duration.ofMinutes(10))
        viewModel.onDropAnchor(wholeNightLog = false)
        assertThat(viewModel.ready().phase).isEqualTo(TonightPhase.NIGHT)
    }

    @Test
    fun `I'm up mid-minute ends night mode at once, not at the next tick`() = runTest {
        val fakes = TonightFakes(now = at(23, 30))
        val viewModel = viewModel(fakes)
        // The last tick came at 23:30:00; the tap is 45 s later, before the 23:31 tick.
        val tap = at(23, 30).plusSeconds(45)
        fakes.time.now = tap

        viewModel.onEndNight()

        assertThat(fakes.nightController.state.value.endedAt).isEqualTo(tap)
        assertThat(viewModel.ready().phase).isNotEqualTo(TonightPhase.NIGHT)
        assertThat(viewModel.ready().night).isNull()
    }

    @Test
    fun `resume restarts the movement log`() = runTest {
        val fakes = TonightFakes(now = at(23, 30))
        viewModel(fakes).onResumeLog()
        assertThat(fakes.nightController.nightControllerCalls).containsExactly("resumeMovementLog")
    }

    // ---------------------------------------------------------------- Hush

    @Test
    fun `hush plays the chosen colour, timer and volume`() = runTest {
        val fakes = TonightFakes(now = at(22, 0))
        val viewModel = viewModel(fakes)

        viewModel.onHushColor(HushColor.BROWN)
        viewModel.onHushTimer(Duration.ofMinutes(30))
        viewModel.onHushVolume(0.4f)
        viewModel.onHushPlay()

        assertThat(fakes.nightController.nightControllerCalls).containsExactly("startHush(BROWN, PT30M, 0.4)")
        assertThat(viewModel.ready().hush.playing).isTrue()
    }

    @Test
    fun `changing hush while it plays carries on with the change`() = runTest {
        val fakes = TonightFakes(now = at(22, 0))
        val viewModel = viewModel(fakes)
        viewModel.onHushPlay()

        viewModel.onHushColor(HushColor.WHITE)
        viewModel.onHushVolume(0.5f)
        viewModel.onHushStop()

        assertThat(fakes.nightController.nightControllerCalls).containsExactly(
            "startHush(PINK, PT45M, 0.25)",
            "startHush(WHITE, PT45M, 0.25)",
            "setHushVolume(0.5)",
            "stopHush",
        ).inOrder()
    }

    @Test
    fun `until my window needs an alarm, else the default timer`() = runTest {
        val withAlarm = TonightFakes(now = at(22, 0))
        viewModel(withAlarm).apply {
            onHushTimer(null)
            onHushPlay()
        }
        assertThat(withAlarm.nightController.nightControllerCalls).containsExactly("startHush(PINK, null, 0.25)")

        val off = WeekPlan(TonightSamples.week.days.map { it.copy(morning = it.morning.copy(alarm = it.morning.alarm.copy(mode = AlarmMode.OFF))) })
        val noAlarm = TonightFakes(now = at(22, 0), week = off)
        viewModel(noAlarm).apply {
            onHushTimer(null)
            onHushPlay()
        }
        assertThat(noAlarm.nightController.nightControllerCalls).containsExactly("startHush(PINK, PT45M, 0.25)")
    }

    // ---------------------------------------------------------------- Catnap

    @Test
    fun `a catnap starts with the chosen length`() = runTest {
        val fakes = TonightFakes(now = at(14, 0))
        val viewModel = viewModel(fakes)

        viewModel.onCatnapLength(Duration.ofMinutes(10))
        viewModel.onStartCatnap()

        assertThat(fakes.nightController.nightControllerCalls).containsExactly("startCatnap(PT10M)")
        assertThat(viewModel.ready().catnap?.running).isNotNull()

        viewModel.onCancelCatnap()
        assertThat(viewModel.ready().catnap?.running).isNull()
    }

    @Test
    fun `a refused catnap says a ring is already set`() = runTest {
        val fakes = TonightFakes(now = at(14, 0))
        fakes.nightController.catnapAllowed = false
        val viewModel = viewModel(fakes)

        viewModel.onStartCatnap()

        assertThat(viewModel.events.first()).isEqualTo(TonightEvent.RingRefused)
    }

    // ---------------------------------------------------------------- One-offs

    @Test
    fun `one-off changes are separate rows`() = runTest {
        val fakes = TonightFakes(now = at(14, 0))
        val viewModel = viewModel(fakes)

        viewModel.onDayOff(wednesday)
        assertThat(viewModel.ready().alarmCard).isInstanceOf(AlarmCardModel.DayOff::class.java)
        viewModel.onClearOverride(wednesday)
        assertThat(viewModel.ready().alarmCard).isInstanceOf(AlarmCardModel.On::class.java)
        viewModel.onJustOnce(wednesday, LocalTime.of(5, 30), AlarmMode.FIXED)
        viewModel.onPause(TonightSamples.today.plusDays(5))
        viewModel.onResumeAlarms()

        assertThat(fakes.schedule.overrides).containsExactly(
            DateOverride(wednesday, OverrideKind.DAY_OFF),
            DateOverride(wednesday, OverrideKind.JUST_ONCE, LocalTime.of(5, 30), AlarmMode.FIXED),
        ).inOrder()
        assertThat(fakes.schedule.clearedOverrides).containsExactly(wednesday)
        assertThat(fakes.schedule.pauses).containsExactly(PlanPause(TonightSamples.today, TonightSamples.today.plusDays(5)), null).inOrder()
        assertThat(fakes.schedule.weekPlan.first()).isEqualTo(TonightSamples.week)
    }

    @Test
    fun `the one-off sheet starts from the date's own alarm`() = runTest {
        val fakes = TonightFakes(now = at(14, 0))
        val viewModel = viewModel(fakes)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.oneOffContext.collect {} }

        val context = checkNotNull(viewModel.oneOffContext.value)

        assertThat(context.startTime(wednesday)).isEqualTo(LocalTime.of(6, 45))
        assertThat(context.startMode(wednesday)).isEqualTo(AlarmMode.SURFACING)
        val saturday = TonightSamples.today.plusDays(4)
        assertThat(context.startTime(saturday)).isEqualTo(LocalTime.of(8, 10))
        assertThat(context.resolve(saturday).alarmOn).isFalse()
    }
}
