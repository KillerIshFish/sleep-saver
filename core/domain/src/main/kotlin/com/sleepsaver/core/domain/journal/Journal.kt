package com.sleepsaver.core.domain.journal

import com.sleepsaver.core.domain.config.SleepConfig
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Night marks, keyed by the wake date of the night they precede (an evening mark for tonight → wakeDate = nightOf + 1).
 * [derived] marks are computed, never stored.
 */
enum class Mark(val derived: Boolean = false) {
    LATE_CAFFEINE,
    ALCOHOL,
    LATE_MEAL,
    LATE_WORKOUT,
    STRESS,
    SCREENS_IN_BED,
    AWAY_FROM_HOME,
    UNWELL,
    NIGHT_STEPS_DONE(derived = true),
    HUSH_USED(derived = true),
    ANCHOR_DROPPED(derived = true),
    ;

    companion object {
        /** The eight marks the user sets (stored rows). */
        val stored: List<Mark> get() = entries.filter { !it.derived }
    }
}

/** "How do you feel?" in words, no faces: Foggy / Slow / Okay / Clear / Bright (1–5). */
enum class MorningFeel(val level: Int) {
    FOGGY(1),
    SLOW(2),
    OKAY(3),
    CLEAR(4),
    BRIGHT(5),
    ;

    companion object {
        fun of(level: Int): MorningFeel? = entries.firstOrNull { it.level == level }
    }
}

/** Where Morning feel was answered: the ringing screen after Stop, or the morning review. */
enum class FeelSource { RING, REVIEW }

/** One wake date's Morning feel. */
data class MorningFeelEntry(val wakeDate: LocalDate, val feel: MorningFeel, val at: Instant, val offset: ZoneOffset, val source: FeelSource)

/** The Tomorrow list written on the evening [nightOf], shown again in the morning. */
data class TomorrowList(val nightOf: LocalDate, val text: String, val updatedAt: Instant, val offset: ZoneOffset) {
    /** Non-blank lines, trimmed, without a leading "-", "•" or "*": " - call Sam " → "call Sam". */
    val items: List<String>
        get() = text.lines()
            .map { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && trimmed[0] in BULLETS) trimmed.substring(1).trim() else trimmed
            }
            .filter { it.isNotEmpty() }

    private companion object {
        const val BULLETS = "-•*"
    }
}

/** Where Drop anchor was tapped. */
enum class AnchorSource { TONIGHT, NIGHT_STEPS, NIGHT_SCREEN }

/** A Drop anchor tap. [wakeDate] = NightRules.wakeDateOf(at, offset). */
data class Anchor(
    val id: Long,
    val at: Instant,
    val offset: ZoneOffset,
    val wakeDate: LocalDate,
    val source: AnchorSource,
    /** The optional whole-night movement log was on for this night. */
    val wholeNightLog: Boolean,
)

/** Morning-review prompts shown at most once per wake date. */
enum class PromptKind { ODD_NIGHT }

/**
 * Morning feel can still be changed until the next wake (DECISIONS A5): all of its own wake date, and on the next
 * date until that date's wake time. [todayWake] = the wake instant of [today] (ScheduleTimes.wake(today) in the
 * device zone, plan-aware); null = unknown, so it locks at midnight. Example: Tuesday's feel can be changed at
 * Wed 00:30 but not at Wed 07:00 when Wednesday's wake time is 06:30.
 */
fun feelEditable(wakeDate: LocalDate, today: LocalDate, now: Instant, todayWake: Instant?): Boolean =
    wakeDate == today || (wakeDate == today.minusDays(1) && todayWake != null && now < todayWake)

/** Marks can be set for wake dates in [today − (markEditDays − 1), today + 1] (tonight's marks included). */
fun markEditable(wakeDate: LocalDate, today: LocalDate, config: SleepConfig = SleepConfig.DEFAULT): Boolean =
    !wakeDate.isBefore(today.minusDays(config.journal.markEditDays - 1)) && !wakeDate.isAfter(today.plusDays(1))
