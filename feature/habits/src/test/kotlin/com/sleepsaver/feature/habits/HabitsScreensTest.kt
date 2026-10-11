package com.sleepsaver.feature.habits

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.core.ui.testing.PHONE_PORTRAIT_DARK
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek

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
                    onEditNightSteps = {},
                )
            }
        }

        compose.onNodeWithText("Turn on the habit to get reminders").assertIsDisplayed()
        compose.onNodeWithText("Show in my schedule").assertIsEnabled().assertIsOff()
    }

    @Test
    fun `night steps opens the routine editor`() {
        var edits = 0
        compose.setContent {
            SleepSaverTheme {
                HabitDetailContent(
                    state = habitDetailUiState(HabitType.EVENING_ROUTINE, SampleData.habits, ActivitiesWithCustom, SampleData.readyState()),
                    onBack = {},
                    onToggleHabit = {},
                    onToggleReminder = {},
                    onTimingChange = {},
                    onResetTiming = {},
                    onEditNightSteps = { edits++ },
                )
            }
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Edit Night steps"))
        compose.onNodeWithText("Edit Night steps").performClick()

        assertThat(edits).isEqualTo(1)
        compose.onNodeWithText("from your week plan", substring = true).assertDoesNotExist()
    }

    @Test
    fun `night steps says which evenings the week plan switches off`() {
        val week = weekWithStepsOff(DayOfWeek.SATURDAY, DayOfWeek.FRIDAY)
        compose.setContent {
            SleepSaverTheme {
                HabitDetailContent(
                    state = habitDetailUiState(HabitType.EVENING_ROUTINE, SampleData.habits, ActivitiesWithCustom, SampleData.readyState(), week = week),
                    onBack = {},
                    onToggleHabit = {},
                    onToggleReminder = {},
                    onTimingChange = {},
                    onResetTiming = {},
                    onEditNightSteps = {},
                )
            }
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Off on Fri, Sat · from your week plan"))
        compose.onNodeWithText("Off on Fri, Sat · from your week plan").assertIsDisplayed()
    }
}
