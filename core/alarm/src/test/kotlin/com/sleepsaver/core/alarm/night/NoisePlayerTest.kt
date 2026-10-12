package com.sleepsaver.core.alarm.night

import android.media.AudioTrack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.config.SleepConfig
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioTrack
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.pow

/**
 * Hush's real player on Robolectric's AudioTrack: what its stream thread sets as the track volume. (NightService's
 * tests play FakeNoise, which can't see the thread.)
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [RecordingAudioTrack::class])
internal class NoisePlayerTest {

    private val player = NoisePlayer(HushColor.PINK)

    @Before
    fun clearVolumes() = RecordingAudioTrack.volumes.clear()

    @After
    fun stopPlayer() = player.stop()

    @Test
    fun `the fade-in and the volume slider reach the track after a silent start`() {
        player.start(0f) // NightService fades in from silence

        player.setGain(0.4f) // a fade-in step
        assertStreamsAt(0.4f)

        player.setGain(0.8f) // the slider, later
        assertStreamsAt(0.8f)
    }

    @Test
    fun `a start at a gain streams at that gain`() {
        player.start(0.3f)

        assertStreamsAt(0.3f)
    }

    @Test
    fun `stop ends the stream thread`() {
        player.start(0.5f)
        assertStreamsAt(0.5f)

        player.stop()
        val afterStop = RecordingAudioTrack.volumes.size
        Thread.sleep(POLL_MS * 10)

        assertThat(RecordingAudioTrack.volumes.size).isEqualTo(afterStop)
    }

    /** Waits for the stream thread to set [gain] (give or take the drift) before a chunk. */
    private fun assertStreamsAt(gain: Float) {
        val tolerance = gain * (10.0.pow(SleepConfig.DEFAULT.hush.driftDb / 20) - 1).toFloat()
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (RecordingAudioTrack.volumes.any { abs(it - gain) <= tolerance }) return
            Thread.sleep(POLL_MS)
        }
        assertWithMessage("track volumes set by the stream (wanted %s ± %s)", gain, tolerance)
            .that(RecordingAudioTrack.volumes.toSet())
            .contains(gain)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 5L
    }
}

/**
 * AudioTrack that records every volume it is given (from any thread) and takes a float chunk in about a millisecond,
 * like a blocking write, instead of returning at once.
 */
@Implements(AudioTrack::class)
internal class RecordingAudioTrack : ShadowAudioTrack() {
    @Implementation
    fun setVolume(gain: Float): Int {
        volumes += gain
        return AudioTrack.SUCCESS
    }

    @Implementation
    fun write(audioData: FloatArray, offsetInFloats: Int, sizeInFloats: Int, writeMode: Int): Int {
        Thread.sleep(1)
        return sizeInFloats
    }

    companion object {
        val volumes = CopyOnWriteArrayList<Float>()
    }
}
