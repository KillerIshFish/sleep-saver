package com.sleepsaver.feature.review.detail

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.alarm.LandingStage
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.core.ui.testing.PHONE_PORTRAIT_DARK
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.feature.review.common.NightSource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Night detail's marks, Morning feel, Drop anchor and Wake landing cards. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE_PORTRAIT_DARK)
class NightDetailContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = SampleData.today
    private val night = SampleData.nights().single { it.wakeDate == today }

    private val toggled = mutableListOf<Mark>()
    private val feels = mutableListOf<MorningFeel>()

    private fun show(state: NightDetailUiState) {
        compose.setContent {
            SleepSaverTheme {
                NightDetailContent(
                    state = state,
                    onBack = {}, onAdjust = {}, onDeleteNap = {}, onRevert = {},
                    onMarkToggle = { toggled += it },
                    onFeel = { feels += it },
                )
            }
        }
    }

    private fun state(
        marks: Set<Mark> = emptySet(),
        feel: MorningFeel? = null,
        feelEditable: Boolean = true,
        wakeDate: LocalDate = today,
    ) = NightDetailUiState(
        wakeDate = wakeDate,
        today = today,
        loading = false,
        night = night,
        source = NightSource.of(night, SampleData.zone, SampleData.now),
        marks = marks,
        feel = feel,
        feelEditable = feelEditable,
        anchor = PlanSamples.anchorStats(today),
        landing = PlanSamples.landing(LandingStage.LIGHT, today),
    )

    @Test
    fun `marks and feel can be set while the night is recent`() {
        show(state(marks = setOf(Mark.ALCOHOL)))

        compose.onNodeWithText("Late caffeine").performScrollTo().performClick()
        compose.onNodeWithText("Bright").performScrollTo().performClick()

        assertThat(toggled).containsExactly(Mark.LATE_CAFFEINE)
        assertThat(feels).containsExactly(MorningFeel.BRIGHT)
    }

    @Test
    fun `an old night shows only its marks and feel, read-only`() {
        show(state(marks = setOf(Mark.ALCOHOL), feel = MorningFeel.OKAY, feelEditable = false, wakeDate = today.minusDays(10)))

        compose.onNodeWithText("Alcohol").performScrollTo().performClick()
        compose.onNodeWithText("Late caffeine").assertDoesNotExist()
        compose.onNodeWithText("Okay").performScrollTo()
        compose.onNodeWithText("Bright").assertDoesNotExist()

        assertThat(toggled).isEmpty()
    }

    @Test
    fun `the anchor line says when it was dropped, how soon sleep came and how much of the time was asleep`() {
        show(state())

        compose.onNodeWithText("Anchor dropped 10:55 PM · fell asleep about 15 min later · 91% of the time after it asleep")
            .performScrollTo()
    }

    @Test
    fun `missing anchor parts are left out`() {
        show(state().copy(anchor = PlanSamples.anchorStats(today).copy(latency = null, latencyShown = null, efficiency = null)))

        compose.onNodeWithText("Anchor dropped 10:55 PM").performScrollTo()
    }

    @Test
    fun `the compact wake landing sits on the night`() {
        show(state())

        compose.onNodeWithText("Wake landing").performScrollTo()
        compose.onNodeWithText("Watch estimate: light sleep when it rang.").assertExists()
        compose.onNodeWithText("Reset to default").assertDoesNotExist()
    }

    @Test
    fun `a night without an anchor or alarm has neither card`() {
        show(state().copy(anchor = null, landing = null, movement = emptyList()))

        compose.onNodeWithText("Wake landing").assertDoesNotExist()
        compose.onNodeWithText("Anchor dropped", substring = true).assertDoesNotExist()
    }
}
