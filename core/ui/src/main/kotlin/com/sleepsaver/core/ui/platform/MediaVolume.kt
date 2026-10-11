package com.sleepsaver.core.ui.platform

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Whether the media volume (where Hush plays) is above [threshold] of its maximum, e.g. 0.5 for "more than half
 * way up" (`config.hush.mediaVolumeWarning`). Re-read whenever the screen resumes.
 */
@Composable
fun rememberMediaVolumeHigh(threshold: Float): Boolean {
    val context = LocalContext.current
    val high = remember(context, threshold) { mutableStateOf(mediaVolumeHigh(context, threshold)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { high.value = mediaVolumeHigh(context, threshold) }
    return high.value
}

/** STREAM_MUSIC current / max > [threshold]; false when the volume can't be read. */
internal fun mediaVolumeHigh(context: Context, threshold: Float): Boolean = try {
    val audio = context.getSystemService(AudioManager::class.java)
    val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
    max > 0 && (audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / max > threshold
} catch (e: RuntimeException) {
    Log.w("MediaVolume", "Couldn't read the media volume", e)
    false
}
