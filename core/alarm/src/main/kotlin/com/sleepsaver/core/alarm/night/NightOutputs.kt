package com.sleepsaver.core.alarm.night

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.core.content.getSystemService
import com.sleepsaver.core.alarm.synth.NoiseDrift
import com.sleepsaver.core.alarm.synth.NoiseSynth
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.config.SleepConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** One accelerometer stream. Samples arrive on a background thread with their own timestamps. */
internal interface MotionSource {
    /** Starts the stream; false when the phone has no accelerometer. */
    fun start(onSample: (Instant, Float, Float, Float) -> Unit): Boolean
    fun stop()

    /** True when the sensor wakes the CPU itself (batched); otherwise the service holds a partial wake lock. */
    val wakeUp: Boolean
}

internal interface MotionSensors {
    fun create(): MotionSource
}

/** Hush's noise: plays a pre-rendered loop at a player gain. */
internal interface HushSound {
    fun start(gain: Float)
    fun setGain(gain: Float)
    fun pause()
    fun resume()
    fun stop()
}

internal interface HushOutputs {
    fun noise(color: HushColor): HushSound
}

/** The phone's accelerometer (SPEC_V2 §4.6). */
internal class DeviceMotionSensors @Inject constructor(@ApplicationContext private val context: Context) : MotionSensors {
    override fun create(): MotionSource = AccelerometerSource(context)
}

/**
 * The wake-up accelerometer when the phone has one (batched up to maxReportLatency), else the normal one (the service
 * then holds a partial wake lock). Event timestamps (elapsedRealtimeNanos) become Instants through a pair captured at
 * registration, so batching delays delivery but not the times.
 */
internal class AccelerometerSource(context: Context) : MotionSource, SensorEventListener {
    private val manager = context.getSystemService<SensorManager>()
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var thread: HandlerThread? = null
    private var baseNanos = 0L
    private var baseInstant: Instant = Instant.EPOCH
    private var onSample: ((Instant, Float, Float, Float) -> Unit)? = null

    override val wakeUp: Boolean get() = sensor?.isWakeUpSensor == true

    override fun start(onSample: (Instant, Float, Float, Float) -> Unit): Boolean {
        val manager = manager ?: return false
        val sensor = sensor ?: return false
        this.onSample = onSample
        val handlerThread = HandlerThread("SleepSaverSensor").apply { start() }
        thread = handlerThread
        baseNanos = SystemClock.elapsedRealtimeNanos()
        baseInstant = Instant.now()
        val surfacing = SleepConfig.DEFAULT.surfacing
        val periodUs = TimeUnit.MILLISECONDS.toMicros(surfacing.samplingPeriod.toMillis()).toInt()
        val latencyUs = if (sensor.isWakeUpSensor && sensor.fifoMaxEventCount > 0) {
            TimeUnit.MILLISECONDS.toMicros(surfacing.maxReportLatency.toMillis()).toInt()
        } else {
            0
        }
        val registered = manager.registerListener(this, sensor, periodUs, latencyUs, Handler(handlerThread.looper))
        if (!registered) stop()
        return registered
    }

    override fun stop() {
        runCatching { manager?.flush(this) }
        manager?.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        onSample = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val at = baseInstant.plusNanos(event.timestamp - baseNanos)
        onSample?.invoke(at, event.values[0], event.values[1], event.values[2])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/** Hush through [NoisePlayer]. */
internal class DeviceHushOutputs @Inject constructor() : HushOutputs {
    override fun noise(color: HushColor): HushSound = NoisePlayer(color)
}

/**
 * Generated noise (SPEC_V2 §4.6, §4.8): a seamless loop from [NoiseSynth] streamed through an AudioTrack (PCM float,
 * 48 kHz mono, USAGE_MEDIA / CONTENT_TYPE_MUSIC, power-saving mode), with the slow [NoiseDrift] on top of the gain.
 */
internal class NoisePlayer(private val color: HushColor) : HushSound {
    private val hush = SleepConfig.DEFAULT.hush
    @Volatile private var streaming = false
    @Volatile private var gain = 0f
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    override fun start(gain: Float) {
        this.gain = gain
        val minBuffer = AudioTrack.getMinBufferSize(hush.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val audio = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(hush.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
                .setBufferSizeInBytes(maxOf(minBuffer, CHUNK * Float.SIZE_BYTES) * 4)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "No audio track for Hush", e)
            return
        }
        track = audio
        audio.setVolume(0f)
        streaming = true
        thread = Thread({
            val loop = NoiseSynth.render(color)
            val drift = NoiseDrift(hush)
            val startedAt = SystemClock.elapsedRealtime()
            var position = 0
            try {
                audio.play()
                while (streaming) {
                    audio.setVolume((gain * drift.gain(SystemClock.elapsedRealtime() - startedAt)).coerceIn(0f, 1f))
                    val count = minOf(CHUNK, loop.size - position)
                    if (audio.write(loop, position, count, AudioTrack.WRITE_BLOCKING) < 0) break
                    position = (position + count) % loop.size
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Hush stream stopped", e)
            }
        }, "SleepSaverHush").apply { start() }
    }

    override fun setGain(gain: Float) {
        this.gain = gain
    }

    override fun pause() {
        runCatching { track?.pause() }
    }

    override fun resume() {
        runCatching { track?.play() }
    }

    override fun stop() {
        streaming = false
        runCatching { track?.pause(); track?.flush() }
        thread?.join(JOIN_MS)
        thread = null
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }

    private companion object {
        const val TAG = "SleepSaverHush"
        const val CHUNK = 4_800
        const val JOIN_MS = 500L
    }
}
