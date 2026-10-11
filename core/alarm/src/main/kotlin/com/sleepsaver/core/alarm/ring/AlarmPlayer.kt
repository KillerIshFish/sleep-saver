package com.sleepsaver.core.alarm.ring

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.getSystemService
import com.sleepsaver.core.alarm.AlarmPlatform
import com.sleepsaver.core.alarm.synth.ToneSynth
import com.sleepsaver.core.domain.schedule.AlarmTone

/**
 * Plays an alarm tone with USAGE_ALARM (SPEC_V2 §4.7): the synthesized tones stream from [ToneSynth] through an
 * AudioTrack; SYSTEM_ALARM uses the phone's alarm sound through MediaPlayer, falling back to Soft chimes when it can't
 * be read (always before the first unlock: ringtones live in credential-encrypted storage). Loudness is player gain
 * relative to the current alarm volume; the stream volume is never changed.
 */
internal class AlarmPlayer(
    private val context: Context,
    private val tone: AlarmTone,
    private val locked: Boolean,
) : RingSound {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val audioManager = context.getSystemService<AudioManager>()
    private var focus: AudioFocusRequest? = null
    private var track: AudioTrack? = null
    private var media: MediaPlayer? = null
    @Volatile private var streaming = false
    private var thread: Thread? = null

    override fun start(gain: Float) {
        requestFocus()
        if (tone == AlarmTone.SYSTEM_ALARM && !locked && startSystemSound(gain)) return
        startSynth(gain)
    }

    override fun setGain(gain: Float) {
        val g = gain.coerceIn(0f, 1f)
        try {
            track?.setVolume(g)
            media?.setVolume(g, g)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Gain not applied", e)
        }
    }

    override fun stop() {
        streaming = false
        thread?.join(JOIN_MS)
        thread = null
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
        media?.let { runCatching { it.stop() }; it.release() }
        media = null
        focus?.let { request -> audioManager?.abandonAudioFocusRequest(request) }
        focus = null
    }

    private fun requestFocus() {
        val manager = audioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { } // the alarm keeps sounding whatever else wants focus
            .build()
        manager.requestAudioFocus(request)
        focus = request
    }

    private fun startSystemSound(gain: Float): Boolean = try {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM) ?: return false
        media = MediaPlayer().apply {
            setAudioAttributes(attributes)
            setDataSource(context, uri)
            isLooping = true
            setVolume(gain, gain)
            prepare()
            start()
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "The phone's alarm sound can't play; using Soft chimes", e)
        media?.release()
        media = null
        false
    }

    private fun startSynth(gain: Float) {
        val sampleRate = AlarmPlatform.SAMPLE_RATE
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val audio = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuffer, BUFFER_FRAMES * Float.SIZE_BYTES) * 2)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "No audio track for the alarm", e)
            return
        }
        track = audio
        audio.setVolume(gain.coerceIn(0f, 1f))
        streaming = true
        val synth = ToneSynth(tone, sampleRate)
        thread = Thread({
            val buffer = FloatArray(BUFFER_FRAMES)
            try {
                audio.play()
                while (streaming) {
                    synth.fill(buffer)
                    if (audio.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING) < 0) break
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Alarm stream stopped", e)
            }
        }, "SleepSaverAlarmTone").apply { start() }
    }

    private companion object {
        const val TAG = "SleepSaverAlarm"
        const val BUFFER_FRAMES = 4_800
        const val JOIN_MS = 500L
    }
}

/** The alarm's vibration: [AlarmPlatform.VIBRATION_WAVEFORM] repeating, with alarm attributes (so DND rules apply). */
internal class AlarmVibrator(context: Context) : RingVibration {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService<VibratorManager>()?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService<Vibrator>()
    }
    private var on = false

    override fun start() {
        val v = vibrator ?: return
        if (on || !v.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(AlarmPlatform.VIBRATION_WAVEFORM, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
        on = true
    }

    override fun stop() {
        if (!on) return
        vibrator?.cancel()
        on = false
    }
}
