package com.sleepsaver.core.ui.platform

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Sets this window's screen brightness to [brightness] (0..1) while in composition, and puts back what it was when
 * it leaves. null follows the system brightness. Example: a guided tool calls `DimScreen(0.01f)` after a while so the
 * screen glows faintly in the dark; the Night screen calls `DimScreen(null)`. It never keeps the screen on.
 */
@Composable
fun DimScreen(brightness: Float?) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity, brightness) {
        val window = activity.window
        val previous = window.attributes.screenBrightness
        window.attributes = window.attributes.apply {
            screenBrightness = brightness?.coerceIn(0f, 1f) ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        onDispose {
            window.attributes = window.attributes.apply { screenBrightness = previous }
        }
    }
}
