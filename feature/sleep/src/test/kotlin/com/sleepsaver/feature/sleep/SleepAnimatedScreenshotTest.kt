package com.sleepsaver.feature.sleep

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.data.repository.SyncStatus
import com.sleepsaver.core.testing.SampleData
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
 * States with the LoadingOrb. `captureScreen` waits for its infinite animation to settle, which takes minutes;
 * the compose test rule cancels infinite animations, so the orb renders its first frame at once.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SCREENSHOT_SDK], qualifiers = PHONE_PORTRAIT_DARK)
class SleepAnimatedScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            SleepSaverTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // A Box, so content that doesn't fill the screen keeps its own size.
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
        val dir = System.getProperty("roborazzi.output.dir") ?: "build/outputs/roborazzi"
        compose.onRoot().captureRoboImage(File(dir, "$name.png").path)
    }

    @Test
    fun loading() = capture("sleep_loading") { SleepContent(SleepUiState.Loading, SleepActions()) }

    @Test
    fun waitingWhileSyncing() = capture("sleep_home_waiting_syncing") {
        SleepContent(
            ready(
                insights = SampleData.insights(nights = SampleData.nights(includeLastNight = false)),
                sync = SyncState(status = SyncStatus.Syncing, lastSuccessAt = sampleLastSync),
            ),
            SleepActions(),
        )
    }

    @Test
    fun sourceSheetSyncing() = capture("sleep_source_sheet_syncing") {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.padding(16.dp),
        ) {
            SourceSheetContent(
                state = ready(sync = SyncState(status = SyncStatus.Syncing, lastSuccessAt = sampleLastSync)),
                onSyncNow = {},
                onChangeInSettings = {},
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}
