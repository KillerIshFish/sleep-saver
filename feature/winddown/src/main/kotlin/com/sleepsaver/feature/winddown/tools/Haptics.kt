package com.sleepsaver.feature.winddown.tools

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.sleepsaver.feature.winddown.LOG_TAG
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The guided tools' haptic cues, behind a seam so tests can record them. One [tick] means "breathe in" (or "tense"),
 * a [doubleTick] "breathe out" (or "let go"), and a [pulse] "move on" (the next body-scan area, or the end).
 */
internal interface Haptics {
    fun tick()
    fun doubleTick()
    fun pulse()
}

/**
 * [Haptics] on the phone's vibrator: the system's tick effect on API 29+ (else a 20 ms one-shot), its double click
 * (else two 20 ms ticks) and a soft 80 ms pulse. Vibration that isn't available or allowed is skipped silently: a
 * missing cue must never stop a breathing exercise.
 *
 * The cues go out with the alarm usage, like the wake alarm's vibration. Without one, Android files short effects
 * under touch feedback and drops them in battery saver (only ringtone, alarm and a few other usages pass) or with touch
 * feedback turned off: someone lying in the dark with "Eyes closed" on would wait for a pulse that never comes.
 */
internal class VibratorHaptics @Inject constructor(@ApplicationContext context: Context) : Haptics {

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
    } catch (e: RuntimeException) {
        Log.w(LOG_TAG, "No vibrator", e)
        null
    }

    override fun tick() = vibrate(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        } else {
            VibrationEffect.createOneShot(TICK_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        },
    )

    override fun doubleTick() = vibrate(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        } else {
            VibrationEffect.createWaveform(longArrayOf(0, TICK_MS, DOUBLE_GAP_MS, TICK_MS), NO_REPEAT)
        },
    )

    override fun pulse() = vibrate(VibrationEffect.createOneShot(PULSE_MS, VibrationEffect.DEFAULT_AMPLITUDE))

    private fun vibrate(effect: VibrationEffect) {
        val vibrator = vibrator ?: return
        try {
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
        } catch (e: RuntimeException) {
            // SecurityException (no VIBRATE permission) or a vendor quirk: the cue is optional.
            Log.w(LOG_TAG, "Vibration failed", e)
        }
    }

    private companion object {
        const val TICK_MS = 20L
        const val DOUBLE_GAP_MS = 120L
        const val PULSE_MS = 80L
        const val NO_REPEAT = -1
    }
}

/** A monotonic clock for the guided tools' timing; tests pass the coroutine test scheduler's virtual time. */
internal fun interface GuidedClock {
    fun elapsedMillis(): Long
}

/** [GuidedClock] on `SystemClock.elapsedRealtime()`: keeps counting while the screen is off, never jumps with the wall clock. */
internal class SystemGuidedClock @Inject constructor() : GuidedClock {
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
}
