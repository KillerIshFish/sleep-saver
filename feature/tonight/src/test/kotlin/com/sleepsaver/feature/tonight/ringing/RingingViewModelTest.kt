package com.sleepsaver.feature.tonight.ringing

import android.content.Context
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.JournalRepository
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.journal.FeelSource
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.testing.FakeJournalRepository
import com.sleepsaver.core.testing.FakeNightController
import com.sleepsaver.core.testing.FakeTimeProvider
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.PlanSamples.NightVariant
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.LocalTime

/**
 * The ringing screen's ViewModel, including before the first unlock after a reboot (direct boot, SPEC_V2 §4.9): it
 * must reach the ring, stop it and take a Morning feel without resolving the journal (credential storage).
 */
@RunWith(AndroidJUnit4::class)
class RingingViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val wakeDate = PlanSamples.today.plusDays(1)
    private val ringAt = PlanSamples.at(wakeDate, LocalTime.of(6, 27))

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun setUnlocked(unlocked: Boolean) = shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(unlocked)

    private class CountingLazy(private val journal: () -> JournalRepository) : Lazy<JournalRepository> {
        var gets = 0
        override fun get(): JournalRepository {
            gets++
            return journal()
        }
    }

    private fun viewModel(controller: FakeNightController, journal: Lazy<JournalRepository>) =
        RingingViewModel(context, controller, journal, FakeTimeProvider(ringAt))

    @Test
    fun `before the first unlock it rings, stops and takes a feel without the journal`() = runTest {
        setUnlocked(false)
        val controller = FakeNightController(PlanSamples.nightState(NightVariant.RINGING_PLANNED), clock = { ringAt })
        val journal = CountingLazy { throw IllegalStateException("credential storage is locked") }
        val viewModel = viewModel(controller, journal)

        val sounding = viewModel.uiState.value as RingingUiState.Sounding
        assertThat(sounding.ringing.kind).isEqualTo(RingKind.PLANNED)

        viewModel.onStop()
        assertThat(viewModel.uiState.value).isEqualTo(RingingUiState.AfterStop(RingKind.PLANNED, wakeDate, feel = null, unlocked = false))

        viewModel.onFeel(MorningFeel.SLOW)

        assertThat(controller.feelsRecorded).containsExactly(wakeDate to MorningFeel.SLOW)
        assertThat(controller.nightControllerCalls).containsExactly("stopRinging", "recordMorningFeel($wakeDate, SLOW)").inOrder()
        assertThat((viewModel.uiState.value as RingingUiState.AfterStop).feel).isEqualTo(MorningFeel.SLOW)
        assertThat(journal.gets).isEqualTo(0)
    }

    @Test
    fun `once unlocked, a feel already given shows`() = runTest {
        setUnlocked(true)
        val journal = FakeJournalRepository()
        journal.setFeel(wakeDate, MorningFeel.CLEAR, FeelSource.REVIEW)
        val lazy = CountingLazy { journal }
        val controller = FakeNightController(PlanSamples.nightState(NightVariant.RINGING_PLANNED), clock = { ringAt })
        val viewModel = viewModel(controller, lazy)

        viewModel.onStop()

        assertThat(viewModel.uiState.value).isEqualTo(RingingUiState.AfterStop(RingKind.PLANNED, wakeDate, MorningFeel.CLEAR, unlocked = true))
        assertThat(lazy.gets).isEqualTo(1)
    }

    @Test
    fun `unlocking while the screen is up allows the morning review`() = runTest {
        setUnlocked(false)
        val controller = FakeNightController(PlanSamples.nightState(NightVariant.RINGING_PLANNED), clock = { ringAt })
        val viewModel = viewModel(controller, CountingLazy { FakeJournalRepository() })
        viewModel.onStop()

        setUnlocked(true)
        viewModel.onResume()

        assertThat((viewModel.uiState.value as RingingUiState.AfterStop).unlocked).isTrue()
    }

    @Test
    fun `snooze closes the screen`() = runTest {
        setUnlocked(true)
        val controller = FakeNightController(PlanSamples.nightState(NightVariant.RINGING_PLANNED), clock = { ringAt })
        val viewModel = viewModel(controller, CountingLazy { FakeJournalRepository() })

        viewModel.onSnooze()

        assertThat(controller.nightControllerCalls).containsExactly("snooze")
        assertThat(viewModel.uiState.value).isEqualTo(RingingUiState.Gone)
    }

    @Test
    fun `a ring stopped elsewhere closes the screen`() = runTest {
        val controller = FakeNightController(PlanSamples.nightState(NightVariant.RINGING_TEST), clock = { ringAt })
        val viewModel = viewModel(controller, CountingLazy { FakeJournalRepository() })
        assertThat(viewModel.uiState.value).isInstanceOf(RingingUiState.Sounding::class.java)

        controller.state.value = controller.state.value.copy(ringing = null)

        assertThat(viewModel.uiState.value).isEqualTo(RingingUiState.Gone)
    }

    @Test
    fun `a catnap or test ring never asks for a feel`() = runTest {
        setUnlocked(true)
        val controller = FakeNightController(PlanSamples.nightState(NightVariant.RINGING_CATNAP), clock = { ringAt })
        val viewModel = viewModel(controller, CountingLazy { FakeJournalRepository() })

        viewModel.onStop()
        viewModel.onFeel(MorningFeel.OKAY)

        assertThat(viewModel.uiState.value).isEqualTo(RingingUiState.AfterStop(RingKind.NAP, null, null, unlocked = true))
        assertThat(controller.feelsRecorded).isEmpty()
    }

    @Test
    fun `a new ring after stop sounds again`() = runTest {
        val controller = FakeNightController(PlanSamples.nightState(NightVariant.RINGING_PLANNED), clock = { ringAt })
        val ringing = checkNotNull(controller.state.value.ringing)
        val viewModel = RingingViewModel(context, controller, CountingLazy { FakeJournalRepository() }, FakeTimeProvider(ringAt))
        viewModel.onStop()

        // The backup ring, a few minutes later.
        controller.state.value = controller.state.value.copy(ringing = ringing.copy(since = ringAt.plusSeconds(300)))

        assertThat(viewModel.uiState.value).isInstanceOf(RingingUiState.Sounding::class.java)
    }
}
