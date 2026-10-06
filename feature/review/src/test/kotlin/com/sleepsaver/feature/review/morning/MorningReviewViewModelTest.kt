package com.sleepsaver.feature.review.morning

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.MorningReview
import com.sleepsaver.core.data.repository.ReviewStatus
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.data.repository.SyncResult
import com.sleepsaver.core.data.repository.SyncTrigger
import com.sleepsaver.core.domain.model.DebtChangeBreakdown
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.testing.FakeInsightsRepository
import com.sleepsaver.core.testing.FakeReviewRepository
import com.sleepsaver.core.testing.FakeSettingsRepository
import com.sleepsaver.core.testing.FakeSleepRepository
import com.sleepsaver.core.testing.FakeSyncRepository
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.feature.review.FakeMorningReviewNotifier
import com.sleepsaver.feature.review.FakeTimeProvider
import com.sleepsaver.feature.review.edit.EditKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

@RunWith(AndroidJUnit4::class)
class MorningReviewViewModelTest {

    private val today: LocalDate = SampleData.today
    private val breakdown = DebtChangeBreakdown(
        wakeDate = today,
        need = Duration.ofHours(8),
        slept = Duration.ofMinutes(6 * 60 + 53),
        before = Duration.ofMinutes(162),
        lastNightDelta = Duration.ofMinutes(67),
        fade = Duration.ofMinutes(-36),
        after = Duration.ofMinutes(193),
    )

    private val sleep = FakeSleepRepository(SampleData.nights())
    private val insights = FakeInsightsRepository(breakdownResult = breakdown)
    private val settings = FakeSettingsRepository(AppSettings(onboardingDone = true))
    private val sync = FakeSyncRepository()
    private val reviews = FakeReviewRepository()
    private val notifier = FakeMorningReviewNotifier()
    private val time = FakeTimeProvider()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(date: LocalDate = today) = MorningReviewViewModel(
        SavedStateHandle(mapOf("wakeDate" to date.toString())),
        sleep,
        insights,
        settings,
        sync,
        reviews,
        notifier,
        time,
    )

    private val MorningReviewViewModel.step get() = uiState.value.step

    @Test
    fun `syncs calmly, then shows last night`() = runTest {
        val vm = viewModel()
        assertThat(vm.step).isEqualTo(ReviewStep.Loading(recalculating = false))

        advanceTimeBy(1_000)
        assertThat(vm.step).isInstanceOf(ReviewStep.Loading::class.java)

        advanceUntilIdle()
        assertThat(sync.syncCalls).containsExactly(SyncTrigger.MANUAL)
        val summary = vm.step as ReviewStep.Summary
        assertThat(summary.night.wakeDate).isEqualTo(today)
    }

    @Test
    fun `demo mode skips the sync`() = runTest {
        settings.state.value = AppSettings(demoMode = true)
        val vm = viewModel()
        advanceUntilIdle()

        assertThat(sync.syncCalls).isEmpty()
        assertThat(vm.step).isInstanceOf(ReviewStep.Summary::class.java)
    }

    @Test
    fun `looks right recalculates, then done records a confirmation and clears the notification`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onLooksRight()
        assertThat(vm.step).isEqualTo(ReviewStep.Loading(recalculating = true))
        advanceTimeBy(1_500)
        assertThat(vm.step).isEqualTo(ReviewStep.Loading(recalculating = true))
        advanceUntilIdle()

        val result = vm.step as ReviewStep.Result
        assertThat(result.update.breakdown).isEqualTo(breakdown)
        assertThat(result.update.statusBefore).isEqualTo(DebtStatus.MODERATE)
        assertThat(result.update.statusAfter).isEqualTo(DebtStatus.MODERATE)
        assertThat(sleep.savedOverrides).isEmpty()

