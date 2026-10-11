package com.sleepsaver.feature.review

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.sleepsaver.core.data.repository.MovementTrace
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.domain.alarm.AlarmEvent
import com.sleepsaver.core.domain.alarm.MovementEpoch
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.alarm.WakeLanding
import com.sleepsaver.core.domain.alarm.WakeLandingCalculator
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.debt.SleepDebtCalculator
import com.sleepsaver.core.domain.journal.FeelSource
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.journal.MorningFeelEntry
import com.sleepsaver.core.domain.journal.TomorrowList
import com.sleepsaver.core.domain.model.ManualNap
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.NightOverride
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SleepStage
import com.sleepsaver.core.domain.model.SleepStageType
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.night.NightAssembler
import com.sleepsaver.core.testing.PlanSamples
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
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

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

    // ---------------------------------------------------------------- v2: Morning feel, odd night, list, landing, marks

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Instant =
        ZonedDateTime.of(date, LocalTime.of(hour, minute), zone).toInstant()

    private val offset get() = zone.rules.getOffset(SampleData.now)

    private fun summaryState(night: Night = lastNight) = review(ReviewStep.Summary(night, source(night)))

    private fun resultStep(nights: List<Night>): ReviewStep.Result {
        val calculator = SleepDebtCalculator()
        val breakdown = calculator.breakdown(today, nights.associateBy { it.wakeDate }, Duration.ofHours(8))
        return ReviewStep.Result(DebtUpdate(breakdown, calculator.status(breakdown.before), calculator.status(breakdown.after)))
    }

    /**
     * SampleData's nights with last night replaced by 23:05 → 06:40, whose watch stages put [ringStage] at the 06:27
     * ring (06:14–06:28), awake after it, a doze through the snooze and awake again at the stop ([stages] = false: a
     * night without any stages).
     */
    private fun landingNights(ringStage: SleepStageType = SleepStageType.LIGHT, stages: Boolean = true): List<Night> {
        val start = at(today.minusDays(1), 23, 5)
        val plan = listOf(
            SleepStageType.LIGHT to "23:30", SleepStageType.DEEP to "00:15", SleepStageType.LIGHT to "00:45",
            SleepStageType.REM to "01:05", SleepStageType.LIGHT to "01:35", SleepStageType.DEEP to "02:05",
            SleepStageType.LIGHT to "02:30", SleepStageType.REM to "02:55", SleepStageType.AWAKE to "03:01",
            SleepStageType.LIGHT to "03:35", SleepStageType.REM to "04:05", SleepStageType.LIGHT to "04:40",
            SleepStageType.REM to "05:10", SleepStageType.LIGHT to "05:35", SleepStageType.REM to "06:00",
            SleepStageType.LIGHT to "06:14", ringStage to "06:28", SleepStageType.AWAKE to "06:31",
            SleepStageType.LIGHT to "06:37", SleepStageType.AWAKE to "06:40",
        )
        var t = start
        val watch = plan.map { (type, until) ->
            val time = LocalTime.parse(until)
            val next = at(if (time.hour >= 12) today.minusDays(1) else today, time.hour, time.minute)
            SleepStage(t, next, type).also { t = next }
        }
        val end = t
        val session = RawSleepSession(
            id = "landing",
            origin = SleepConfig.GARMIN_CONNECT_PACKAGE,
            start = start,
            end = end,
            startOffset = zone.rules.getOffset(start),
            endOffset = zone.rules.getOffset(end),
            stages = if (stages) watch else emptyList(),
            lastModified = end,
        )
        return NightAssembler().assemble(
            (SampleData.sessions().filterNot { it.id == "sample:0" } + session).sortedBy { it.start },
            SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE),
        )
    }

    /** This morning's 06:45 alarm (window 06:15–06:45): rang at 06:27 at a restless moment, one snooze, stopped 06:38. */
    private fun landingEvent(reason: RingReason): AlarmEvent {
        val event = PlanSamples.alarmEvent(reason, today)
        return if (reason == RingReason.RESTLESS) event.copy(snoozeTimes = listOf(at(today, 6, 28)), stoppedAt = at(today, 6, 38)) else event
    }

    private fun land(nights: List<Night>, reason: RingReason = RingReason.RESTLESS): WakeLanding =
        requireNotNull(WakeLandingCalculator().land(landingEvent(reason), nights.single { it.wakeDate == today }))

    private fun landingState(nights: List<Night>, landing: WakeLanding, tuned: Boolean = false) = MorningReviewUiState(
        wakeDate = today,
        today = today,
        step = resultStep(nights),
        landing = landing,
        tuningNote = if (tuned) PlanSamples.tuning() else null,
        night = nights.single { it.wakeDate == today },
        marks = setOf(Mark.LATE_CAFFEINE, Mark.STRESS),
    )

    /** The phone's movement around the window: a quiet calibration, then a stir just before the 06:27 ring. */
    private fun movement(): List<MovementTrace> {
        val start = at(today, 6, 0)
        val epochs = (0 until 56).map { i ->
            val minute = i / 2
            val activity = when (minute) {
                in 24..26 -> 45.0 + (i % 3) * 25.0
                in 27..28 -> 120.0 - (i % 2) * 30.0
                else -> 3.0 + (i * 7 % 5) * 1.5
            }
            MovementEpoch(start.plusSeconds(30L * i), activity)
        }
        return listOf(MovementTrace(logId = 1, mode = "WINDOW", start = start, end = at(today, 6, 28), epochs = epochs))
    }

    /** Captures [content] scrolled so the node showing [text] sits just under the top of the page. */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureScrolledTo(name: String, text: String, content: @Composable () -> Unit) {
        captureScreen(name, content)
        composeRule.onNodeWithText(text, useUnmergedTree = true).performScrollTo()
        composeRule.mainClock.advanceTimeBy(SCROLL_SETTLE_MS)
        composeRule.waitForIdle()
        val scroller = composeRule.onNode(hasScrollAction())
        val viewportTop = scroller.fetchSemanticsNode().boundsInRoot.top
        val targetTop = composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        val margin = with(composeRule.density) { 12.dp.toPx() }
        scroller.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, targetTop - viewportTop - margin) }
        composeRule.mainClock.advanceTimeBy(SCROLL_SETTLE_MS)
        composeRule.waitForIdle()
        captureScreenRoboImage(File(System.getProperty("roborazzi.output.dir") ?: "build/outputs/roborazzi", "$name.png").path)
    }

    @Test
    fun reviewFeel() = captureScreen("review_feel") {
        MorningReviewContent(summaryState(), MorningReviewActions.Noop)
    }

    @Test
    fun reviewFeelAnswered() = captureScreen("review_feel_answered") {
        val feel = MorningFeelEntry(today, MorningFeel.CLEAR, SampleData.now, offset, FeelSource.RING)
        MorningReviewContent(summaryState().copy(feel = feel), MorningReviewActions.Noop)
    }

    @Test
    fun reviewOddNight() = captureScreen("review_odd_night") {
        val feel = MorningFeelEntry(today, MorningFeel.SLOW, SampleData.now, offset, FeelSource.RING)
        MorningReviewContent(
            summaryState().copy(feel = feel, oddNight = PlanSamples.oddNight(today), marks = setOf(Mark.LATE_MEAL)),
            MorningReviewActions.Noop,
        )
    }

    @Test
    fun reviewTomorrowList() = captureScreen("review_tomorrow_list") {
        val feel = MorningFeelEntry(today, MorningFeel.OKAY, SampleData.now, offset, FeelSource.RING)
        val text = listOf("- Call Sam about the boiler", "- Send the slides before 10", "- Pick up the parcel", "- Book the dentist")
            .joinToString("\n")
        val list = TomorrowList(today.minusDays(1), text, SampleData.now, offset)
        MorningReviewContent(summaryState().copy(feel = feel, tomorrowList = list), MorningReviewActions.Noop)
    }

    @Test
    fun reviewLandingLight() {
        val nights = landingNights(SleepStageType.LIGHT)
        captureScrolledTo("review_landing_light", "Wake landing") {
            MorningReviewContent(landingState(nights, land(nights)), MorningReviewActions.Noop, animateResult = false)
        }
    }

    @Test
    fun reviewLandingDeep() {
        val nights = landingNights(SleepStageType.DEEP)
        captureScrolledTo("review_landing_deep", "Wake landing") {
            MorningReviewContent(landingState(nights, land(nights)), MorningReviewActions.Noop, animateResult = false)
        }
    }

    @Test
    fun reviewLandingNoStages() {
        val nights = landingNights(stages = false)
        captureScrolledTo("review_landing_no_stages", "Wake landing") {
            MorningReviewContent(
                landingState(nights, land(nights, RingReason.PLACEMENT_FALLBACK)),
                MorningReviewActions.Noop,
                animateResult = false,
            )
        }
    }

    @Test
    fun reviewLandingTuned() {
        val nights = landingNights(SleepStageType.DEEP)
        captureScrolledTo("review_landing_tuned", "Wake landing") {
            MorningReviewContent(landingState(nights, land(nights), tuned = true), MorningReviewActions.Noop, animateResult = false)
        }
    }

    @Test
    fun reviewMarks() {
        val nights = landingNights(SleepStageType.LIGHT)
        captureScrolledTo("review_marks", "Last night’s marks") {
            MorningReviewContent(landingState(nights, land(nights)), MorningReviewActions.Noop, animateResult = false)
        }
    }

    @Test
    fun reviewLargeFont() = captureScreen("review_large_font") {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
            MorningReviewContent(summaryState().copy(oddNight = PlanSamples.oddNight(today)), MorningReviewActions.Noop)
        }
    }

    @Test
    fun reviewLandingLargeFont() {
        val nights = landingNights(SleepStageType.LIGHT)
        captureScrolledTo("review_landing_large_font", "Wake landing") {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                MorningReviewContent(landingState(nights, land(nights), tuned = true), MorningReviewActions.Noop, animateResult = false)
            }
        }
    }

    @Test
    fun review24h() {
        val nights = landingNights(SleepStageType.LIGHT)
        captureScrolledTo("review_24h", "Wake landing") {
            CompositionLocalProvider(LocalUse24HourClock provides true) {
                MorningReviewContent(landingState(nights, land(nights)), MorningReviewActions.Noop, animateResult = false)
            }
        }
    }

    // ---------------------------------------------------------------- v2: Night detail

    private fun detailState(nights: List<Night>, landing: WakeLanding?): NightDetailUiState {
        val night = nights.single { it.wakeDate == today }
        return NightDetailUiState(
            wakeDate = today,
            today = today,
            loading = false,
            night = night,
            source = source(night),
            marks = setOf(Mark.ALCOHOL, Mark.LATE_MEAL),
            feel = MorningFeel.CLEAR,
            feelEditable = true,
            anchor = PlanSamples.anchorStats(today),
            landing = landing,
            movement = movement(),
        )
    }

    @Test
    fun nightDetailMarksAnchor() {
        val nights = landingNights(SleepStageType.LIGHT)
        captureScrolledTo("night_detail_marks_anchor", "Marks") {
            NightDetailContent(detailState(nights, land(nights)), onBack = {}, onAdjust = {}, onDeleteNap = {}, onRevert = {})
        }
    }

    @Test
    fun nightDetailLanding() {
        val nights = landingNights(SleepStageType.LIGHT)
        captureScrolledTo("night_detail_landing", "Wake landing") {
            NightDetailContent(detailState(nights, land(nights)), onBack = {}, onAdjust = {}, onDeleteNap = {}, onRevert = {})
        }
    }
}

/** Long enough for an animated scroll to finish before the capture. */
private const val SCROLL_SETTLE_MS = 2_000L
