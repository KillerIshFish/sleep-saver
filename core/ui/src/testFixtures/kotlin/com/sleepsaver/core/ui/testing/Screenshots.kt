package com.sleepsaver.core.ui.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Robolectric's newest supported SDK (compileSdk 37 is newer than Robolectric knows). */
const val SCREENSHOT_SDK = 36

/** Pixel 7 sized phone (411 × 914 dp, 420 dpi), portrait, night mode. */
const val PHONE_PORTRAIT_DARK = "w411dp-h914dp-normal-long-notround-port-night-420dpi-keyshidden-nonav"

private const val CAPTURE_TAG = "screenshot-capture"

/**
 * Extend this in a screenshot test class: Robolectric with native graphics on a dark phone-portrait device.
 *
 * ```
 * class HomeScreenshots : ScreenshotTest() {
 *     @Test fun home() = captureScreen("home") { HomeScreen(state = previewState) }
 * }
 * ```
 *
 * Content is hosted by a Compose test rule, which pauses infinite animations (spinners, the loading orb) so they
 * render their first frame instead of keeping the UI busy forever. A test may capture several times; each capture
 * replaces the previous content.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SCREENSHOT_SDK], qualifiers = PHONE_PORTRAIT_DARK)
abstract class ScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var hosted by mutableStateOf<(@Composable () -> Unit)?>(null)

    /**
     * Renders [content] full screen (411 × 914 dp) inside [SleepSaverTheme] on a background `Surface` (so plain
     * `Text` gets the theme's content colour, as under the app's Scaffold) and writes
     * `build/outputs/roborazzi/<name>.png` in the calling module. Dialogs and sheets are included.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    fun captureScreen(name: String, content: @Composable () -> Unit) {
        host {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                // A Box, so content that doesn't fill the screen keeps its own size.
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
        captureScreenRoboImage(outputPath(name))
    }

    /**
     * Renders [content] at its own size (plus a 16 dp margin) inside [SleepSaverTheme] on the app background and
     * writes `build/outputs/roborazzi/<name>.png`. Content that fills the width gets the phone's 411 dp.
     */
    fun captureComponent(name: String, content: @Composable () -> Unit) {
        host {
            Surface(Modifier.testTag(CAPTURE_TAG), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.padding(16.dp)) { content() }
            }
        }
        composeRule.onNodeWithTag(CAPTURE_TAG).captureRoboImage(outputPath(name))
    }

    private fun host(content: @Composable () -> Unit) {
        if (hosted == null) {
            hosted = content
            composeRule.setContent { SleepSaverTheme { hosted?.invoke() } }
        } else {
            hosted = content
        }
        composeRule.waitForIdle()
    }
}

/**
 * Standalone variant for tests that can't extend [ScreenshotTest]. Prefer the [ScreenshotTest] members:
 * this one waits for every animation to settle, so content with an infinite animation never finishes.
 */
fun captureScreen(name: String, content: @Composable () -> Unit) = captureStandalone(name) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
}

/** Standalone variant of [ScreenshotTest.captureComponent]; see [captureScreen] for the caveat. */
fun captureComponent(name: String, content: @Composable () -> Unit) = captureStandalone(name) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

private fun captureStandalone(name: String, content: @Composable () -> Unit) {
    captureRoboImage(outputPath(name)) {
        SleepSaverTheme(content)
    }
}

private fun outputPath(name: String): String {
    val dir = System.getProperty("roborazzi.output.dir") ?: "build/outputs/roborazzi"
    return File(dir, "$name.png").path
}
