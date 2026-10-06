package com.sleepsaver.feature.energy

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.sleepsaver.core.ui.testing.PHONE_PORTRAIT_DARK
import com.sleepsaver.core.ui.testing.SCREENSHOT_SDK
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * States with the LoadingOrb. `captureScreen` waits for its infinite animation to settle, which takes minutes
 * (and fills the test JVM's heap with trace records); the compose test rule cancels infinite animations, so the orb
 * renders its first frame at once.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SCREENSHOT_SDK], qualifiers = PHONE_PORTRAIT_DARK)
class EnergyAnimatedScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            SleepSaverTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        val dir = System.getProperty("roborazzi.output.dir") ?: "build/outputs/roborazzi"
        compose.onRoot().captureRoboImage(File(dir, "$name.png").path)
    }

    private fun screen(state: EnergyUiState) = capture("energy_${state.screenshotName()}") {
        EnergyContent(
            state = state,
            onEditNight = {},
            onAddAwake = { _, _, _ -> },
            onAddHabit = {},
            onOpenHabit = {},
            onToggleReminder = { _, _ -> },
            onOpenSettings = {},
        )
    }

    private fun EnergyUiState.screenshotName(): String = when (this) {
        EnergyUiState.Loading -> "loading"
        is EnergyUiState.NoNights -> "importing"
        is EnergyUiState.Ready -> "ready"
    }

    @Test
    fun loading() = screen(EnergyUiState.Loading)

    @Test
    fun importing() = screen(EnergyUiState.NoNights(EmptyReason.IMPORTING))
}
