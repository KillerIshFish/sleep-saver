package com.sleepsaver.core.alarm.ring

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.schedule.AlarmTone
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager

/** The ring's player and its audio focus (docs/PLAN.md §5, Android 17 background audio hardening). */
@RunWith(AndroidJUnit4::class)
internal class AlarmPlayerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audio get() = shadowOf(context.getSystemService(AudioManager::class.java))

    @Test
    fun `the tone asks for alarm focus, asks again with the same request, and gives it back on stop`() {
        val player = AlarmPlayer(context, AlarmTone.SOFT_CHIMES, locked = false)

        player.start(0.5f)
        val request = audio.lastAudioFocusRequest.audioFocusRequest
        assertThat(request.focusGain).isEqualTo(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        assertThat(request.audioAttributes.usage).isEqualTo(AudioAttributes.USAGE_ALARM)
        assertThat(player.playing).isTrue()

        // Same request = same focus client: the platform keeps one entry for it instead of stacking a second one.
        player.reclaimFocus()
        assertThat(audio.lastAudioFocusRequest.audioFocusRequest).isSameInstanceAs(request)

        player.stop()
        assertThat(audio.lastAbandonedAudioFocusRequest).isSameInstanceAs(request)
        assertThat(player.playing).isFalse()
    }

    @Test
    fun `before it starts the player has no focus to ask for again`() {
        val player = AlarmPlayer(context, AlarmTone.SOFT_CHIMES, locked = false)

        player.reclaimFocus()
        player.stop()

        assertThat(audio.lastAudioFocusRequest).isNull()
        assertThat(audio.lastAbandonedAudioFocusRequest).isNull()
    }

    @Test
    @Config(shadows = [RefusingFocusAudioManager::class])
    fun `a focus request that throws still plays the alarm`() {
        val player = AlarmPlayer(context, AlarmTone.SOFT_CHIMES, locked = false)

        player.start(0.5f)
        player.reclaimFocus()

        assertThat(player.playing).isTrue()
        player.stop()
        assertThat(player.playing).isFalse()
    }
}

/**
 * AudioManager under Android 17's `cmd audio set-enable-hardening throw`, for a service without while-in-use. The
 * parameter is spelled out: inside the shadow, `AudioFocusRequest` is ShadowAudioManager's own nested class.
 */
@Implements(AudioManager::class)
internal class RefusingFocusAudioManager : ShadowAudioManager() {
    @Implementation
    public override fun requestAudioFocus(audioFocusRequest: android.media.AudioFocusRequest): Int =
        throw IllegalStateException("AudioHardening focus request for req 2 ignored")
}
