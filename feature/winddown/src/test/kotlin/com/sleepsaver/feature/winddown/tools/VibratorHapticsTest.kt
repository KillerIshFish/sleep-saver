package com.sleepsaver.feature.winddown.tools

import android.content.Context
import android.os.VibrationAttributes
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class VibratorHapticsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Suppress("DEPRECATION") // The shadow lives on the plain Vibrator service.
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    @Test
    fun `every cue reaches the vibrator`() {
        val haptics = VibratorHaptics(context)

        haptics.tick()
        haptics.doubleTick()
        haptics.pulse()

        assertThat(shadowOf(vibrator).isVibrating).isTrue()
    }

    @Test
    fun `cues use the alarm usage, so battery saver and touch-feedback settings don't drop them`() {
        val haptics = VibratorHaptics(context)

        for (cue in listOf(haptics::tick, haptics::doubleTick, haptics::pulse)) {
            cue()
            val attributes = shadowOf(vibrator).vibrationAttributesFromLastVibration as VibrationAttributes
            assertThat(attributes.usage).isEqualTo(VibrationAttributes.USAGE_ALARM)
        }
    }

    @Test
    fun `a phone without a vibrator is skipped quietly`() {
        shadowOf(vibrator).setHasVibrator(false)
        val haptics = VibratorHaptics(context)

        haptics.tick()
        haptics.doubleTick()
        haptics.pulse()

        assertThat(shadowOf(vibrator).isVibrating).isFalse()
    }
}
