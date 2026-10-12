package com.sleepsaver.core.alarm.ring

import android.content.Context
import com.sleepsaver.core.domain.schedule.AlarmTone
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** The alarm's sound: started once, then only its player gain changes. */
internal interface RingSound {
    /** Requests transient audio focus and starts playing at [gain], whether or not focus is granted. */
    fun start(gain: Float)
    fun setGain(gain: Float)

    /** After [start]: asks for focus again (one of our screens came to the front); a no-op while it is held. */
    fun reclaimFocus()

    /** Stops, releases the player and abandons focus. Safe to call twice. */
    fun stop()
}

/** The alarm's vibration (USAGE_ALARM, a repeating waveform). */
internal interface RingVibration {
    fun start()
    fun stop()
}

/** Makes the ring's outputs; a fake in tests (Robolectric has no audio). */
internal interface RingOutputs {
    /** [locked]: before the first unlock the phone's own alarm sound is unreadable, so SYSTEM_ALARM plays Soft chimes. */
    fun sound(tone: AlarmTone, locked: Boolean): RingSound
    fun vibration(): RingVibration
}

/** The device's player and vibrator. Direct-boot safe (context only). */
internal class DeviceRingOutputs @Inject constructor(@ApplicationContext private val context: Context) : RingOutputs {
    override fun sound(tone: AlarmTone, locked: Boolean): RingSound = AlarmPlayer(context, tone, locked)
    override fun vibration(): RingVibration = AlarmVibrator(context)
}
