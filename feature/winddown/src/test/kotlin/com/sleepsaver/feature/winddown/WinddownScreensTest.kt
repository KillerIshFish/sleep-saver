package com.sleepsaver.feature.winddown

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.hooks.NightState
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.nightsteps.GuidedTool
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.testing.FakeHabitsRepository
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.PlanSamples.NightVariant
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.core.ui.testing.PHONE_PORTRAIT_DARK
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.feature.winddown.editor.RoutineEditorActions
import com.sleepsaver.feature.winddown.editor.RoutineEditorContent
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
import com.sleepsaver.feature.winddown.tools.BodyScanActions
import com.sleepsaver.feature.winddown.tools.BodyScanContent
import com.sleepsaver.feature.winddown.tools.BreathPattern
import com.sleepsaver.feature.winddown.tools.BreathPhase
import com.sleepsaver.feature.winddown.tools.RunPosition
import com.sleepsaver.feature.winddown.tools.SlowTideActions
import com.sleepsaver.feature.winddown.tools.SlowTideContent
import com.sleepsaver.feature.winddown.tools.SlowTideSetup
import com.sleepsaver.feature.winddown.tools.TomorrowListContent
import com.sleepsaver.feature.winddown.tools.TomorrowListUiState
import com.sleepsaver.feature.winddown.tools.ToolStage
import com.sleepsaver.feature.winddown.tools.slowTideUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Duration

