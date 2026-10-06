package com.sleepsaver.feature.review

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.debt.SleepDebtCalculator
import com.sleepsaver.core.domain.model.ManualNap
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.NightOverride
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.night.NightAssembler
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.core.ui.format.LocalUse24HourClock
import com.sleepsaver.core.ui.testing.ScreenshotTest
import com.sleepsaver.core.ui.testing.captureComponent
import com.sleepsaver.core.ui.testing.captureScreen
import com.sleepsaver.feature.review.common.HowWeMeasureContent
import com.sleepsaver.feature.review.common.NightSource
import com.sleepsaver.feature.review.detail.NightDetailContent
import com.sleepsaver.feature.review.detail.NightDetailUiState
import com.sleepsaver.feature.review.edit.EditNightContent
import com.sleepsaver.feature.review.edit.EditNightUiState
import com.sleepsaver.feature.review.edit.NightEditorCallbacks
import com.sleepsaver.feature.review.edit.NightEditorState
import com.sleepsaver.feature.review.morning.DebtUpdate
import com.sleepsaver.feature.review.morning.MorningReviewActions
import com.sleepsaver.feature.review.morning.MorningReviewContent
import com.sleepsaver.feature.review.morning.MorningReviewUiState
import com.sleepsaver.feature.review.morning.ReviewStep
import com.sleepsaver.feature.review.nap.AddNapContent
import com.sleepsaver.feature.review.nap.AddNapUiState
import com.sleepsaver.feature.review.nap.NapDay
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ReviewScreenshotTest : ScreenshotTest() {

    private val zone = SampleData.zone
    private val today: LocalDate = SampleData.today
    private val saturday: LocalDate = today.minusDays(2)

    /** Sample nights plus a nap the user added on Sunday afternoon. */
    private val nights: List<Night> by lazy {
        val napStart = LocalDateTime.of(saturday, LocalTime.of(17, 10)).atZone(zone).toInstant()
        NightAssembler().assemble(
            SampleData.sessions(),
            SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE),
            manualNaps = listOf(ManualNap(4, napStart, napStart.plus(Duration.ofMinutes(25)), zone.rules.getOffset(napStart))),
        )
    }
    private val lastNight: Night get() = nights.single { it.wakeDate == today }

    private fun review(step: ReviewStep) = MorningReviewUiState(wakeDate = today, today = today, step = step)

    private fun source(night: Night?) = NightSource.of(night, zone, SampleData.now)

    @Test
    fun reviewLoading() = captureScreen("review_loading") {
        MorningReviewContent(review(ReviewStep.Loading(recalculating = false)), MorningReviewActions.Noop)
    }

    @Test
    fun reviewSummary() = captureScreen("review_summary") {
        MorningReviewContent(review(ReviewStep.Summary(lastNight, source(lastNight))), MorningReviewActions.Noop)
    }

    @Test
    fun reviewSummary24h() = captureScreen("review_summary_24h") {
        CompositionLocalProvider(LocalUse24HourClock provides true) {
            MorningReviewContent(review(ReviewStep.Summary(lastNight, source(lastNight))), MorningReviewActions.Noop)
        }
    }

    @Test
    fun reviewAdjust() = captureScreen("review_adjust") {
        val editor = NightEditorState.from(today, lastNight, Duration.ofHours(8), LocalTime.of(7, 0), zone)
        MorningReviewContent(review(ReviewStep.Adjust(editor)), MorningReviewActions.Noop)
    }

    @Test
    fun reviewRecalculating() = captureScreen("review_recalculating") {
        MorningReviewContent(review(ReviewStep.Loading(recalculating = true)), MorningReviewActions.Noop)
    }

    @Test
    fun reviewResult() = captureScreen("review_result") {
        val calculator = SleepDebtCalculator()
        val breakdown = calculator.breakdown(today, nights.associateBy { it.wakeDate }, Duration.ofHours(8))
        val update = DebtUpdate(breakdown, calculator.status(breakdown.before), calculator.status(breakdown.after))
        MorningReviewContent(review(ReviewStep.Result(update)), MorningReviewActions.Noop, animateResult = false)
    }

    @Test
    fun reviewMissing() = captureScreen("review_missing") {
        MorningReviewContent(review(ReviewStep.Missing()), MorningReviewActions.Noop)
    }

    @Test
    fun reviewMissingPermission() = captureScreen("review_missing_permission") {
        MorningReviewContent(
            review(ReviewStep.Missing(SyncError.PERMISSION_DENIED)),
            MorningReviewActions(
                onClose = {}, onSyncNow = {}, onLooksRight = {}, onAdjust = {}, onAdjustCancel = {}, onAdjustDone = {},
                onDone = {}, onDontShowAgain = {}, editor = NightEditorCallbacks.Noop, onOpenHealthConnect = {},
            ),
        )
    }

    @Test
    fun reviewSummaryLargeFont() = captureScreen("review_summary_large_font") {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
            MorningReviewContent(review(ReviewStep.Summary(lastNight, source(lastNight))), MorningReviewActions.Noop)
        }
    }

    @Test
    fun reviewAdjustLargeFont() = captureScreen("review_adjust_large_font") {
        val editor = NightEditorState.from(today, lastNight, Duration.ofHours(8), LocalTime.of(7, 0), zone)
        CompositionLocalProvider(
            LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
            LocalUse24HourClock provides true,
        ) {
            MorningReviewContent(review(ReviewStep.Adjust(editor)), MorningReviewActions.Noop)
        }
    }

    @Test
    fun nightDetail() = captureScreen("night_detail") {
        val night = nights.single { it.wakeDate == saturday }
        NightDetailContent(
            state = NightDetailUiState(saturday, today, loading = false, night = night, source = source(night)),
            onBack = {}, onAdjust = {}, onDeleteNap = {}, onRevert = {},
        )
    }

    @Test
    fun nightDetailLower() = captureScreen("night_detail_lower") {
        val night = nights.single { it.wakeDate == saturday }
        NightDetailContent(
            state = NightDetailUiState(saturday, today, loading = false, night = night, source = source(night)),
            onBack = {}, onAdjust = {}, onDeleteNap = {}, onRevert = {},
            scrollState = ScrollState(Int.MAX_VALUE),
        )
    }

    @Test
    fun nightDetailEdited() = captureScreen("night_detail_edited") {
        val edited = NightAssembler().assemble(
            SampleData.sessions(),
            SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE),
            overrides = listOf(
                NightOverride(
                    wakeDate = today,
                    wokeUp = lastNight.wokeUp!!.plus(Duration.ofMinutes(20)),
                    offset = lastNight.wokeUpOffset!!,
                    updatedAt = SampleData.now,
                ),
            ),
        ).single { it.wakeDate == today }
        NightDetailContent(
            state = NightDetailUiState(today, today, loading = false, night = edited, source = source(edited)),
            onBack = {}, onAdjust = {}, onDeleteNap = {}, onRevert = {},
            scrollState = ScrollState(Int.MAX_VALUE),
        )
    }

    @Test
    fun nightDetailManual() = captureScreen("night_detail_manual") {
        val date = today.minusDays(40)
        val wake = LocalDateTime.of(date, LocalTime.of(6, 45)).atZone(zone).toInstant()
        val manual = NightAssembler().assemble(
            emptyList(),
            SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE),
            overrides = listOf(
                NightOverride(
                    wakeDate = date,
                    fellAsleep = wake.minus(Duration.ofMinutes(7 * 60 + 20)),
                    wokeUp = wake,
                    awakeMinutes = 20,
                    isManualNight = true,
                    offset = zone.rules.getOffset(wake),
                    updatedAt = SampleData.now,
                ),
            ),
        ).single()
        NightDetailContent(
            state = NightDetailUiState(date, today, loading = false, night = manual, source = source(manual)),
            onBack = {}, onAdjust = {}, onDeleteNap = {}, onRevert = {},
        )
    }

    @Test
    fun nightDetailMissing() = captureScreen("night_detail_missing") {
        NightDetailContent(
            state = NightDetailUiState(today.minusDays(5), today, loading = false, night = null),
            onBack = {}, onAdjust = {}, onDeleteNap = {}, onRevert = {},
        )
    }

    @Test
    fun editNightMissing() = captureScreen("edit_night_missing") {
        val date = today.minusDays(5)
        val editor = NightEditorState.from(date, null, Duration.ofHours(8), LocalTime.of(7, 0), zone)
        EditNightContent(
            state = EditNightUiState(date, today, editor = editor),
            callbacks = NightEditorCallbacks.Noop,
            onCancel = {}, onDone = {}, onRevert = {},
        )
    }

    @Test
    fun editNightLower() = captureScreen("edit_night_lower") {
        val edited = lastNight.copy(
            override = NightOverride(today, awakeMinutes = 30, offset = lastNight.wokeUpOffset!!, updatedAt = SampleData.now),
        )
        val editor = NightEditorState.from(today, edited, Duration.ofHours(8), LocalTime.of(7, 0), zone)
            .withWokeUp(edited.fellAsleep!!)
        EditNightContent(
            state = EditNightUiState(today, today, editor = editor),
            callbacks = NightEditorCallbacks.Noop,
            onCancel = {}, onDone = {}, onRevert = {},
            scrollState = ScrollState(Int.MAX_VALUE),
        )
    }

    @Test
    fun addNap() = captureScreen("add_nap") {
        val sunday = nights.single { it.wakeDate == saturday }
        val now = LocalDateTime.of(saturday, LocalTime.of(18, 5)).atZone(zone).toInstant()
        AddNapContent(
            state = AddNapUiState(
                today = saturday,
                zone = zone,
                now = now,
                day = NapDay.TODAY,
                start = LocalDateTime.of(saturday, LocalTime.of(17, 40)).atZone(zone).toInstant(),
                minutes = 20,
                naps = sunday.naps,
            ),
            onClose = {}, onDayChange = {}, onStartChange = {}, onMinutesChange = {}, onSave = {}, onDeleteNap = {},
        )
    }

    @Test
    fun howWeMeasure() = captureComponent("how_we_measure") {
        HowWeMeasureContent(source = source(lastNight), onGotIt = {}, onChangeSource = {})
    }
}
