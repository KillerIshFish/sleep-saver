package com.sleepsaver.feature.review.edit

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.testing.FakeInsightsRepository
import com.sleepsaver.core.testing.FakeSettingsRepository
import com.sleepsaver.core.testing.FakeSleepRepository
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.feature.review.FakeTimeProvider
import com.sleepsaver.feature.review.TestNight
import com.sleepsaver.feature.review.TestNight.at
import com.sleepsaver.feature.review.TestNight.wakeDate
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
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class EditNightViewModelTest {

    private val sleep = FakeSleepRepository(TestNight.nights())
    private val settings = FakeSettingsRepository(
        AppSettings(sleep = UserSleepSettings(sleepNeed = Duration.ofMinutes(450), typicalWake = LocalTime.of(6, 30))),
    )
    private val insights = FakeInsightsRepository()
    private val time = FakeTimeProvider(now = TestNight.now, zone = TestNight.zone)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(date: LocalDate = wakeDate) =
        EditNightViewModel(SavedStateHandle(mapOf("wakeDate" to date.toString())), sleep, settings, insights, time)

    @Test
    fun `saves only the field that changed`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        val editor = vm.uiState.value.editor!!
        assertThat(editor.awakeMinutes).isEqualTo(30)

        vm.onAwakeChange(editor.awakeMinutes + 5)
        vm.onDone()
        advanceUntilIdle()

        val saved = sleep.savedOverrides.single()
        assertThat(saved.wakeDate).isEqualTo(wakeDate)
        assertThat(saved.awakeMinutes).isEqualTo(35)
        assertThat(saved.fellAsleep).isNull()
        assertThat(saved.wokeUp).isNull()
        assertThat(saved.napMinutes).isNull()
        assertThat(saved.offset).isEqualTo(TestNight.offset)
        assertThat(vm.uiState.value.finished).isTrue()
    }

    @Test
    fun `moving both times saves both and nothing else`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onFellAsleepChange(at(wakeDate.minusDays(1), 23, 45))
        vm.onWokeUpChange(at(wakeDate, 6, 40))
        assertThat(vm.uiState.value.editor!!.totalSleep).isEqualTo(Duration.ofMinutes(6 * 60 + 25))
        vm.onDone()
        advanceUntilIdle()

        val saved = sleep.savedOverrides.single()
        assertThat(saved.fellAsleep).isEqualTo(at(wakeDate.minusDays(1), 23, 45))
        assertThat(saved.wokeUp).isEqualTo(at(wakeDate, 6, 40))
        assertThat(saved.awakeMinutes).isNull()
        assertThat(saved.napMinutes).isNull()
    }

    @Test
    fun `nothing changed closes without saving`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onDone()
        advanceUntilIdle()

        assertThat(sleep.savedOverrides).isEmpty()
        assertThat(sleep.manualNights).isEmpty()
        assertThat(vm.uiState.value.finished).isTrue()
    }

    @Test
    fun `an invalid night can't be saved`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        val editor = vm.uiState.value.editor!!

        vm.onWokeUpChange(editor.fellAsleep.minus(Duration.ofMinutes(5)))
        vm.onDone()
        advanceUntilIdle()

        assertThat(vm.uiState.value.editor!!.error).isEqualTo(EditError.WAKE_BEFORE_SLEEP)
        assertThat(sleep.savedOverrides).isEmpty()
        assertThat(vm.uiState.value.finished).isFalse()
    }

    @Test
    fun `a missing night is added as a manual night from typical wake and need`() = runTest {
        val missing = wakeDate.plusDays(1)
        val vm = viewModel(missing)
        advanceUntilIdle()
        val editor = vm.uiState.value.editor!!
        assertThat(editor.kind).isEqualTo(EditKind.NEW)
        assertThat(editor.wokeUp).isEqualTo(at(missing, 6, 30))
        assertThat(editor.fellAsleep).isEqualTo(at(missing, 6, 30).minus(Duration.ofMinutes(450)))

        vm.onDone()
        advanceUntilIdle()

        val (date, fell, woke) = sleep.manualNights.single()
        assertThat(date).isEqualTo(missing)
        assertThat(fell).isEqualTo(editor.fellAsleep)
        assertThat(woke).isEqualTo(editor.wokeUp)
        assertThat(sleep.savedOverrides).isEmpty()
        assertThat(vm.uiState.value.finished).isTrue()
    }

    @Test
    fun `a missing night starts at the schedule's typical wake when there is one`() = runTest {
        insights.scheduleResult = SampleData.schedule()
        val vm = EditNightViewModel(
            SavedStateHandle(mapOf("wakeDate" to SampleData.today.plusDays(1).toString())),
            FakeSleepRepository(SampleData.nights()),
            settings,
            insights,
            FakeTimeProvider(),
        )
        advanceUntilIdle()

        val editor = vm.uiState.value.editor!!
        val wake = editor.wokeUp.atZone(SampleData.zone).toLocalTime()
        assertThat(wake).isEqualTo(SampleData.schedule().typicalWake.toLocalTime())
    }

    @Test
    fun `revert drops the edits and closes`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onRevert()
        advanceUntilIdle()

        assertThat(sleep.reverted).containsExactly(wakeDate)
        assertThat(vm.uiState.value.finished).isTrue()
    }
}
