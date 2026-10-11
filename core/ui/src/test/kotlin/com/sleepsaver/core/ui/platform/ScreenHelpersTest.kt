package com.sleepsaver.core.ui.platform

import android.media.AudioManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The media-volume warning behind Hush and the night screens' dimming. */
@RunWith(AndroidJUnit4::class)
class ScreenHelpersTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val activity get() = compose.activity
    private val audio get() = activity.getSystemService(AudioManager::class.java)

    private fun setMediaShare(share: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * share).toInt(), 0)
    }

    @Test
    fun `media volume counts as high only above the threshold`() {
        setMediaShare(0.8f)
        assertThat(mediaVolumeHigh(activity, 0.5f)).isTrue()
        setMediaShare(0.4f)
        assertThat(mediaVolumeHigh(activity, 0.5f)).isFalse()
        setMediaShare(0f)
        assertThat(mediaVolumeHigh(activity, 0f)).isFalse()
    }

    @Test
    fun `media volume is read again when the screen resumes`() {
        setMediaShare(0.2f)
        var high: Boolean? = null
        compose.setContent { high = rememberMediaVolumeHigh(0.5f) }
        compose.waitForIdle()
        assertThat(high).isFalse()

        setMediaShare(1f)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertThat(high).isTrue()
    }

    @Test
    fun `dim screen sets the brightness and puts it back`() {
        var dim by mutableStateOf(true)
        var level by mutableStateOf<Float?>(0.01f)
        compose.setContent { if (dim) DimScreen(level) }
        compose.waitForIdle()
        assertThat(activity.window.attributes.screenBrightness).isEqualTo(0.01f)

        level = null
        compose.waitForIdle()
        assertThat(activity.window.attributes.screenBrightness).isEqualTo(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)

        level = 0.3f
        compose.waitForIdle()
        assertThat(activity.window.attributes.screenBrightness).isEqualTo(0.3f)

        dim = false
        compose.waitForIdle()
        assertThat(activity.window.attributes.screenBrightness).isEqualTo(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    @Test
    fun `leaving a dimmed screen for another dim-aware one never strands the dim level`() {
        val none = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        var tool by mutableStateOf(true)
        var night by mutableStateOf(false)
        compose.setContent {
            if (tool) DimScreen(0.01f)
            if (night) DimScreen(null)
        }
        compose.waitForIdle()
        assertThat(activity.window.attributes.screenBrightness).isEqualTo(0.01f)

        // A navigation transition: the Night screen enters while the dimmed tool is still on its way out.
        night = true
        compose.waitForIdle()
        assertThat(activity.window.attributes.screenBrightness).isEqualTo(none)
        tool = false
        compose.waitForIdle()
        assertThat(activity.window.attributes.screenBrightness).isEqualTo(none)

        // Leaving the Night screen later goes back to the system brightness, not the tool's 1 %.
        night = false
        compose.waitForIdle()
        assertThat(activity.window.attributes.screenBrightness).isEqualTo(none)
    }

    @Test
    fun `stacked dim screens end on the window's own brightness`() {
        activity.runOnUiThread { activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.7f } }
        compose.waitForIdle()
        var night by mutableStateOf(true)
        var tool by mutableStateOf(false)
        compose.setContent {
            if (night) DimScreen(null)
            if (tool) DimScreen(0.01f)
        }
        compose.waitForIdle()
        tool = true
        compose.waitForIdle()
        assertThat(activity.window.attributes.screenBrightness).isEqualTo(0.01f)

        night = false
        compose.waitForIdle()
        assertThat(activity.window.attributes.screenBrightness).isEqualTo(0.01f)
        tool = false
        compose.waitForIdle()
        assertThat(activity.window.attributes.screenBrightness).isEqualTo(0.7f)
    }
}
