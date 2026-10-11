package com.sleepsaver.core.alarm.ring

import com.sleepsaver.core.domain.alarm.CueProfile
import com.sleepsaver.core.domain.config.AlarmConfig
import java.time.Duration

/** What the alarm does at one moment of its ring. [gain] is player gain (0..volume), never the stream volume. */
internal data class CueState(val speaker: Boolean, val gain: Float, val vibrate: Boolean, val escalated: Boolean)

/**
 * A wake-up style's timeline from the ring start (SPEC_V2 §4.7). Pure. Example, STANDARD at volume 0.8: speaker from 0 s
 * with gain rising 0.04 → 0.8 over 60 s, vibration from 30 s, full volume and vibration at 5 min.
 *
 *  - The speaker starts at speakerAfter (Quiet bed: 2 min of vibration only).
 *  - Calm's first chime: one cycle of the tone at firstChimeGain × volume, then firstChimeGap of quiet, then the swell.
 *  - Swell: gain ramps linearly from swellStartGain × volume to volume over swell.
 *  - Vibration from vibrationAfter. At escalateAfter: gain = volume (the day's full volume) and vibration on.
 */
internal class CueTimeline(
    private val profile: CueProfile,
    private val volume: Float,
    /** One cycle of the tone: the length of Calm's first chime. */
    private val chime: Duration,
    private val config: AlarmConfig,
) {
    fun at(elapsed: Duration): CueState {
        if (elapsed >= profile.escalateAfter) return CueState(speaker = true, gain = volume, vibrate = true, escalated = true)
        val vibrate = elapsed >= profile.vibrationAfter
        if (elapsed < profile.speakerAfter) return CueState(speaker = false, gain = 0f, vibrate = vibrate, escalated = false)
        var t = elapsed - profile.speakerAfter
        if (profile.firstChime) {
            if (t < chime) return CueState(speaker = true, gain = config.firstChimeGain * volume, vibrate = vibrate, escalated = false)
            t -= chime
            if (t < config.firstChimeGap) return CueState(speaker = true, gain = 0f, vibrate = vibrate, escalated = false)
            t -= config.firstChimeGap
        }
        val swellMs = profile.swell.toMillis()
        val progress = if (swellMs <= 0) 1f else (t.toMillis().toFloat() / swellMs).coerceIn(0f, 1f)
        val start = config.swellStartGain
        return CueState(speaker = true, gain = volume * (start + (1 - start) * progress), vibrate = vibrate, escalated = false)
    }
}
