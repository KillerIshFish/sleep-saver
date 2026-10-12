package com.sleepsaver.core.ui.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.ui.components.PrimaryButton
import org.junit.Assert.assertThrows
import org.junit.Test

/** The simulated system bars reach the content's insets, and [assertClearOfSystemBars] catches what sits under them. */
class SystemBarsTest : ScreenshotTest() {

    @Composable
    private fun Screen(modifier: Modifier) {
        Box(modifier.fillMaxSize()) {
            Text("Top line", Modifier.align(Alignment.TopStart))
            PrimaryButton("Bottom button", onClick = {}, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
    }

    @Test
    fun paddedScreenIsClear() {
        captureScreen("system_bars_padded", systemBars = true) { Screen(Modifier.safeDrawingPadding()) }
        composeRule.assertClearOfSystemBars()
    }

    @Test
    fun edgeToEdgeContentIsCaught() {
        captureScreen("system_bars_unpadded", systemBars = true) { Screen(Modifier) }
        val error = assertThrows(AssertionError::class.java) { composeRule.assertClearOfSystemBars() }
        assertThat(error).hasMessageThat().contains("Top line")
        assertThat(error).hasMessageThat().contains("Bottom button")
    }
}
