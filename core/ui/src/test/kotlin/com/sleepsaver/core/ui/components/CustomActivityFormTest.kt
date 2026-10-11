package com.sleepsaver.core.ui.components

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
class CustomActivityFormTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the emoji field keeps only the last character typed, whole`() {
        assertThat(lastGrapheme("")).isEmpty()
        assertThat(lastGrapheme("✨")).isEqualTo("✨")
        assertThat(lastGrapheme("✨🍵")).isEqualTo("🍵")
        assertThat(lastGrapheme("🍵🕯️")).isEqualTo("🕯️")
        assertThat(lastGrapheme("ab")).isEqualTo("b")
    }

    @Test
    fun `add waits for a label, then gets it trimmed with the picked emoji`() {
        val added = mutableListOf<Pair<String, String>>()
        var cancelled = false
        rule.setContent {
            SleepSaverTheme {
                CustomActivityForm(onCancel = { cancelled = true }, onAdd = { label, emoji -> added += label to emoji }, autoFocus = false)
            }
        }
        rule.onNodeWithText("Add").assertIsNotEnabled()

        rule.onNodeWithText("Activity").performTextInput("  Herbal tea ")
        rule.onNodeWithText("🌿").performClick()
        rule.onNodeWithText("Add").assertIsEnabled().performClick()

        assertThat(added).containsExactly("Herbal tea" to "🌿")
        rule.onNodeWithText("Cancel").performClick()
        assertThat(cancelled).isTrue()
    }

    @Test
    fun `labels are cut at the maximum length while typing`() {
        val added = mutableListOf<String>()
        rule.setContent {
            SleepSaverTheme {
                CustomActivityForm(onCancel = {}, onAdd = { label, _ -> added += label }, autoFocus = false, maxLabelLength = 6)
            }
        }
        rule.onNodeWithText("Activity").performTextInput("Stretching")
        rule.onNodeWithText("Add").performClick()

        assertThat(added).containsExactly("Stretc")
    }
}
