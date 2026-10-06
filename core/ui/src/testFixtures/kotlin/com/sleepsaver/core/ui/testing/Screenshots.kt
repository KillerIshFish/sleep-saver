package com.sleepsaver.core.ui.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Robolectric's newest supported SDK (compileSdk 37 is newer than Robolectric knows). */
const val SCREENSHOT_SDK = 36

/** Pixel 7 sized phone (411 × 914 dp, 420 dpi), portrait, night mode. */
const val PHONE_PORTRAIT_DARK = "w411dp-h914dp-normal-long-notround-port-night-420dpi-keyshidden-nonav"

/**
 * Extend this in a screenshot test class: Robolectric with native graphics on a dark phone-portrait device.
 *
 * ```
 * class HomeScreenshots : ScreenshotTest() {
 *     @Test fun home() = captureScreen("home") { HomeScreen(state = previewState) }
 * }
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SCREENSHOT_SDK], qualifiers = PHONE_PORTRAIT_DARK)
abstract class ScreenshotTest

/**
 * Renders [content] full screen (411 × 914 dp) inside [SleepSaverTheme] on a background `Surface` (so plain
 * `Text` gets the theme's content colour, as under the app's Scaffold) and writes
 * `build/outputs/roborazzi/<name>.png` in the calling module.
 */
fun captureScreen(name: String, content: @Composable () -> Unit) = capture(name) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
}

/**
 * Renders [content] at its own size (plus a 16 dp margin) inside [SleepSaverTheme] on the app background and
 * writes `build/outputs/roborazzi/<name>.png`. Content that fills the width gets the phone's 411 dp.
 */
fun captureComponent(name: String, content: @Composable () -> Unit) = capture(name) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

private fun capture(name: String, content: @Composable () -> Unit) {
    val dir = System.getProperty("roborazzi.output.dir") ?: "build/outputs/roborazzi"
    captureRoboImage(File(dir, "$name.png").path) {
        SleepSaverTheme(content)
    }
}
