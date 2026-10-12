package com.sleepsaver.core.domain.config

import com.sleepsaver.core.domain.alarm.CueProfile
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.AlarmTone
import com.sleepsaver.core.domain.schedule.WakeStyle
import java.time.Duration

/**
 * Alarm defaults, ranges, snooze, ring timeline and cue profiles (docs/PLAN.md §3.12). Example: with the defaults a
 * 6:30 SURFACING alarm may ring from 6:00, and a snooze never ends after 6:40 (wake time + [overrunDefault]).
 */
data class AlarmConfig(
    /** Mode used when the user switches an alarm on. */
    val defaultMode: AlarmMode = AlarmMode.SURFACING,
    val defaultWindow: Duration = Duration.ofMinutes(30),
    val windowMin: Duration = Duration.ofMinutes(10),
    val windowMax: Duration = Duration.ofMinutes(45),
    val windowStep: Duration = Duration.ofMinutes(5),
    /** Debt-aware nights: window + this, capped at windowMax. */
    val debtAwareWidening: Duration = Duration.ofMinutes(10),
    /** A planned sleep opportunity below this makes the night debt-aware. */
    val shortNightOpportunity: Duration = Duration.ofMinutes(390),
    /** Player gain relative to the current alarm volume (never the stream volume). */
    val defaultVolume: Float = 0.8f,
    val minVolume: Float = 0.1f,
    val defaultWeekdayStyle: WakeStyle = WakeStyle.STANDARD,
    val defaultWeekendStyle: WakeStyle = WakeStyle.CALM,
    val defaultTone: AlarmTone = AlarmTone.SOFT_CHIMES,
    /** Tapering snooze lengths, one per snooze. */
    val taperingSnoozes: List<Duration> = listOf(
        Duration.ofMinutes(9), Duration.ofMinutes(7), Duration.ofMinutes(5), Duration.ofMinutes(3), Duration.ofMinutes(2),
    ),
    val fixedSnoozeDefault: Duration = Duration.ofMinutes(9),
    val fixedSnoozeMin: Duration = Duration.ofMinutes(1),
    val fixedSnoozeMax: Duration = Duration.ofMinutes(20),
    val fixedSnoozeCountDefault: Int = 3,
    val fixedSnoozeCountMin: Int = 1,
    val fixedSnoozeCountMax: Int = 5,
    /** A snooze never ends later than wake time + overrun (0..20 min). */
    val overrunDefault: Duration = Duration.ofMinutes(10),
    val overrunMax: Duration = Duration.ofMinutes(20),
    val overrunStep: Duration = Duration.ofMinutes(5),
    /** A snooze shorter than this isn't offered. */
    val snoozeMinLength: Duration = Duration.ofMinutes(1),
    /** Auto-silence after ringing this long without Snooze/Stop. */
    val ringTimeout: Duration = Duration.ofMinutes(10),
    /** One backup ring this long after an auto-silenced ring (never after a backup). */
    val backupRingDelay: Duration = Duration.ofMinutes(5),
    /** After a reboot or a dead process, a ring whose time passed less than this ago still rings (catch-up). */
    val missedGrace: Duration = Duration.ofMinutes(15),
    /** Catch-up and resume rings are armed this far ahead (AlarmManager clamps to ≥ 5 s anyway). */
    val catchUpDelay: Duration = Duration.ofSeconds(5),
    /**
     * With notifications or the alarm channel blocked a ring has no Stop button, so it stops itself after sounding this
     * long: counted from the speaker start ([ringCap]), so Quiet bed's vibration-only minutes don't use it up.
     */
    val notificationsBlockedRingCap: Duration = Duration.ofMinutes(2),
    /**
     * Without exact-alarm access the wake-time ring uses setWindow(target − this, this): early rather than late.
     * The Alarm check copy quotes it ("up to 10 minutes early").
     */
    val inexactRingWindow: Duration = Duration.ofMinutes(10),
    /** Without exact-alarm access extras use setWindow(at, this). */
    val inexactExtraWindow: Duration = Duration.ofMinutes(1),
    /** The mirror's `ringing` key expires at since + ringTimeout + this. */
    val ringingExpiryMargin: Duration = Duration.ofMinutes(2),
    /** A RING_EXTRA broadcast matches mirror.extra when its `at` is within ± this of now. */
    val extraMatchTolerance: Duration = Duration.ofMinutes(2),
    /** AlarmPlan.following and the mirror's `upcoming`: this many alarms after the next one. */
    val followingMirrored: Int = 2,
    /** The mirror's handledIds keeps the newest this many ids. */
    val handledIdsKept: Int = 8,
    /** NightLogRepository.recentAlarmEvents returns at most this many events. */
    val recentEventsLimit: Int = 30,
    /** "Test ring in 1 minute": rings this long after the tap, for this long ([testCue]). */
    val testRingDelay: Duration = Duration.ofSeconds(60),
    val testRingLength: Duration = Duration.ofSeconds(60),
    /** A test ring swells over at most this, so the rest of testRingLength plays at the day's full volume. */
    val testRingSwell: Duration = Duration.ofSeconds(30),
    /** A tone preview in the editor plays this long. */
    val previewLength: Duration = Duration.ofSeconds(5),
    /** Swell starts at this fraction of the day's volume. */
    val swellStartGain: Float = 0.05f,
    /** Calm's single soft first chime: its gain (fraction of volume) and the quiet gap after it. */
    val firstChimeGain: Float = 0.15f,
    val firstChimeGap: Duration = Duration.ofSeconds(20),
    val calm: CueProfile = CueProfile(
        firstChime = true,
        swell = Duration.ofSeconds(90),
        vibrationAfter = Duration.ofSeconds(60),
        speakerAfter = Duration.ZERO,
        escalateAfter = Duration.ofMinutes(5),
    ),
    val standard: CueProfile = CueProfile(
        firstChime = false,
        swell = Duration.ofSeconds(60),
        vibrationAfter = Duration.ofSeconds(30),
        speakerAfter = Duration.ZERO,
        escalateAfter = Duration.ofMinutes(5),
    ),
    val firm: CueProfile = CueProfile(
        firstChime = false,
        swell = Duration.ofSeconds(20),
        vibrationAfter = Duration.ZERO,
        speakerAfter = Duration.ZERO,
        escalateAfter = Duration.ofMinutes(3),
    ),
    val quietBed: CueProfile = CueProfile(
        firstChime = false,
        swell = Duration.ofSeconds(60),
        vibrationAfter = Duration.ZERO,
        speakerAfter = Duration.ofMinutes(2),
        escalateAfter = Duration.ofMinutes(5),
    ),
) {
    /** The timeline of a wake-up style, e.g. `cue(WakeStyle.FIRM).swell` = 20 s. */
    fun cue(style: WakeStyle): CueProfile = when (style) {
        WakeStyle.CALM -> calm
        WakeStyle.STANDARD -> standard
        WakeStyle.FIRM -> firm
        WakeStyle.QUIET_BED -> quietBed
    }

    /** The timeline a ring of [kind] plays: a test ring plays [testCue], every other ring its style's own [cue]. */
    fun ringCue(kind: RingKind, style: WakeStyle): CueProfile = if (kind == RingKind.TEST) testCue(style) else cue(style)

    /**
     * A test ring's timeline: [style]'s own from its speaker start, without Calm's first chime, the swell at most
     * testRingSwell and vibration on by its end. So in testRingLength every style's test plays its tone at the day's
     * full volume and vibrates, as the real ring does once it is under way.
     * Example: Quiet bed's test sounds at once (no 2 min of vibration only) and is at full volume from 30 s; Calm's
     * swells at once over 30 s instead of after a chime over 90 s; Firm's is Firm's own timeline.
     */
    fun testCue(style: WakeStyle): CueProfile {
        val cue = cue(style)
        val swell = minOf(cue.swell, testRingSwell)
        return cue.copy(
            firstChime = false,
            swell = swell,
            vibrationAfter = minOf((cue.vibrationAfter - cue.speakerAfter).coerceAtLeast(Duration.ZERO), swell),
            speakerAfter = Duration.ZERO,
        )
    }

    /**
     * How long a ring of [kind] in [style] sounds before it stops itself (T10): testRingLength for a test ring, else
     * ringTimeout, or with the ring UI blocked ([uiBlocked], no Stop button) notificationsBlockedRingCap after the
     * style's speaker start, so the ring always gets to make a sound. Never more than ringTimeout.
     * Example: blocked, a Standard ring stops at 2 min and a Quiet bed ring (speaker from 2 min) at 4 min.
     */
    fun ringCap(kind: RingKind, style: WakeStyle, uiBlocked: Boolean): Duration = when {
        kind == RingKind.TEST -> testRingLength
        uiBlocked -> minOf(cue(style).speakerAfter + notificationsBlockedRingCap, ringTimeout)
        else -> ringTimeout
    }
}
