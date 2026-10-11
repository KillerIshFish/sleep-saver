package com.sleepsaver.feature.tonight.check

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.alarm.TuningReason
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

@RunWith(AndroidJUnit4::class)
class AlarmCheckViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(fakes: TonightFakes): AlarmCheckViewModel {
        val viewModel = AlarmCheckViewModel(fakes.nightLog, fakes.nightController, fakes.clockSource(), fakes.time, writer())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return viewModel
    }

    private fun AlarmCheckViewModel.ready(): AlarmCheckUiState.Ready = uiState.value as AlarmCheckUiState.Ready

    @Test
    fun `no test ring and the standard trigger`() = runTest {
        val state = viewModel(TonightFakes()).ready()

        assertThat(state.lastTest).isNull()
        assertThat(state.tuning).isNull()
        assertThat(state.tuned).isFalse()
        assertThat(state.today).isEqualTo(TonightSamples.today)
    }

    @Test
    fun `the last test ring and a tuned trigger`() = runTest {
        val fakes = TonightFakes()
        val test = PlanSamples.alarmEvent(RingReason.TEST, wakeDate = TonightSamples.today)
        fakes.nightLog.eventsState.value = listOf(PlanSamples.alarmEvent(RingReason.RESTLESS), test)
        fakes.nightLog.tuningState.value = PlanSamples.tuning()

        val state = viewModel(fakes).ready()

        assertThat(state.lastTest).isEqualTo(test)
        assertThat(state.tuning?.reason).isEqualTo(TuningReason.RAISED_AFTER_DEEP_RINGS)
        assertThat(state.tuned).isTrue()
    }

    @Test
    fun `a reset row reads like no tuning`() = runTest {
        val fakes = TonightFakes()
        fakes.nightLog.tuningState.value = PlanSamples.tuning().copy(reason = TuningReason.RESET)
        assertThat(viewModel(fakes).ready().tuned).isFalse()
    }

    @Test
    fun `reset to default`() = runTest {
        val fakes = TonightFakes()
        fakes.nightLog.tuningState.value = PlanSamples.tuning()
        val viewModel = viewModel(fakes)

        viewModel.onResetTuning()

        assertThat(fakes.nightLog.resetTuningCalls).isEqualTo(1)
        assertThat(viewModel.ready().tuned).isFalse()
    }

    @Test
    fun `test ring answers`() = runTest {
        val fakes = TonightFakes()
        val viewModel = viewModel(fakes)

        viewModel.onTestRing()
        assertThat(viewModel.events.first()).isEqualTo(TestRingEvent.Set(at(14, 1), TonightSamples.zone))

        // The controller refuses while a ring is set or sounding.
        fakes.nightController.testRingAllowed = false
        viewModel.onTestRing()
        assertThat(viewModel.events.first()).isEqualTo(TestRingEvent.Refused)
    }
}
