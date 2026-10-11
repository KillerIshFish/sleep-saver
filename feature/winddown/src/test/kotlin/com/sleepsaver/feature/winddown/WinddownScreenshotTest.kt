package com.sleepsaver.feature.winddown

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.sleepsaver.core.data.hooks.NightState
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.PlanSamples.NightVariant
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.core.ui.format.LocalUse24HourClock
import com.sleepsaver.core.ui.testing.ScreenshotTest
import com.sleepsaver.feature.winddown.editor.RoutineEditorActions
import com.sleepsaver.feature.winddown.editor.RoutineEditorContent
import com.sleepsaver.feature.winddown.editor.RoutineEditorUiState
import com.sleepsaver.feature.winddown.editor.routineEditorUiState
import com.sleepsaver.feature.winddown.library.StepLibraryActions
import com.sleepsaver.feature.winddown.library.StepLibraryContent
import com.sleepsaver.feature.winddown.library.stepLibraryUiState
import com.sleepsaver.feature.winddown.reset.NightResetActions
import com.sleepsaver.feature.winddown.reset.NightResetContent
import com.sleepsaver.feature.winddown.reset.ResetStage
import com.sleepsaver.feature.winddown.reset.nightResetUiState
import com.sleepsaver.feature.winddown.steps.NightStepsActions
import com.sleepsaver.feature.winddown.steps.NightStepsContent
import com.sleepsaver.feature.winddown.steps.NightStepsSheet
import com.sleepsaver.feature.winddown.steps.NightStepsUiState
import com.sleepsaver.feature.winddown.tools.BodyScanActions
import com.sleepsaver.feature.winddown.tools.BodyScanContent
import com.sleepsaver.feature.winddown.tools.BreathPhase
import com.sleepsaver.feature.winddown.tools.MuscleCue
import com.sleepsaver.feature.winddown.tools.MuscleGroup
import com.sleepsaver.feature.winddown.tools.MuscleReleaseActions
import com.sleepsaver.feature.winddown.tools.MuscleReleaseContent
import com.sleepsaver.feature.winddown.tools.RunPosition
import com.sleepsaver.feature.winddown.tools.SlowTideActions
import com.sleepsaver.feature.winddown.tools.SlowTideContent
import com.sleepsaver.feature.winddown.tools.SlowTideSetup
import com.sleepsaver.feature.winddown.tools.TomorrowListContent
import com.sleepsaver.feature.winddown.tools.TomorrowListUiState
import com.sleepsaver.feature.winddown.tools.ToolStage
import com.sleepsaver.feature.winddown.tools.slowTidePatterns
import com.sleepsaver.feature.winddown.tools.slowTideUiState
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration

class WinddownScreenshotTest : ScreenshotTest() {

    private val config = SleepConfig.DEFAULT

