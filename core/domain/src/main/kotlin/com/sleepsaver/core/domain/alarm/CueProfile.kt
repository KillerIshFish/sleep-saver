package com.sleepsaver.core.domain.alarm

import java.time.Duration

/**
 * A wake-up style's timeline (AlarmConfig.cue). Times are from the ring start. Example: STANDARD swells over 60 s,
 * vibrates from 30 s and escalates at 5 min without Snooze or Stop.
 */
data class CueProfile(
    /** One soft chime (firstChimeGain), then firstChimeGap of quiet, then the swell. */
    val firstChime: Boolean,
    /** Player gain rises from swellStartGain × volume to volume over this. */
    val swell: Duration,
    /** Vibration (USAGE_ALARM) starts here. */
    val vibrationAfter: Duration,
    /** Speaker (and the swell) starts here; zero = at once (Quiet bed: 2 min of vibration only). */
    val speakerAfter: Duration,
    /** No Snooze/Stop by then → full volume + vibration. */
    val escalateAfter: Duration,
)
