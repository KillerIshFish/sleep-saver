package com.sleepsaver.feature.habits

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasScrollAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.testing.FakeHabitsRepository
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.core.ui.testing.PHONE_PORTRAIT_DARK
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Interactions on the stateless screens (no Hilt): the callbacks fire with the right arguments. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_PORTRAIT_DARK)
class HabitsScreensTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `list rows open the habit and their switches toggle it`() {
        val opened = mutableListOf<HabitType>()
        val toggled = mutableListOf<Pair<HabitType, Boolean>>()
        val reminders = mutableListOf<Pair<HabitType, Boolean>>()
        compose.setContent {
            SleepSaverTheme {
                HabitsContent(
                    state = habitsUiState(SampleData.habits, SampleData.readyState()),
                    onBack = {},
                    onOpenHabit = { opened += it },
                    onToggleHabit = { type, on -> toggled += type to on },
                    onToggleReminder = { type, on -> reminders += type to on },
                )
            }
        }

        compose.onNodeWithText("Caffeine cutoff").performClick()
        compose.onNodeWithContentDescription("Show Caffeine cutoff in my schedule").performClick()
        compose.onNodeWithContentDescription("Remind me about Caffeine cutoff").performClick()

        assertThat(opened).containsExactly(HabitType.CAFFEINE_CUTOFF)
        assertThat(toggled).containsExactly(HabitType.CAFFEINE_CUTOFF to false)
        assertThat(reminders).containsExactly(HabitType.CAFFEINE_CUTOFF to false)
    }

    @Test
    fun `a habit that is off can't have its reminder toggled`() {
        compose.setContent {
            SleepSaverTheme {
                HabitsContent(
                    state = habitsUiState(SampleData.habits, SampleData.readyState()),
                    onBack = {},
                    onOpenHabit = {},
                    onToggleHabit = { _, _ -> },
                    onToggleReminder = { _, _ -> },
                )
            }
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Hard workout cutoff"))
        compose.onNodeWithContentDescription("Remind me about Hard workout cutoff").assertIsNotEnabled()
    }

    @Test
    fun `detail reminder row is disabled while the habit is off`() {
        compose.setContent {
            SleepSaverTheme {
                HabitDetailContent(
                    state = habitDetailUiState(HabitType.NAP_WINDOW, SampleData.habits, emptyList(), SampleData.readyState()),
                    onBack = {},
                    onToggleHabit = {},
                    onToggleReminder = {},
                    onTimingChange = {},
                    onResetTiming = {},
                    onChooseActivities = {},
                )
            }
        }

        compose.onNodeWithText("Turn on the habit to get reminders").assertIsDisplayed()
        compose.onNodeWithText("Show in my schedule").assertIsEnabled().assertIsOff()
    }

    @Test
    fun `long-pressing a custom tile asks before removing it`() {
        val deleted = mutableListOf<Long>()
        compose.setContent {
            SleepSaverTheme {
                EveningRoutineContent(
                    state = EveningRoutineUiState.Ready(ActivitiesWithCustom, routineEnabled = true),
                    onClose = {},
                    onToggle = { _, _ -> },
                    onAddCustom = { _, _ -> },
                    onDelete = { deleted += it },
                    onAddToSchedule = {},
                )
            }
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Herbal tea"))
        compose.onNodeWithText("Herbal tea").performTouchInput { longClick() }
        compose.onNodeWithText("Remove “Herbal tea”?").assertIsDisplayed()
        compose.onNodeWithText("Remove").performClick()

        assertThat(deleted).containsExactly(8L)
    }

    @Test
    fun `tapping a tile toggles it and the custom tile opens the form`() {
        val toggled = mutableListOf<Pair<Long, Boolean>>()
        val added = mutableListOf<Pair<String, String>>()
        compose.setContent {
            SleepSaverTheme {
                EveningRoutineContent(
                    state = EveningRoutineUiState.Ready(FakeHabitsRepository.DEFAULT_ACTIVITIES, routineEnabled = false),
                    onClose = {},
                    onToggle = { id, selected -> toggled += id to selected },
                    onAddCustom = { label, emoji -> added += label to emoji },
                    onDelete = {},
                    onAddToSchedule = {},
                )
            }
        }

        compose.onNodeWithText("Stretching").performClick()
        compose.onNodeWithText("Reading").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Custom"))
        // The form focuses its text field, and a blinking cursor never lets the clock settle: step it by hand.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Custom").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Add your own").assertIsDisplayed()
        compose.onNodeWithText("Add").assertIsNotEnabled()

        assertThat(toggled).containsExactly(2L to true, 1L to false).inOrder()
        assertThat(added).isEmpty()
    }

    @Test
    fun `the custom form adds the label with the picked emoji`() {
        val added = mutableListOf<Pair<String, String>>()
        var cancelled = false
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SleepSaverTheme {
                CustomActivityForm(
                    onCancel = { cancelled = true },
                    onAdd = { label, emoji -> added += label to emoji },
                    initialLabel = "Herbal tea",
                    autoFocus = false,
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)

        compose.onNodeWithText("🍵").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Add").assertIsEnabled().performClick()

        assertThat(added).containsExactly("Herbal tea" to "🍵")
        assertThat(cancelled).isFalse()
    }

    @Test
    fun `nothing picked means nothing to add`() {
        compose.setContent {
            SleepSaverTheme {
                EveningRoutineContent(
                    state = EveningRoutineUiState.Ready(
                        FakeHabitsRepository.DEFAULT_ACTIVITIES.map { it.copy(selected = false) },
                        routineEnabled = false,
                    ),
                    onClose = {},
                    onToggle = { _, _ -> },
                    onAddCustom = { _, _ -> },
                    onDelete = {},
                    onAddToSchedule = {},
                )
            }
        }

        compose.onNodeWithText("Add to schedule").assertIsNotEnabled()
    }
}