    @Composable
    private fun LargeFont(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f), content = content)
    }

    @Composable
    private fun Clock24(content: @Composable () -> Unit) = CompositionLocalProvider(LocalUse24HourClock provides true, content = content)

    @Composable
    private fun Steps(state: NightStepsUiState, scrolledToEnd: Boolean = false, sheet: NightStepsSheet? = null) = NightStepsContent(
        state = state,
        actions = NightStepsActions(),
        scrollState = rememberScrollState(if (scrolledToEnd) 10_000 else 0),
        sheet = sheet,
    )

    @Composable
    private fun Editor(state: RoutineEditorUiState, removing: Long? = null, scrolledToEnd: Boolean = false) = RoutineEditorContent(
        state = state,
        actions = RoutineEditorActions(),
        removing = removing,
        scrollState = rememberScrollState(if (scrolledToEnd) 10_000 else 0),
    )

    // ---------------------------------------------------------------- Night steps

    @Test
    fun nightSteps() = captureScreen("night_steps") { Steps(WinddownSamples.nightSteps()) }

    @Test
    fun nightStepsScrolled() = captureScreen("night_steps_scrolled") {
        Steps(WinddownSamples.nightSteps(effects = WinddownSamples.effects), scrolledToEnd = true)
    }

    @Test
    fun nightStepsDone() = captureScreen("night_steps_done") {
        Steps(WinddownSamples.nightSteps(session = richSession(done = 6), suggestions = emptyList(), effects = WinddownSamples.effects))
    }

    @Test
    fun nightStepsOff() = captureScreen("night_steps_off") { Steps(WinddownSamples.nightSteps(session = richSession(enabled = false))) }

    @Test
    fun nightStepsEmpty() = captureScreen("night_steps_empty") {
        Steps(WinddownSamples.nightSteps(session = richSession(done = 0, activities = emptyList()), suggestions = emptyList()))
    }

    @Test
    fun nightStepsSettingsOnly() = captureScreen("night_steps_settings_only") {
        Steps(WinddownSamples.nightSteps(session = richSession(done = 0, fromSettingsOnly = true), adherence = PlanSamples.adherence().copy(evenings = emptyList(), doneRecent = 0, countedRecent = 0, quietStreak = 0)))
    }

    @Test
    fun nightStepsLargeFont() = captureScreen("night_steps_large_font") { LargeFont { Steps(WinddownSamples.nightSteps()) } }

    @Test
    fun nightSteps24h() = captureScreen("night_steps_24h") { Clock24 { Steps(WinddownSamples.nightSteps()) } }

    @Test
    fun nightStepsNight() = captureScreen("night_steps_night") {
        Steps(WinddownSamples.nightSteps(nightState = PlanSamples.nightState(NightVariant.ANCHORED)))
    }

    @Test
    fun nightStepsAnchorSheet() = captureScreen("night_steps_anchor_sheet") { Steps(WinddownSamples.nightSteps(), sheet = NightStepsSheet.ANCHOR) }

    @Test
    fun nightStepsHushSheet() = captureScreen("night_steps_hush_sheet") { Steps(WinddownSamples.nightSteps(), sheet = NightStepsSheet.HUSH) }

    // ---------------------------------------------------------------- Routine editor

    @Test
    fun editorEveryEvening() = captureScreen("editor_every_evening") {
        Editor(routineEditorUiState(null, WeekPlan.DEFAULT, richActivities(), SampleData.habits))
    }

    @Test
    fun editorScrolled() = captureScreen("editor_scrolled") {
        Editor(routineEditorUiState(null, WeekPlan.DEFAULT, richActivities(), SampleData.habits), scrolledToEnd = true)
    }

    @Test
    fun editorDayOwn() = captureScreen("editor_day_own") {
        val tuesday = WeekPlan.DEFAULT.day(DayOfWeek.TUESDAY)
        val week = WeekPlan.DEFAULT.with(tuesday.copy(evening = tuesday.evening.copy(routine = listOf(1L, 3L, 10L))))
        val reminderOff = SampleData.habits.map { if (it.type == HabitType.EVENING_ROUTINE) it.copy(reminderEnabled = false) else it }
        Editor(routineEditorUiState(DayOfWeek.TUESDAY, week, richActivities(), reminderOff))
    }

    @Test
    fun editorRemoveDialog() = captureScreen("editor_remove_dialog") {
        Editor(routineEditorUiState(null, WeekPlan.DEFAULT, richActivities(), SampleData.habits), removing = 10L)
    }

    @Test
    fun editorLargeFont() = captureScreen("editor_large_font") {
        LargeFont { Editor(routineEditorUiState(DayOfWeek.TUESDAY, WeekPlan.DEFAULT, richActivities(), SampleData.habits)) }
    }

    // ---------------------------------------------------------------- Library

    private val library = stepLibraryUiState(WinddownSamples.suggestions.drop(1) + PlanSamples.suggestions.take(1), PlanSamples.activities())

    @Test
    fun library() = captureScreen("library") { StepLibraryContent(library, StepLibraryActions()) }

    @Test
    fun libraryExpanded() = captureScreen("library_expanded") {
        StepLibraryContent(library, StepLibraryActions(), expanded = setOf(Technique.COOL_DARK_ROOM))
    }

    @Test
    fun libraryScrolled() = captureScreen("library_scrolled") {
        StepLibraryContent(library, StepLibraryActions(), scrollState = rememberScrollState(10_000))
    }

    @Test
    fun libraryLargeFont() = captureScreen("library_large_font") { LargeFont { StepLibraryContent(library, StepLibraryActions()) } }

    // ---------------------------------------------------------------- Guided tools

    private fun tide(stage: ToolStage<BreathPhase>, eyesClosed: Boolean = false) =
        slowTideUiState(SlowTideSetup(config.slowTidePatterns().first(), config.steps.slowTideDefaultLength, eyesClosed), stage)

    private val breathingIn = ToolStage.Running(RunPosition(index = 4, count = 120, cue = BreathPhase.IN, length = Duration.ofSeconds(4)))

    @Test
    fun toolSlowTide() = captureScreen("tool_slow_tide") { SlowTideContent(tide(breathingIn), SlowTideActions(), breathProgress = 0.6f) }

    @Test
    fun toolSlowTideOut() = captureScreen("tool_slow_tide_out") {
        val out = ToolStage.Running(RunPosition(index = 5, count = 120, cue = BreathPhase.OUT, length = Duration.ofSeconds(6)))
        SlowTideContent(tide(out), SlowTideActions(), breathProgress = 0.8f)
    }

    @Test
    fun toolSlowTideEyesClosed() = captureScreen("tool_slow_tide_eyes_closed") {
        SlowTideContent(tide(breathingIn, eyesClosed = true), SlowTideActions(), breathProgress = 0.6f)
    }

    @Test
    fun toolSlowTideSetup() = captureScreen("tool_slow_tide_setup") { SlowTideContent(tide(ToolStage.Setup), SlowTideActions()) }

    @Test
    fun toolFinished() = captureScreen("tool_finished") { SlowTideContent(tide(ToolStage.Finished(checkedOff = true)), SlowTideActions()) }

    @Test
    fun toolBodyScan() = captureScreen("tool_body_scan") {
        BodyScanContent(ToolStage.Running(RunPosition(index = 2, count = 10, cue = 2, length = config.steps.bodyScanStep)), BodyScanActions())
    }

    @Test
    fun toolBodyScanIntro() = captureScreen("tool_body_scan_intro") { BodyScanContent(ToolStage.Setup, BodyScanActions()) }

    @Test
    fun toolMuscleRelease() = captureScreen("tool_muscle_release") {
        val cue = MuscleCue(MuscleGroup.SHOULDERS, tense = true)
        MuscleReleaseContent(ToolStage.Running(RunPosition(index = 6, count = 24, cue = cue, length = config.steps.muscleTense)), MuscleReleaseActions())
    }

    @Test
    fun toolMuscleReleaseIntro() = captureScreen("tool_muscle_release_intro") { MuscleReleaseContent(ToolStage.Setup, MuscleReleaseActions()) }

    @Test
    fun toolTomorrowList() = captureScreen("tool_tomorrow_list") {
        TomorrowListContent(
            state = TomorrowListUiState.Ready(PlanSamples.today, "Call Sam about the boiler\nBuy oat milk\nPack the gym bag"),
            onDone = {},
            onClose = {},
        )
    }

    @Test
    fun toolTomorrowListParked() = captureScreen("tool_tomorrow_list_parked") {
        TomorrowListContent(
            state = TomorrowListUiState.Ready(PlanSamples.today, "Call Sam about the boiler\nBuy oat milk", saved = true, checkedOff = true),
            onDone = {},
            onClose = {},
        )
    }

    @Test
    fun toolSlowTideLargeFont() = captureScreen("tool_slow_tide_large_font") {
        LargeFont { SlowTideContent(tide(ToolStage.Setup), SlowTideActions()) }
    }

    // ---------------------------------------------------------------- Night reset

    private fun reset(stage: ResetStage, eyesClosed: Boolean = true, nightState: NightState = NightState()) = nightResetUiState(
        stage = stage,
        eyesClosed = eyesClosed,
        breath = RunPosition(index = 3, count = 24, cue = BreathPhase.OUT, length = Duration.ofSeconds(6)),
        nightState = nightState,
        nextAlarm = PlanSamples.plannedAlarm(),
        hush = HushChoice(color = HushColor.BROWN),
    )

    @Test
    fun nightResetBreathing() = captureScreen("night_reset_breathing") { NightResetContent(reset(ResetStage.BREATHING), NightResetActions()) }

    @Test
    fun nightResetBreathingVisual() = captureScreen("night_reset_breathing_visual") {
        NightResetContent(reset(ResetStage.BREATHING, eyesClosed = false), NightResetActions(), breathProgress = 0.4f)
    }

    @Test
    fun nightResetText() = captureScreen("night_reset_text") { NightResetContent(reset(ResetStage.TEXT), NightResetActions()) }

    @Test
    fun nightResetTextScrolled() = captureScreen("night_reset_text_scrolled") {
        NightResetContent(reset(ResetStage.TEXT), NightResetActions(), scrollState = rememberScrollState(10_000))
    }

    @Test
    fun nightResetLargeFont() = captureScreen("night_reset_large_font") {
        LargeFont { NightResetContent(reset(ResetStage.TEXT, nightState = PlanSamples.nightState(NightVariant.HUSH_PLAYING)), NightResetActions()) }
    }
}
