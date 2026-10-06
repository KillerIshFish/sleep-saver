package com.sleepsaver.core.domain.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Mirrors Health Connect's SleepSessionRecord stage constants. */
enum class SleepStageType(val healthConnectValue: Int) {
    UNKNOWN(0),
    AWAKE(1),
    SLEEPING(2),
    OUT_OF_BED(3),
    LIGHT(4),
    DEEP(5),
    REM(6),
    AWAKE_IN_BED(7);

    val isAwake: Boolean get() = this == AWAKE || this == OUT_OF_BED || this == AWAKE_IN_BED

    companion object {
        fun fromHealthConnect(value: Int): SleepStageType =
            entries.firstOrNull { it.healthConnectValue == value } ?: UNKNOWN
    }
}

data class SleepStage(val start: Instant, val end: Instant, val type: SleepStageType) {
    val duration: Duration get() = Duration.between(start, end)
}

/**
 * One sleep session as a data source reported it. Never modified by user edits.
 * Offsets are the ones the session was recorded in (the data layer fills nulls with the
 * device offset at ingest), so local clock times are reproducible after travel.
 */
data class RawSleepSession(
    val id: String,
    val origin: String,
    val start: Instant,
    val end: Instant,
    val startOffset: ZoneOffset,
    val endOffset: ZoneOffset,
    val stages: List<SleepStage> = emptyList(),
    val lastModified: Instant = end,
    val title: String? = null,
) {
    val duration: Duration get() = Duration.between(start, end)
    val localStart: LocalDateTime get() = LocalDateTime.ofInstant(start, startOffset)
    val localEnd: LocalDateTime get() = LocalDateTime.ofInstant(end, endOffset)
}

/** A nap the user added by hand ("+ Nap"). */
data class ManualNap(
    val id: Long,
    val start: Instant,
    val end: Instant,
    val offset: ZoneOffset,
)

/** Awake time the user added by long-pressing the sleep block. */
data class AddedAwake(
    val id: Long,
    val wakeDate: LocalDate,
    val start: Instant,
    val end: Instant,
)

/**
 * The user's edits for one night. Any null field means "use the data source's value".
 * `isManualNight` = the user added a night that has no raw data (both times are then non-null).
 */
data class NightOverride(
    val wakeDate: LocalDate,
    val fellAsleep: Instant? = null,
    val wokeUp: Instant? = null,
    val awakeMinutes: Int? = null,
    val napMinutes: Int? = null,
    val isManualNight: Boolean = false,
    val offset: ZoneOffset,
    val updatedAt: Instant,
)

sealed interface SourceSelection {
    /** Only sessions written by this package (default: Garmin Connect). */
    data class Single(val packageName: String) : SourceSelection

    /** Every origin; overlapping sessions from different apps are de-duplicated. */
    data object All : SourceSelection
}

enum class AwakeKind {
    /** An awake stage inside a session. */
    STAGE,

    /** The gap between two merged fragments. */
    GAP,

    /** Added by the user. */
    ADDED,
}

data class AwakePeriod(val start: Instant, val end: Instant, val kind: AwakeKind) {
    val duration: Duration get() = Duration.between(start, end)
}

enum class BlockKind { MAIN, EXTRA, NAP }

/**
 * A contiguous stretch of sleep drawn as one block: a merged cluster of night fragments, or a nap.
 * `asleep` excludes every awake period inside it.
 */
data class SleepBlock(
    val kind: BlockKind,
    val start: Instant,
    val end: Instant,
    val startOffset: ZoneOffset,
    val endOffset: ZoneOffset,
    val asleep: Duration,
    /** Awake periods inside the block, including the ones below the interruption threshold. */
    val awakePeriods: List<AwakePeriod> = emptyList(),
    val stages: List<SleepStage> = emptyList(),
    val sessionIds: List<String> = emptyList(),
    val origin: String? = null,
    val isManual: Boolean = false,
) {
    val timeInBed: Duration get() = Duration.between(start, end)
    val localStart: LocalDateTime get() = LocalDateTime.ofInstant(start, startOffset)
    val localEnd: LocalDateTime get() = LocalDateTime.ofInstant(end, endOffset)
}

data class StageTotals(
    val deep: Duration,
    val light: Duration,
    val rem: Duration,
    val awake: Duration,
    /** Asleep but not classified (SLEEPING / UNKNOWN / uncovered). */
    val unclassified: Duration,
) {
    val hasStageData: Boolean get() = !deep.isZero || !light.isZero || !rem.isZero
}

/**
 * Everything we know about the sleep that ended on the morning of [wakeDate], plus that day's naps.
 * Labelled in the UI as "<wakeDate − 1> night".
 *
 * A night "counts" ([isPresent]) when it has main sleep (from data or manually added).
 * A day with naps only is still treated as a missing night.
 */
data class Night(
    val wakeDate: LocalDate,
    val main: SleepBlock?,
    val extraSegments: List<SleepBlock> = emptyList(),
    val naps: List<SleepBlock> = emptyList(),
    /** Effective (override-aware) values. */
    val fellAsleep: Instant?,
    val wokeUp: Instant?,
    val fellAsleepOffset: ZoneOffset?,
    val wokeUpOffset: ZoneOffset?,
    val mainSleep: Duration,
    val awakeInMain: Duration,
    val extraSleep: Duration,
    val napSleep: Duration,
    /** main + extra + naps: what sleep debt uses. */
    val totalSleep: Duration,
    val stageTotals: StageTotals?,
    /** Awake periods ≥ the interruption threshold, plus fragment gaps and user-added awake time. */
    val interruptions: List<AwakePeriod> = emptyList(),
    val override: NightOverride? = null,
    val origin: String? = null,
) {
    val isPresent: Boolean get() = main != null || override?.isManualNight == true

    /**
     * The user changed this night: it has an override row, or awake time they added by long-pressing the sleep
     * block (an [AwakeKind.ADDED] period inside main sleep; added time outside the main window has no effect
     * and doesn't count).
     */
    val isEdited: Boolean
        get() = override != null ||
            main?.awakePeriods?.any { it.kind == AwakeKind.ADDED } == true ||
            interruptions.any { it.kind == AwakeKind.ADDED }
    val isManual: Boolean get() = override?.isManualNight == true
    val timeInBed: Duration?
        get() = if (fellAsleep != null && wokeUp != null) Duration.between(fellAsleep, wokeUp) else null

    /** Sleep efficiency = main sleep / time in bed, 0..1. */
    val efficiency: Double?
        get() = timeInBed?.takeIf { !it.isZero && !it.isNegative }?.let { mainSleep.toMillis().toDouble() / it.toMillis() }

    /** The evening this night started on, for "Saturday night" labels. */
    val nightOf: LocalDate get() = wakeDate.minusDays(1)
}
