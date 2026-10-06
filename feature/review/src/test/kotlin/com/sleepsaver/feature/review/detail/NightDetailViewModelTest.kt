package com.sleepsaver.feature.review.detail

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.ManualNap
import com.sleepsaver.core.testing.FakeSleepRepository
import com.sleepsaver.feature.review.FakeTimeProvider
import com.sleepsaver.feature.review.TestNight
import com.sleepsaver.feature.review.TestNight.at
import com.sleepsaver.feature.review.TestNight.wakeDate
import com.sleepsaver.feature.review.common.NightSourceKind
import com.sleepsaver.feature.review.common.manualNapId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class NightDetailViewModelTest {

    private val nap = ManualNap(id = 7, start = at(wakeDate, 14), end = at(wakeDate, 14, 25), offset = TestNight.offset)
    private val sleep = FakeSleepRepository(TestNight.nights(manualNaps = listOf(nap)))
    private val time = FakeTimeProvider(now = TestNight.now, zone = TestNight.zone)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(date: LocalDate = wakeDate) =
        NightDetailViewModel(SavedStateHandle(mapOf("wakeDate" to date.toString())), sleep, time)

    @Test
    fun `shows the night with its source and naps`() = runTest {
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val state = vm.uiState.first { !it.loading }
        assertThat(state.isMissing).isFalse()
        assertThat(state.night!!.wakeDate).isEqualTo(wakeDate)
        assertThat(state.source!!.kind).isEqualTo(NightSourceKind.HEALTH_CONNECT)
        assertThat(state.night!!.naps.single().manualNapId).isEqualTo(7)
    }

    @Test
    fun `a night with no data is missing`() = runTest {
        val vm = viewModel(wakeDate.minusDays(3))
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.isMissing).isTrue()
    }

    @Test
    fun `deletes a manual nap and reverts edits`() = runTest {
        val vm = viewModel()

        vm.onDeleteNap(7)
        vm.onRevert()
        advanceUntilIdle()

        assertThat(sleep.deletedNaps).containsExactly(7L)
        assertThat(sleep.reverted).containsExactly(wakeDate)
    }
}
