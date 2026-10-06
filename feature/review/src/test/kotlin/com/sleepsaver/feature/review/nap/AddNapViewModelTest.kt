package com.sleepsaver.feature.review.nap

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.ManualNap
import com.sleepsaver.core.testing.FakeSleepRepository
import com.sleepsaver.feature.review.FailingWritesSleepRepository
import com.sleepsaver.feature.review.FakeTimeProvider
import com.sleepsaver.feature.review.TestNight
import com.sleepsaver.feature.review.TestNight.at
import com.sleepsaver.feature.review.TestNight.wakeDate
import com.sleepsaver.feature.review.common.manualNapId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class AddNapViewModelTest {

    // 15:07 on the wake date: a nap that just ended started at 14:45.
    private val now = at(wakeDate, 15, 7)
    private val nap = ManualNap(id = 3, start = at(wakeDate, 9, 30), end = at(wakeDate, 9, 50), offset = TestNight.offset)
    private val sleep = FakeSleepRepository(TestNight.nights(manualNaps = listOf(nap)))
    private val time = FakeTimeProvider(now = now, zone = TestNight.zone)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = AddNapViewModel(sleep, time)

    @Test
    fun `defaults to a 20 minute nap that just ended, today`() = runTest {
        val state = viewModel().uiState.value

        assertThat(state.day).isEqualTo(NapDay.TODAY)
        assertThat(state.date).isEqualTo(wakeDate)
        assertThat(state.minutes).isEqualTo(20)
        assertThat(state.start).isEqualTo(at(wakeDate, 14, 45))
        assertThat(state.canSave).isTrue()
    }

    @Test
    fun `saves the nap with the device offset and closes`() = runTest {
        val vm = viewModel()
        vm.onStartChange(at(wakeDate, 13, 0))
        vm.onMinutesChange(35)

        vm.onSave()
        advanceUntilIdle()

        assertThat(sleep.addedNaps).containsExactly(at(wakeDate, 13, 0) to at(wakeDate, 13, 35))
        assertThat(vm.uiState.value.finished).isTrue()
    }

    @Test
    fun `length stays between 10 minutes and 3 hours`() = runTest {
        val vm = viewModel()

        vm.onMinutesChange(5)
        assertThat(vm.uiState.value.minutes).isEqualTo(10)
        vm.onMinutesChange(400)
        assertThat(vm.uiState.value.minutes).isEqualTo(180)
    }

    @Test
    fun `yesterday keeps the clock time`() = runTest {
        val vm = viewModel()
        vm.onStartChange(at(wakeDate, 13, 10))

        vm.onDayChange(NapDay.YESTERDAY)

        val state = vm.uiState.value
        assertThat(state.date).isEqualTo(wakeDate.minusDays(1))
        assertThat(state.start).isEqualTo(at(wakeDate.minusDays(1), 13, 10))
        assertThat(state.startMax).isEqualTo(at(wakeDate, 0).minus(Duration.ofMinutes(5)))
    }

    @Test
    fun `a nap that would end after now can't be saved`() = runTest {
        val vm = viewModel()
        vm.onStartChange(at(wakeDate, 15, 0))

        assertThat(vm.uiState.value.endsInFuture).isTrue()
        vm.onSave()
        advanceUntilIdle()

        assertThat(sleep.addedNaps).isEmpty()
        assertThat(vm.uiState.value.finished).isFalse()
    }

    @Test
    fun `a save that fails keeps the form open`() = runTest {
        val vm = AddNapViewModel(FailingWritesSleepRepository(sleep), time)

        vm.onSave()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.finished).isFalse()
        assertThat(state.saveFailed).isTrue()
        assertThat(state.canSave).isTrue()
    }

    @Test
    fun `saving re-checks the clock`() = runTest {
        val vm = viewModel()
        // The form opened at 15:07; a nap from 15:00 for 10 minutes ends after that...
        vm.onStartChange(at(wakeDate, 15, 0))
        vm.onMinutesChange(10)
        assertThat(vm.uiState.value.endsInFuture).isTrue()

        // ...but not after 15:20, when the user finally taps Save.
        time.now = at(wakeDate, 15, 20)
        vm.onSave()
        advanceUntilIdle()

        assertThat(sleep.addedNaps).containsExactly(at(wakeDate, 15, 0) to at(wakeDate, 15, 10))
    }

    @Test
    fun `lists the chosen day's naps and deletes a manual one`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.uiState.value.naps.map { it.manualNapId }).containsExactly(3L)

        vm.onDayChange(NapDay.YESTERDAY)
        advanceUntilIdle()
        assertThat(vm.uiState.value.naps).isEmpty()

        vm.onDeleteNap(3)
        advanceUntilIdle()
        assertThat(sleep.deletedNaps).containsExactly(3L)
    }
}