/** Interactions on the stateless screens (no Hilt): the callbacks fire with the right arguments. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_PORTRAIT_DARK)
class WinddownScreensTest {

    @get:Rule
    val compose = createComposeRule()

    // ---------------------------------------------------------------- Night steps

    @Test
    fun `steps check off, guided ones start, and a suggestion is added with its title`() {
        val toggled = mutableListOf<Pair<Long, Boolean>>()
        val started = mutableListOf<GuidedTool>()
        val added = mutableListOf<Pair<Technique, String>>()
        compose.setContent {
            SleepSaverTheme {
                NightStepsContent(
                    state = WinddownSamples.nightSteps(),
                    actions = NightStepsActions(
                        onToggleStep = { id, done -> toggled += id to done },
                        onStartTool = { started += it },
                        onAddSuggestion = { technique, label -> added += technique to label },
                    ),
                )
            }
        }

        compose.onNodeWithText("Herbal tea").performScrollTo().performClick()
        compose.onNodeWithText("Screens down").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Start Slow tide breathing").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Add Slow tide breathing").performScrollTo().performClick()

        assertThat(toggled).containsExactly(10L to true, 1L to false).inOrder()
        assertThat(started).containsExactly(GuidedTool.SLOW_TIDE)
        assertThat(added).containsExactly(Technique.SLOW_TIDE to "Slow tide breathing")
    }

    @Test
    fun `changing steps, the library and marks reach their actions`() {
        val calls = mutableListOf<String>()
        compose.setContent {
            SleepSaverTheme {
                NightStepsContent(
                    state = WinddownSamples.nightSteps(),
                    actions = NightStepsActions(
                        onChangeSteps = { calls += "change" },
                        onOpenLibrary = { calls += "library" },
                        onToggleMark = { calls += "mark $it" },
                    ),
                )
            }
        }

        compose.onNodeWithText("Change tonight's steps").performScrollTo().performClick()
        compose.onNodeWithText("See the library").performScrollTo().performClick()
        compose.onNodeWithText("Stress").performScrollTo().performClick()

        assertThat(calls).containsExactly("change", "library", "mark ${Mark.STRESS}").inOrder()
    }

    @Test
    fun `Drop anchor opens the settling-in sheet, with the movement log off unless switched on`() {
        val dropped = mutableListOf<Boolean>()
        compose.setContent {
            SleepSaverTheme {
                NightStepsContent(state = WinddownSamples.nightSteps(), actions = NightStepsActions(onDropAnchor = { dropped += it }))
            }
        }

        compose.onNodeWithText("Drop anchor").performScrollTo().performClick()
        compose.onNodeWithText("Settling in").assertIsDisplayed()
        compose.onNodeWithText("Log movement all night").performClick()
        compose.onAllNodesWithText("Drop anchor").assertCountEquals(2)
        compose.onAllNodesWithText("Drop anchor")[1].performClick()

        assertThat(dropped).containsExactly(true)
    }

    @Test
    fun `a Hush step opens Hush inline`() {
        compose.setContent {
            SleepSaverTheme { NightStepsContent(state = WinddownSamples.nightSteps(), actions = NightStepsActions()) }
        }

        compose.onNodeWithContentDescription("Start Hush").performScrollTo().performClick()

        compose.onNodeWithText("Until my window").assertIsDisplayed()
    }

    @Test
    fun `at night the screen shows no time of day`() {
        compose.setContent {
            SleepSaverTheme {
                NightStepsContent(
                    state = WinddownSamples.nightSteps(nightState = PlanSamples.nightState(NightVariant.HUSH_PLAYING)),
                    actions = NightStepsActions(),
                )
            }
        }

        compose.onNodeWithText("Bedtime soon").assertIsDisplayed()
        compose.onNodeWithText("Anchor dropped").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(showsClockTime, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `an evening with steps off points to the week plan`() {
        var opened = 0
        compose.setContent {
            SleepSaverTheme {
                NightStepsContent(
                    state = WinddownSamples.nightSteps(session = richSession(enabled = false)),
                    actions = NightStepsActions(onOpenWeekPlan = { opened++ }),
                )
            }
        }

        compose.onNodeWithText("Turn them on for Tuesday evenings in the week plan.").assertIsDisplayed()
        compose.onNodeWithText("Week plan").performClick()

        assertThat(opened).isEqualTo(1)
    }

    // ---------------------------------------------------------------- Library

    @Test
    fun `library Add passes the title resolved in composition, and guided tools can be tried`() {
        val added = mutableListOf<Pair<Technique, String>>()
        val tried = mutableListOf<GuidedTool>()
        compose.setContent {
            SleepSaverTheme {
                StepLibraryContent(
                    state = stepLibraryUiState(WinddownSamples.suggestions, activities = emptyList()),
                    actions = StepLibraryActions(onAdd = { technique, label -> added += technique to label }, onTry = { tried += it }),
                )
            }
        }

        compose.onAllNodesWithContentDescription("Add Slow tide breathing")[0]
            .performScrollTo()
            .performClick()
        compose.onAllNodesWithText("Try now")[0].performScrollTo().performClick()

        assertThat(added).containsExactly(Technique.SLOW_TIDE to "Slow tide breathing")
        assertThat(tried).containsExactly(GuidedTool.SLOW_TIDE)
    }

    @Test
    fun `why it helps opens on tap and techniques already in the steps can't be added again`() {
        compose.setContent {
            SleepSaverTheme {
                StepLibraryContent(
                    state = stepLibraryUiState(emptyList(), PlanSamples.activities()),
                    actions = StepLibraryActions(),
                )
            }
        }

        compose.onAllNodesWithText("In your steps")[0].performScrollTo().assertIsNotEnabled()
        compose.onAllNodesWithText("Why it helps")[0].performScrollTo().performClick()
        compose.onNodeWithText("Slow breathing with a longer out-breath can help you feel calmer. People report better sleep after a few weeks of practice.")
            .assertIsDisplayed()
    }

    // ---------------------------------------------------------------- Routine editor

    @Test
    fun `the editor toggles, removes after confirming, and opens the library`() {
        val calls = mutableListOf<String>()
        // Built outside composition (lint: no StateFlow.value read while composing).
        val state = routineEditorUiState(null, WeekPlan.DEFAULT, richActivities(), FakeHabitsRepository().settingsState.value)
        compose.setContent {
            SleepSaverTheme {
                RoutineEditorContent(
                    state = state,
                    actions = RoutineEditorActions(
                        onIncludedChange = { id, on -> calls += "include $id $on" },
                        onRemove = { calls += "remove $it" },
                        onAddFromLibrary = { calls += "library" },
                        onReminderChange = { calls += "reminder $it" },
                    ),
                )
            }
        }

        compose.onNodeWithText("Read on paper").performScrollTo().performClick()
        compose.onNodeWithText("Remove").performScrollTo().performClick()
        compose.onNodeWithText("Remove Herbal tea?").assertIsDisplayed()
        compose.onNodeWithText("Its past check-offs stay in your history.").assertIsDisplayed()
        compose.onAllNodesWithText("Remove")[1].performClick()
        compose.onNodeWithText("Add from the library").performScrollTo().performClick()
        compose.onNodeWithText("Reminder when Night steps start").performScrollTo().performClick()

        assertThat(calls).containsExactly("include 4 true", "remove 10", "library", "reminder false").inOrder()
    }

    @Test
    fun `a day editor offers the every-evening list`() {
        val same = mutableListOf<Boolean>()
        compose.setContent {
            SleepSaverTheme {
                RoutineEditorContent(
                    state = routineEditorUiState(DayOfWeek.TUESDAY, WeekPlan.DEFAULT, richActivities(), SampleData.habits),
                    actions = RoutineEditorActions(onSameChange = { same += it }),
                )
            }
        }

        compose.onNodeWithText("Tuesday evening").assertIsDisplayed()
        compose.onNodeWithText("Same as every evening").performClick()

        assertThat(same).containsExactly(false)
    }

    // ---------------------------------------------------------------- Tools

    @Test
    fun `slow tide's start screen picks a pace, a length and starts`() {
        val calls = mutableListOf<String>()
        val config = SleepConfig.DEFAULT
        compose.setContent {
            SleepSaverTheme {
                SlowTideContent(
                    state = slowTideUiState(SlowTideSetup(BreathPattern(Duration.ofSeconds(4), Duration.ofSeconds(6)), config.steps.slowTideDefaultLength), ToolStage.Setup),
                    actions = SlowTideActions(
                        onPattern = { calls += "pace ${it.breatheIn.seconds}/${it.breatheOut.seconds}" },
                        onLength = { calls += "length ${it.toMinutes()}" },
                        onEyesClosed = { calls += "eyes $it" },
                        onStart = { calls += "start" },
                    ),
                )
            }
        }

        compose.onNodeWithText("5 in · 5 out").performClick()
        compose.onNodeWithText("15 min").performClick()
        compose.onNodeWithText("Eyes closed: vibration only").performScrollTo().performClick()
        compose.onNodeWithText("Start").performScrollTo().performClick()

        assertThat(calls).containsExactly("pace 5/5", "length 15", "eyes true", "start").inOrder()
    }

    @Test
    fun `a running tool keeps the screen on, its start and end screens don't`() {
        val setup = SlowTideSetup(BreathPattern(Duration.ofSeconds(4), Duration.ofSeconds(6)), SleepConfig.DEFAULT.steps.slowTideDefaultLength)
        var stage by mutableStateOf<ToolStage<BreathPhase>>(ToolStage.Setup)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            SleepSaverTheme { SlowTideContent(state = slowTideUiState(setup, stage), actions = SlowTideActions(), breathProgress = 0.5f) }
        }

        compose.runOnIdle { assertThat(view.keepScreenOn).isFalse() }
        stage = ToolStage.Running(RunPosition(index = 0, count = 120, cue = BreathPhase.IN, length = Duration.ofSeconds(4)))
        compose.runOnIdle { assertThat(view.keepScreenOn).isTrue() }
        stage = ToolStage.Finished(checkedOff = false)
        compose.runOnIdle { assertThat(view.keepScreenOn).isFalse() }
    }

    @Test
    fun `a paused body scan lets the screen time out`() {
        val area3 = RunPosition(index = 2, count = 10, cue = 2, length = Duration.ofSeconds(60))
        var stage by mutableStateOf<ToolStage<Int>>(ToolStage.Running(area3))
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            SleepSaverTheme { BodyScanContent(stage = stage, actions = BodyScanActions()) }
        }

        compose.runOnIdle { assertThat(view.keepScreenOn).isTrue() }
        stage = ToolStage.Running(area3.copy(paused = true))
        compose.runOnIdle { assertThat(view.keepScreenOn).isFalse() }
        stage = ToolStage.Running(area3)
        compose.runOnIdle { assertThat(view.keepScreenOn).isTrue() }
    }

    @Test
    fun `tomorrow list saves what was typed`() {
        val saved = mutableListOf<String>()
        compose.setContent {
            SleepSaverTheme {
                TomorrowListContent(
                    state = TomorrowListUiState.Ready(PlanSamples.today, ""),
                    onDone = { saved += it },
                    onClose = {},
                )
            }
        }

        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).performTextReplacement("Call Sam\nBuy milk")
        compose.onNodeWithText("Done").performScrollTo().performClick()

        assertThat(saved).containsExactly("Call Sam\nBuy milk")
    }

    @Test
    fun `tomorrow list keeps what was typed after Done when the save comes back`() {
        var state by mutableStateOf<TomorrowListUiState>(TomorrowListUiState.Ready(PlanSamples.today, ""))
        compose.setContent { SleepSaverTheme { TomorrowListContent(state = state, onDone = {}, onClose = {}) } }
        val field = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))

        field.performTextReplacement("Call Sam")
        compose.onNodeWithText("Done").performScrollTo().performClick()
        field.performTextReplacement("Call Sam\nBuy milk")
        state = TomorrowListUiState.Ready(PlanSamples.today, "Call Sam", saved = true)
        compose.waitForIdle()

        assertThat(field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text).isEqualTo("Call Sam\nBuy milk")
        // Changed since the save: Done again, not Close.
        compose.onNodeWithText("Done").assertExists()
    }

    @Test
    fun `night reset skips the breathing, then goes back to the night without showing a time`() {
        val calls = mutableListOf<String>()
        var stage by mutableStateOf(ResetStage.BREATHING)
        val actions = NightResetActions(
            onClose = { calls += "close" },
            onSkip = {
                calls += "skip"
                stage = ResetStage.TEXT
            },
        )
        compose.setContent {
            SleepSaverTheme {
                NightResetContent(state = nightResetUiState(stage, eyesClosed = true, breath = null, NightState(), null, HushChoice()), actions = actions)
            }
        }

        compose.onNodeWithText("Let's slow down first. Follow the gentle pulses.").assertIsDisplayed()
        compose.onNodeWithText("Skip").performClick()
        compose.onNodeWithText("Back to night").performScrollTo().performClick()

        compose.onAllNodes(showsClockTime, useUnmergedTree = true).assertCountEquals(0)
        assertThat(calls).containsExactly("skip", "close").inOrder()
    }

    private companion object {
        val clock = Regex("""\d{1,2}:\d{2}""")

        /** Any text or description with a time of day in it ("9:45 PM", "21:45"). */
        val showsClockTime = SemanticsMatcher("shows a clock time") { node ->
            val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            texts.any { clock.containsMatchIn(it) }
        }
    }
}
