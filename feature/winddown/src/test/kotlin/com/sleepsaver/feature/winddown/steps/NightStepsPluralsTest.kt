package com.sleepsaver.feature.winddown.steps

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.domain.nightsteps.EveningResult
import com.sleepsaver.core.domain.nightsteps.EveningState
import com.sleepsaver.core.domain.nightsteps.StepAdherence
import com.sleepsaver.core.domain.patterns.EffectMetric
import com.sleepsaver.core.domain.patterns.MarkEffect
import com.sleepsaver.core.domain.patterns.PatternLabel
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.ui.testing.PHONE_PORTRAIT_DARK
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.feature.winddown.WinddownSamples
import com.sleepsaver.feature.winddown.richSession
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A count of one reads "1 step" / "1 evening" / "1 night", never "1 steps" / "1 evenings" / "1 nights". */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_PORTRAIT_DARK)
class NightStepsPluralsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an evening with a single step says step`() {
        val herbalTea = RoutineActivity(10, "Herbal tea", "🍵", isCustom = true, selected = true)
        val state = WinddownSamples.nightSteps(session = richSession(done = 0, activities = listOf(herbalTea))) as NightStepsUiState.Ready
        compose.setContent { SleepSaverTheme { StepsCard(state, onToggle = { _, _ -> }, onStart = {}, onChangeSteps = {}) } }

        compose.onNodeWithContentDescription("0 of 1 step done tonight").assertExists()
    }

    @Test
    fun `a strip of one evening says evening`() {
        val tonight = EveningResult(PlanSamples.today, planned = 2, done = 2, state = EveningState.DONE)
        val adherence = StepAdherence(listOf(tonight), doneRecent = 1, countedRecent = 1, quietStreak = 1)
        compose.setContent { SleepSaverTheme { TrackingCard(adherence, streakShowFrom = 2) } }

        compose.onNodeWithContentDescription("Last 1 evening: 1 with Night steps done").assertExists()
    }

    @Test
    fun `the comparison counts the nights next to the noun`() {
        fun effect(metric: EffectMetric, without: Int, difference: Double) =
            MarkEffect(Mark.NIGHT_STEPS_DONE, metric, PatternLabel.CLEAR, withNights = 9, withoutNights = without, difference = difference, low = null, high = null)
        val rows = listOf(
            effect(EffectMetric.TOTAL_SLEEP, without = 1, difference = 24.0),
            effect(EffectMetric.AWAKE, without = 1, difference = -10.0),
            effect(EffectMetric.MORNING_FEEL, without = 1, difference = 0.5),
            effect(EffectMetric.TOTAL_SLEEP, without = 12, difference = -24.0),
        )
        compose.setContent { SleepSaverTheme { CompareCard(rows, CompareStatus.ROWS) } }

        compose.onNodeWithText("Nights after Night steps: about 25 min more sleep (9 vs 1 night)").assertExists()
        compose.onNodeWithText("Nights after Night steps: about 10 min less awake time (9 vs 1 night)").assertExists()
        compose.onNodeWithText("Nights after Night steps: mornings felt about 0.5 steps brighter (9 vs 1 night)").assertExists()
        compose.onNodeWithText("Nights after Night steps: about 25 min less sleep (9 vs 12 nights)").assertExists()
    }
}
