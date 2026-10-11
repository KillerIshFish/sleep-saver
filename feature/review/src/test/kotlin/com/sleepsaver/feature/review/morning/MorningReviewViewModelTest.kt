package com.sleepsaver.feature.review.morning

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.JournalRepository
import com.sleepsaver.core.data.repository.MorningReview
import com.sleepsaver.core.data.repository.NightLogRepository
import com.sleepsaver.core.data.repository.ReviewStatus
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.data.repository.SyncResult
import com.sleepsaver.core.data.repository.SyncTrigger
import com.sleepsaver.core.domain.alarm.AlarmTuning
import com.sleepsaver.core.domain.alarm.LandingStage
import com.sleepsaver.core.domain.alarm.TuningReason
import com.sleepsaver.core.domain.alarm.WakeLanding
import com.sleepsaver.core.domain.journal.FeelSource
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.journal.MorningFeelEntry
import com.sleepsaver.core.domain.journal.PromptKind
import com.sleepsaver.core.domain.journal.TomorrowList
import com.sleepsaver.core.domain.model.DebtChangeBreakdown
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.testing.FakeHealthConnectGateway
import com.sleepsaver.core.testing.FakeInsightsRepository
import com.sleepsaver.core.testing.FakeJournalRepository
import com.sleepsaver.core.testing.FakeNightLogRepository
import com.sleepsaver.core.testing.FakePatternsRepository
import com.sleepsaver.core.testing.FakeReviewRepository
import com.sleepsaver.core.testing.FakeSettingsRepository
import com.sleepsaver.core.testing.FakeSleepRepository
import com.sleepsaver.core.testing.FakeSyncRepository
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.feature.review.FailingWritesSleepRepository
import com.sleepsaver.feature.review.FakeMorningReviewNotifier
import com.sleepsaver.feature.review.FakeTimeProvider
import com.sleepsaver.feature.review.edit.EditKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
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
import java.time.LocalTime
import java.time.ZonedDateTime
import com.sleepsaver.core.testing.FakeTimeProvider as TickingTime

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
    private var time = FakeTimeProvider()
    private val journal = FakeJournalRepository()
    private val nightLog = FakeNightLogRepository()
    private val patterns = FakePatternsRepository()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val healthConnect = FakeHealthConnectGateway()

    private fun viewModel(date: LocalDate = today, clock: TimeProvider = time) = MorningReviewViewModel(
        SavedStateHandle(mapOf("wakeDate" to date.toString())),
        sleep,
        insights,
        settings,
        sync,
        reviews,
        notifier,
        clock,
        healthConnect,
        journal,
        nightLog,
        patterns,
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
        assertThat(vm.step).isEqualTo(ReviewStep.Missing())

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
        assertThat(vm.step).isEqualTo(ReviewStep.Missing(SyncError.PERMISSION_DENIED))

        sync.nextResult = SyncResult(success = true)
        sleep.nightsState.value = SampleData.nights()
        vm.onSyncNow()
        assertThat(vm.step).isEqualTo(ReviewStep.Loading(recalculating = false))
        advanceUntilIdle()

        assertThat(sync.syncCalls).hasSize(2)
        assertThat(vm.step).isInstanceOf(ReviewStep.Summary::class.java)
    }

    @Test
    fun `missing Health Connect is reported so the screen can offer the fix`() = runTest {
        sleep.nightsState.value = SampleData.nights(includeLastNight = false)
        sync.nextResult = SyncResult(success = false, error = SyncError.HEALTH_CONNECT_NOT_INSTALLED)
        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.step).isEqualTo(ReviewStep.Missing(SyncError.HEALTH_CONNECT_NOT_INSTALLED))
    }

    @Test
    fun `a sync that throws still lands on a friendly state`() = runTest {
        sleep.nightsState.value = SampleData.nights(includeLastNight = false)
        val vm = MorningReviewViewModel(
            SavedStateHandle(mapOf("wakeDate" to today.toString())),
            sleep, insights, settings,
            object : com.sleepsaver.core.data.repository.SyncRepository by sync {
                override suspend fun sync(trigger: SyncTrigger): SyncResult = error("boom")
            },
            reviews, notifier, time, healthConnect, journal, nightLog, patterns,
        )
        advanceUntilIdle()

        assertThat(vm.step).isEqualTo(ReviewStep.Missing(SyncError.UNKNOWN))
    }

    @Test
    fun `an adjustment that can't be saved goes back to the editor with a note`() = runTest {
        val failing = FailingWritesSleepRepository(sleep)
        val vm = MorningReviewViewModel(
            SavedStateHandle(mapOf("wakeDate" to today.toString())),
            failing, insights, settings, sync, reviews, notifier, time, healthConnect, journal, nightLog, patterns,
        )
        advanceUntilIdle()
        vm.onAdjust()
        advanceUntilIdle()
        vm.onAwakeChange(90)

        vm.onAdjustDone()
        advanceUntilIdle()

        val adjust = vm.step as ReviewStep.Adjust
        assertThat(adjust.saveFailed).isTrue()
        assertThat(adjust.editor.awakeMinutes).isEqualTo(90)
        assertThat(reviews.reviews.value).isEmpty()

        vm.onAwakeChange(95)
        assertThat((vm.step as ReviewStep.Adjust).saveFailed).isFalse()
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
            sleep, insights, settings, sync, reviews, notifier, time, healthConnect, journal, nightLog, patterns,
        )
        advanceUntilIdle()

        assertThat(vm.uiState.value.wakeDate).isEqualTo(today)
    }

    @Test
    fun `sync now on a missing night goes through Garmin Connect when Garmin is the source`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.garminSyncIntent()).isNotNull()

        settings.state.value = settings.state.value.let { it.copy(sleep = it.sleep.copy(source = SourceSelection.Single("com.example.other"))) }
        advanceUntilIdle()
        assertThat(vm.garminSyncIntent()).isNull()
    }

    // ---------------------------------------------------------------- Morning feel

    private fun at(date: LocalDate, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(date, LocalTime.of(hour, minute), SampleData.zone).toInstant()

    @Test
    fun `an unanswered morning feel is asked and saved from the review`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.uiState.value.feel).isNull()
        assertThat(vm.uiState.value.feelEditable).isTrue()

        vm.onFeel(MorningFeel.CLEAR)
        advanceUntilIdle()

        val call = journal.feelsSet.single()
        assertThat(call.wakeDate).isEqualTo(today)
        assertThat(call.feel).isEqualTo(MorningFeel.CLEAR)
        assertThat(call.source).isEqualTo(FeelSource.REVIEW)
        assertThat(vm.uiState.value.feel?.feel).isEqualTo(MorningFeel.CLEAR)
    }

    @Test
    fun `a feel answered on the ringing screen is shown and can be changed`() = runTest {
        journal.feelsState.value = mapOf(today to MorningFeelEntry(today, MorningFeel.SLOW, SampleData.now, SampleData.zone.rules.getOffset(SampleData.now), FeelSource.RING))
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.uiState.value.feel?.source).isEqualTo(FeelSource.RING)

        vm.onFeel(MorningFeel.BRIGHT)
        advanceUntilIdle()

        assertThat(vm.uiState.value.feel?.feel).isEqualTo(MorningFeel.BRIGHT)
        assertThat(vm.uiState.value.feel?.source).isEqualTo(FeelSource.REVIEW)
    }

    @Test
    fun `feel stays editable when the review is opened at 00·30 after midnight`() = runTest {
        // Tuesday's review opened early on Wednesday: Wednesday's wake time (06:45 in the week plan) is still ahead.
        patterns.debtPreviewInputsState.value = PlanSamples.debtPreviewInputs()
        time = FakeTimeProvider(now = at(today.plusDays(1), 0, 30))
        val vm = viewModel(today)
        advanceUntilIdle()

        assertThat(vm.uiState.value.feelEditable).isTrue()
        vm.onFeel(MorningFeel.OKAY)
        advanceUntilIdle()
        assertThat(journal.feelsSet.single().wakeDate).isEqualTo(today)
    }

    @Test
    fun `feel is read-only once the next wake time has passed`() = runTest {
        patterns.debtPreviewInputsState.value = PlanSamples.debtPreviewInputs()
        time = FakeTimeProvider(now = at(today.plusDays(1), 7, 0))
        val vm = viewModel(today)
        advanceUntilIdle()

        assertThat(vm.uiState.value.feelEditable).isFalse()
        vm.onFeel(MorningFeel.OKAY)
        advanceUntilIdle()
        assertThat(journal.feelsSet).isEmpty()
    }

    @Test
    fun `today follows the date when the review stays open past midnight`() = runTest {
        // A clock whose minute ticks move on.
        val clock = TickingTime(now = at(today, 23, 50), zone = SampleData.zone)
        val vm = viewModel(today, clock)
        advanceUntilIdle()
        assertThat(vm.uiState.value.today).isEqualTo(today)

        clock.advance(Duration.ofMinutes(20))
        advanceUntilIdle()

        assertThat(vm.uiState.value.today).isEqualTo(today.plusDays(1))
    }

    @Test
    fun `without schedule inputs feel locks at midnight`() = runTest {
        time = FakeTimeProvider(now = at(today.plusDays(1), 0, 30))
        val vm = viewModel(today)
        advanceUntilIdle()

        assertThat(vm.uiState.value.feelEditable).isFalse()
    }

    // ---------------------------------------------------------------- Odd night

    @Test
    fun `an odd night is asked about once and stays up until skipped`() = runTest {
        patterns.oddNightsState.value = mapOf(today to PlanSamples.oddNight(today))
        val vm = viewModel()
        advanceTimeBy(500)
        assertThat(vm.uiState.value.oddNight).isNull() // not while syncing
        advanceUntilIdle()

        assertThat(vm.uiState.value.oddNight).isEqualTo(PlanSamples.oddNight(today))
        assertThat(journal.promptsMarked).containsExactly(today to PromptKind.ODD_NIGHT)

        vm.onMarkToggle(Mark.STRESS)
        advanceUntilIdle()
        assertThat(vm.uiState.value.oddNight).isNotNull()
        assertThat(vm.uiState.value.marks).containsExactly(Mark.STRESS)

        vm.onOddNightDismiss()
        assertThat(vm.uiState.value.oddNight).isNull()
    }

    @Test
    fun `an odd night already asked about today stays hidden`() = runTest {
        patterns.oddNightsState.value = mapOf(today to PlanSamples.oddNight(today))
        journal.promptsState.value = setOf(today to PromptKind.ODD_NIGHT)
        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.uiState.value.oddNight).isNull()
        assertThat(journal.promptsMarked).isEmpty()
    }

    @Test
    fun `an ordinary night or a missing one asks nothing`() = runTest {
        sleep.nightsState.value = SampleData.nights(includeLastNight = false)
        patterns.oddNightsState.value = mapOf(today to PlanSamples.oddNight(today))
        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.step).isInstanceOf(ReviewStep.Missing::class.java)
        assertThat(vm.uiState.value.oddNight).isNull()
        assertThat(journal.promptsMarked).isEmpty()
    }

    // ---------------------------------------------------------------- Tomorrow list

    @Test
    fun `last night's tomorrow list comes back in the morning`() = runTest {
        val offset = SampleData.zone.rules.getOffset(SampleData.now)
        journal.tomorrowListsState.value = mapOf(
            today.minusDays(1) to TomorrowList(today.minusDays(1), "- call Sam\n\n• book the dentist", SampleData.now, offset),
            today to TomorrowList(today, "tonight's list", SampleData.now, offset),
        )
        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.uiState.value.tomorrowList?.nightOf).isEqualTo(today.minusDays(1))
        assertThat(vm.uiState.value.tomorrowList?.items).containsExactly("call Sam", "book the dentist").inOrder()
    }

    @Test
    fun `a blank list shows nothing`() = runTest {
        val offset = SampleData.zone.rules.getOffset(SampleData.now)
        journal.tomorrowListsState.value = mapOf(today.minusDays(1) to TomorrowList(today.minusDays(1), " \n - ", SampleData.now, offset))
        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.uiState.value.tomorrowList).isNull()
    }

    // ---------------------------------------------------------------- Wake landing and tuning

    @Test
    fun `opening the review refreshes the alarm tuning once, after the sync`() = runTest {
        val vm = viewModel()
        advanceTimeBy(500)
        assertThat(nightLog.refreshTuningCalls).isEqualTo(0)
        advanceUntilIdle()
        assertThat(nightLog.refreshTuningCalls).isEqualTo(1)

        vm.onSyncNow()
        advanceUntilIdle()
        assertThat(nightLog.refreshTuningCalls).isEqualTo(1)
    }

    @Test
    fun `the landing comes with this week's tuning note, and reset clears it`() = runTest {
        nightLog.landingsState.value = mapOf(today to PlanSamples.landing(LandingStage.LIGHT, today))
        nightLog.tuningState.value = PlanSamples.tuning() // raised at 08:00 today
        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.uiState.value.landing?.stageAtRing).isEqualTo(LandingStage.LIGHT)
        assertThat(vm.uiState.value.night?.wakeDate).isEqualTo(today)
        assertThat(vm.uiState.value.tuningNote?.reason).isEqualTo(TuningReason.RAISED_AFTER_DEEP_RINGS)

        vm.onResetTuning()
        advanceUntilIdle()
        assertThat(nightLog.resetTuningCalls).isEqualTo(1)
        assertThat(vm.uiState.value.tuningNote).isNull()
    }

    @Test
    fun `an old change or a reset row explains nothing`() = runTest {
        nightLog.landingsState.value = mapOf(today to PlanSamples.landing(LandingStage.LIGHT, today))
        val offset = SampleData.zone.rules.getOffset(SampleData.now)
        nightLog.tuningState.value = AlarmTuning(2.75, TuningReason.RAISED_AFTER_DEEP_RINGS, SampleData.now.minus(Duration.ofDays(8)), offset)
        val vm = viewModel()
        advanceUntilIdle()
        assertThat(vm.uiState.value.tuningNote).isNull()

        nightLog.tuningState.value = AlarmTuning(2.5, TuningReason.RESET, SampleData.now.minus(Duration.ofHours(1)), offset)
        advanceUntilIdle()
        assertThat(vm.uiState.value.tuningNote).isNull()
    }

    @Test
    fun `no planned alarm means no landing`() = runTest {
        nightLog.tuningState.value = PlanSamples.tuning()
        val vm = viewModel()
        advanceUntilIdle()

        assertThat(vm.uiState.value.landing).isNull()
        assertThat(vm.uiState.value.tuningNote).isNull()
    }

    // ---------------------------------------------------------------- Marks

    @Test
    fun `marks toggle on and off for the night`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onMarkToggle(Mark.ALCOHOL)
        advanceUntilIdle()
        assertThat(vm.uiState.value.marks).containsExactly(Mark.ALCOHOL)
        vm.onMarkToggle(Mark.ALCOHOL)
        advanceUntilIdle()

        assertThat(journal.marksSet).containsExactly(Triple(today, Mark.ALCOHOL, true), Triple(today, Mark.ALCOHOL, false)).inOrder()
        assertThat(vm.uiState.value.marks).isEmpty()
    }

    @Test
    fun `derived marks and old nights can't be changed`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onMarkToggle(Mark.ANCHOR_DROPPED)

        val old = viewModel(today.minusDays(9))
        advanceUntilIdle()
        assertThat(old.uiState.value.marksEditable).isFalse()
        old.onMarkToggle(Mark.STRESS)
        advanceUntilIdle()

        assertThat(journal.marksSet).isEmpty()
    }

    @Test
    fun `failing journal reads leave the review working`() = runTest {
        val vm = MorningReviewViewModel(
            SavedStateHandle(mapOf("wakeDate" to today.toString())),
            sleep, insights, settings, sync, reviews, notifier, time, healthConnect,
            object : JournalRepository by journal {
                override fun feel(wakeDate: LocalDate) = flow<MorningFeelEntry?> { error("disk") }
                override fun tomorrowList(nightOf: LocalDate) = flow<TomorrowList?> { error("disk") }
            },
            nightLog, patterns,
        )
        advanceUntilIdle()

        assertThat(vm.step).isInstanceOf(ReviewStep.Summary::class.java)
        assertThat(vm.uiState.value.feel).isNull()
        assertThat(vm.uiState.value.tomorrowList).isNull()
    }

    @Test
    fun `a landing that never arrives doesn't hold up the review`() = runTest {
        val vm = MorningReviewViewModel(
            SavedStateHandle(mapOf("wakeDate" to today.toString())),
            sleep, insights, settings, sync, reviews, notifier, time, healthConnect, journal,
            object : NightLogRepository by nightLog {
                override fun landing(wakeDate: LocalDate) = flow<WakeLanding?> { awaitCancellation() }
            },
            patterns,
        )
        advanceUntilIdle()

        assertThat(vm.step).isInstanceOf(ReviewStep.Summary::class.java)
        assertThat(vm.uiState.value.landing).isNull()
    }
}
