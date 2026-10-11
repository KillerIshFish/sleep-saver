package com.sleepsaver.feature.progress.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.patterns.EffectMetric
import com.sleepsaver.core.domain.patterns.KeelResult
import com.sleepsaver.core.domain.patterns.MarkEffect
import com.sleepsaver.core.domain.patterns.PatternLabel
import com.sleepsaver.core.ui.testing.PHONE_PORTRAIT_DARK
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.feature.progress.MarkPatterns
import com.sleepsaver.feature.progress.MarkProgress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A count of one reads "1 day" / "1 night", never "1 days" / "1 nights" (the counts come from config and data). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_PORTRAIT_DARK)
class ProgressPluralsTest {

    @get:Rule
    val compose = createComposeRule()

    /** One day of Keel window and one night per side for marks. */
    private val ones = SleepConfig.DEFAULT.run { copy(keel = keel.copy(windowDays = 1), journal = journal.copy(marksMinPerSide = 1)) }

    @Test
    fun `keel calibrating and its caption count a single day`() {
        compose.setContent {
            SleepSaverTheme { KeelCard(KeelResult.Calibrating(validDays = 0, needed = 1), socialJetlag = null, config = ones) }
        }

        compose.onNodeWithText("Keel · Calibrating: 0 of 1 day with nights").assertExists()
        compose.onNodeWithText("Keel compares when you're asleep, 24 hours apart, across the last 1 day.").assertExists()
    }

    @Test
    fun `a pattern row and the waiting list count a single night`() {
        val marks = MarkPatterns(
            shown = listOf(
                MarkEffect(Mark.ALCOHOL, EffectMetric.TOTAL_SLEEP, PatternLabel.CLEAR, withNights = 9, withoutNights = 1, difference = -42.0, low = -61.0, high = -24.0),
            ),
            waiting = listOf(
                MarkProgress(Mark.STRESS, nights = 0, needed = 1),
                MarkProgress(Mark.UNWELL, nights = 0, needed = 1, shortOnNightsWith = false),
            ),
        )
        compose.setContent { SleepSaverTheme { MarksCard(marks, config = ones) } }

        compose.onNodeWithText("Nights after alcohol: about 40 min less sleep (9 vs 1 night)").assertExists()
        compose.onNodeWithText("Not enough nights yet").performClick()
        compose.onNodeWithText("Stress: 0 of 1 night").assertExists()
        compose.onNodeWithText("Unwell: 0 of 1 night without it").assertExists()
    }

    @Test
    fun `without marks the card asks for a single night on each side`() {
        compose.setContent { SleepSaverTheme { MarksCard(MarkPatterns(), config = ones) } }

        compose.onNodeWithText(
            "Add marks in the morning review or during Night steps. Patterns show once a mark has 1 night with it and 1 without.",
        ).assertExists()
    }
}