        vm.onDone()
        advanceUntilIdle()
        val review = reviews.reviews.value.getValue(today)
        assertThat(review.status).isEqualTo(ReviewStatus.CONFIRMED)
        assertThat(review.debtBefore).isEqualTo(breakdown.before)
        assertThat(review.debtAfter).isEqualTo(breakdown.after)
        assertThat(notifier.cancels).isEqualTo(1)
        assertThat(vm.uiState.value.finished).isTrue()
    }

    @Test
    fun `adjusting saves only the changed field and records an adjustment`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAdjust()
        advanceUntilIdle()
        val editor = (vm.step as ReviewStep.Adjust).editor
        assertThat(editor.kind).isEqualTo(EditKind.RECORDED)
        val earlier = editor.fellAsleep.minus(Duration.ofMinutes(30))
        vm.onFellAsleepChange(earlier)
        vm.onAdjustDone()
        advanceUntilIdle()

        val saved = sleep.savedOverrides.single()
        assertThat(saved.fellAsleep).isEqualTo(earlier)
        assertThat(saved.wokeUp).isNull()
        assertThat(saved.awakeMinutes).isNull()
        assertThat(saved.napMinutes).isNull()
        assertThat(vm.step).isInstanceOf(ReviewStep.Result::class.java)

        vm.onDone()
        advanceUntilIdle()
        assertThat(reviews.reviews.value.getValue(today).status).isEqualTo(ReviewStatus.ADJUSTED)
        assertThat(notifier.cancels).isEqualTo(1)
    }

    @Test
    fun `cancelling an adjustment goes back to the summary without saving`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onAdjust()
        advanceUntilIdle()
        vm.onAwakeChange(90)

        vm.onAdjustCancel()

        assertThat(vm.step).isInstanceOf(ReviewStep.Summary::class.java)
        assertThat(sleep.savedOverrides).isEmpty()
    }

    @Test
    fun `adjust with no changes counts as a confirmation`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onAdjust()
        advanceUntilIdle()

        vm.onAdjustDone()
        advanceUntilIdle()
        vm.onDone()
        advanceUntilIdle()

        assertThat(sleep.savedOverrides).isEmpty()
        assertThat(reviews.reviews.value.getValue(today).status).isEqualTo(ReviewStatus.CONFIRMED)
    }

    @Test
    fun `closing records a dismissal`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onClose()
        advanceUntilIdle()

        val review = reviews.reviews.value.getValue(today)
        assertThat(review.status).isEqualTo(ReviewStatus.DISMISSED)
        assertThat(review.debtBefore).isNull()
        assertThat(notifier.cancels).isEqualTo(1)
        assertThat(vm.uiState.value.finished).isTrue()
    }

    @Test
    fun `closing never overwrites an earlier confirmation`() = runTest {
        reviews.record(MorningReview(today, ReviewStatus.CONFIRMED, SampleData.now, Duration.ofHours(1), Duration.ofHours(2)))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onClose()
        advanceUntilIdle()

        assertThat(reviews.reviews.value.getValue(today).status).isEqualTo(ReviewStatus.CONFIRMED)
        assertThat(vm.uiState.value.finished).isTrue()
    }

    @Test
    fun `closing on the result records the decision`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onLooksRight()
        advanceUntilIdle()

        vm.onClose()
        advanceUntilIdle()

        assertThat(reviews.reviews.value.getValue(today).status).isEqualTo(ReviewStatus.CONFIRMED)
    }

    @Test
    fun `don't show again turns reviews off, dismisses and closes`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onDontShowAgain()
        advanceUntilIdle()

        assertThat(settings.state.value.morningReviewEnabled).isFalse()
        assertThat(reviews.reviews.value.getValue(today).status).isEqualTo(ReviewStatus.DISMISSED)
        assertThat(notifier.cancels).isEqualTo(1)
        assertThat(vm.uiState.value.finished).isTrue()
    }

    @Test
    fun `a missing night can be added by hand`() = runTest {
        sleep.nightsState.value = SampleData.nights(includeLastNight = false)
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.step).isEqualTo(ReviewStep.Missing(syncFailed = false))

        vm.onAdjust()
        advanceUntilIdle()
        val editor = (vm.step as ReviewStep.Adjust).editor
        assertThat(editor.kind).isEqualTo(EditKind.NEW)
        vm.onAdjustDone()
        advanceUntilIdle()

        val (date, fell, woke) = sleep.manualNights.single()
        assertThat(date).isEqualTo(today)
        assertThat(fell).isEqualTo(editor.fellAsleep)
        assertThat(woke).isEqualTo(editor.wokeUp)
        assertThat(vm.step).isInstanceOf(ReviewStep.Result::class.java)
        vm.onDone()
        advanceUntilIdle()
        assertThat(reviews.reviews.value.getValue(today).status).isEqualTo(ReviewStatus.ADJUSTED)
    }

    @Test
    fun `a failed sync with no night says so and can retry`() = runTest {
        sleep.nightsState.value = SampleData.nights(includeLastNight = false)
        sync.nextResult = SyncResult(success = false, error = SyncError.PERMISSION_DENIED)
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.step).isEqualTo(ReviewStep.Missing(syncFailed = true))

        sync.nextResult = SyncResult(success = true)
        sleep.nightsState.value = SampleData.nights()
        vm.onSyncNow()
        assertThat(vm.step).isEqualTo(ReviewStep.Loading(recalculating = false))
        advanceUntilIdle()

        assertThat(sync.syncCalls).hasSize(2)
        assertThat(vm.step).isInstanceOf(ReviewStep.Summary::class.java)
    }

    @Test
    fun `closing a missing night records nothing`() = runTest {
        sleep.nightsState.value = SampleData.nights(includeLastNight = false)
        val vm = viewModel()
        advanceUntilIdle()

        vm.onClose()
        advanceUntilIdle()

        assertThat(reviews.reviews.value).isEmpty()
        assertThat(vm.uiState.value.finished).isTrue()
    }

    @Test
    fun `a bad date falls back to today`() = runTest {
        val vm = MorningReviewViewModel(
            SavedStateHandle(mapOf("wakeDate" to "not-a-date")),
            sleep, insights, settings, sync, reviews, notifier, time,
        )
        advanceUntilIdle()

        assertThat(vm.uiState.value.wakeDate).isEqualTo(today)
    }
}
