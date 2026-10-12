package com.sleepsaver.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.ui.testing.PHONE_PORTRAIT_DARK
import com.sleepsaver.core.ui.testing.SCREENSHOT_SDK
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [SCREENSHOT_SDK], qualifiers = PHONE_PORTRAIT_DARK)
class ScreenHeaderTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `a subtitle with alternatives keeps the tallest one's height and reads only itself`() {
        var subtitle by mutableStateOf(SHORT)
        rule.setContent {
            SleepSaverTheme {
                Column {
                    ScreenHeader(
                        title = "Progress",
                        modifier = Modifier.width(WIDTH).testTag("switching"),
                        subtitle = subtitle,
                        subtitleAlternatives = listOf(SHORT, LONG),
                    )
                    ScreenHeader(title = "Progress", modifier = Modifier.width(WIDTH).testTag("short"), subtitle = SHORT)
                }
            }
        }
        val reserved = rule.onNodeWithTag("switching").height()

        // The short line alone is shorter: the header holds room for the long one's second line.
        assertThat(rule.onNodeWithTag("short").height()).isLessThan(reserved)
        // Only the shown subtitle is on screen for a screen reader (the short header shows the same text: two nodes).
        assertThat(rule.onAllNodes(hasText(SHORT)).fetchSemanticsNodes()).hasSize(2)
        rule.onNodeWithText(LONG).assertDoesNotExist()

        subtitle = LONG

        rule.onNodeWithText(LONG).assertIsDisplayed()
        assertThat(rule.onAllNodes(hasText(SHORT)).fetchSemanticsNodes()).hasSize(1)
        assertThat(rule.onNodeWithTag("switching").height()).isEqualTo(reserved)
    }

    private fun SemanticsNodeInteraction.height(): Dp = getBoundsInRoot().let { it.bottom - it.top }

    private companion object {
        val WIDTH = 220.dp
        const val SHORT = "Sep 23 – Oct 6"
        const val LONG = "Nights of Dec 21, 2026 – Jan 3, 2027"
    }
}
