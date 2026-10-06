package com.sleepsaver.feature.energy

import androidx.compose.material3.SheetValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.core.ui.testing.PHONE_PORTRAIT_DARK
import com.sleepsaver.core.ui.testing.SCREENSHOT_SDK
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [SCREENSHOT_SDK], qualifiers = PHONE_PORTRAIT_DARK)
class EnergyContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val editedNights = mutableListOf<LocalDate>()
    private val addedAwake = mutableListOf<Triple<LocalDate, Instant, Instant>>()
    private val openedHabits = mutableListOf<HabitType>()
    private val reminders = mutableListOf<Pair<HabitType, Boolean>>()
    private var addHabitTaps = 0
    private var settingsTaps = 0
    private var syncTaps = 0

    private fun show(state: EnergyUiState, focusTime: Instant? = null, sheet: SheetValue = SheetValue.PartiallyExpanded) {
        compose.setContent {
            SleepSaverTheme {
                EnergyContent(
                    state = state,
                    onEditNight = { editedNights += it },
                    onAddAwake = { date, start, end -> addedAwake += Triple(date, start, end) },
                    onAddHabit = { addHabitTaps++ },
                    onOpenHabit = { openedHabits += it },
                    onToggleReminder = { type, on -> reminders += type to on },
                    onOpenSettings = { settingsTaps++ },
                    onSync = { syncTaps++ },
                    focusTime = focusTime,
                    initialSheetValue = sheet,
                )
            }
        }
    }

    @Test
    fun `header shows energy potential and debt status`() {
        val state = EnergyTestData.ready()
        show(state)

        compose.onNodeWithText("Energy").assertIsDisplayed()
        compose.onNodeWithText("Energy potential ${state.energyPotential}% · ", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick()
        assertThat(settingsTaps).isEqualTo(1)
    }

    @Test
    fun `tapping last night's block edits that night`() {
        val state = EnergyTestData.ready()
        show(state, focusTime = state.lastNight!!.fellAsleep!! + Duration.ofHours(4))

        compose.onNodeWithContentDescription("Last night:", substring = true).performClick()

        assertThat(editedNights).containsExactly(SampleData.today)
    }

    @Test
    fun `press and hold inside last night's block adds awake time from the pressed moment`() {
        val state = EnergyTestData.ready()
        val night = state.lastNight!!
        show(state, focusTime = night.fellAsleep!! + Duration.ofHours(4))

        val hint = compose.onNodeWithText("Press and hold to add awake time", useUnmergedTree = true).fetchSemanticsNode()
        val root = compose.onRoot().fetchSemanticsNode()
        println("ENERGY-DEBUG hint=${hint.boundsInWindow} placed=${hint.layoutInfo.isPlaced} root=${root.boundsInWindow}")
        compose.onNodeWithText("Press and hold to add awake time", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Last night:", substring = true).performTouchInput { longClick(center) }
        compose.onNodeWithText("Add awake time").assertIsDisplayed()
        compose.onNodeWithText("Add").performClick()

        val (date, start, end) = addedAwake.single()
        assertThat(date).isEqualTo(SampleData.today)
        assertThat(Duration.between(start, end)).isEqualTo(Duration.ofMinutes(15))
        assertThat(start.epochSecond % 300).isEqualTo(0)
        val middle = night.fellAsleep!!.plusMillis(Duration.between(night.fellAsleep, night.wokeUp).toMillis() / 2)
        assertThat(Duration.between(middle, start).abs()).isAtMost(Duration.ofMinutes(5))
        compose.onNodeWithText("Add awake time").assertDoesNotExist()
    }

    @Test
    fun `cancel closes the dialog without adding anything`() {
        val state = EnergyTestData.ready()
        show(state, focusTime = state.lastNight!!.fellAsleep!! + Duration.ofHours(4))

        compose.onNodeWithContentDescription("Last night:", substring = true).performTouchInput { longClick(center) }
        compose.onNodeWithText("Cancel").performClick()

        compose.onNodeWithText("Add awake time").assertDoesNotExist()
        assertThat(addedAwake).isEmpty()
    }

    @Test
    fun `tapping a habit marker opens that habit`() {
        val state = EnergyTestData.ready()
        val caffeine = state.habits.single { it.type == HabitType.CAFFEINE_CUTOFF }
        show(state, focusTime = caffeine.time)

        compose.onNodeWithContentDescription("Caffeine cutoff at", substring = true).performClick()

        assertThat(openedHabits).containsExactly(HabitType.CAFFEINE_CUTOFF)
    }

    @Test
    fun `the habits sheet adds, opens and toggles reminders`() {
        show(EnergyTestData.ready(), sheet = SheetValue.Expanded)

        compose.onNodeWithContentDescription("Add a habit").performClick()
        compose.onNodeWithContentDescription("Reminder for Caffeine cutoff").performClick()
        compose.onNodeWithText("Last big meal").performClick()

        assertThat(addHabitTaps).isEqualTo(1)
        assertThat(reminders).containsExactly(HabitType.CAFFEINE_CUTOFF to false)
        assertThat(openedHabits).containsExactly(HabitType.LARGE_MEAL_CUTOFF)
    }

    @Test
    fun `waiting for last night shows the banner and a placeholder that adds the night`() {
        val state = EnergyTestData.waiting()
        show(state, focusTime = state.schedule.typicalWake.toInstant() - Duration.ofHours(3))

        compose.onNodeWithText("Last night hasn't synced yet", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Sync now").performClick()
        compose.onNodeWithContentDescription("Last night's sleep hasn't arrived yet").performClick()

        assertThat(syncTaps).isEqualTo(1)
        assertThat(editedNights).containsExactly(SampleData.today)
    }

    @Test
    fun `while syncing the banner action is disabled`() {
        show(EnergyTestData.waiting().copy(syncing = true))

        compose.onNodeWithText("Syncing…").assertIsNotEnabled()
    }

    @Test
    fun `a missing last night offers to add it`() {
        show(EnergyTestData.waiting(now = EnergyTestData.at(15, 0)))

        compose.onNodeWithText("Add it").performClick()

        assertThat(editedNights).containsExactly(SampleData.today)
    }

    @Test
    fun `without Health Connect the empty state says so and links to set up`() {
        show(EnergyUiState.NoNights(EmptyReason.HEALTH_CONNECT_MISSING))

        compose.onNodeWithText("Health Connect is needed").assertIsDisplayed()
        compose.onNodeWithText("Set up data").performClick()

        assertThat(settingsTaps).isEqualTo(1)
    }

    @Test
    fun `no nights explains and links to set up`() {
        show(EnergyUiState.NoNights())

        compose.onNodeWithText("Your energy schedule needs a few nights of sleep").assertIsDisplayed()
        compose.onNodeWithText("Set up data").performClick()

        assertThat(settingsTaps).isEqualTo(1)
    }

    @Test
    fun `loading shows the orb`() {
        show(EnergyUiState.Loading)

        compose.onNodeWithContentDescription("Mapping your day…").assertExists()
    }
}
