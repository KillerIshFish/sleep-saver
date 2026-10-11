package com.sleepsaver.core.ui.platform

import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

/**
 * Sets this window's screen brightness to [brightness] (0..1) while in composition, and puts back what it was when
 * it leaves. null follows the system brightness. Example: a guided tool calls `DimScreen(0.01f)` after a while so the
 * screen glows faintly in the dark; the Night screen calls `DimScreen(null)`. It never keeps the screen on.
 *
 * Several at once (two screens during a navigation transition) stack: the newest one wins, and when the last one
 * leaves the window gets the brightness it had before the first. So leaving a dimmed tool for the Night screen and
 * later leaving that never strands the app at the tool's brightness.
 */
@Composable
fun DimScreen(brightness: Float?) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity, brightness) {
        val window = activity.window
        val stack = dimStacks.getOrPut(window) { DimStack(original = window.attributes.screenBrightness) }
        val request = DimRequest(brightness?.coerceIn(0f, 1f) ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        stack.requests += request
        window.setScreenBrightness(request.brightness)
        onDispose {
            stack.requests.remove(request)
            window.setScreenBrightness(stack.requests.lastOrNull()?.brightness ?: stack.original)
            if (stack.requests.isEmpty()) dimStacks.remove(window)
        }
    }
}

/** One live [DimScreen] call (compared by identity). */
private class DimRequest(val brightness: Float)

/** The live [DimScreen] calls on one window, oldest first, and the brightness the window had before the first. */
private class DimStack(val original: Float) {
    val requests = ArrayList<DimRequest>()
}

/** Per window; only touched from composition effects, so always on the main thread. */
private val dimStacks = WeakHashMap<Window, DimStack>()

private fun Window.setScreenBrightness(brightness: Float) {
    attributes = attributes.apply { screenBrightness = brightness }
}
