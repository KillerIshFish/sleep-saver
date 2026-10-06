package com.sleepsaver.feature.review.edit

import com.sleepsaver.core.domain.model.AwakePeriod
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.NightOverride
import com.sleepsaver.core.data.repository.SleepRepository
import com.sleepsaver.feature.review.common.NightSource
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** What kind of night the editor is working on. */
enum class EditKind {
    /** A night recorded by the data source (maybe already edited). */
    RECORDED,

    /** A night the user added earlier (an override with isManualNight). */
    MANUAL,

    /** No night yet: saving adds one. */
    NEW,
}

enum class EditError { WAKE_BEFORE_SLEEP, TOO_LONG }

/** The values the editor started from, so only what the user changes gets saved. */
data class EditBaseline(
    val fellAsleep: Instant,
    val wokeUp: Instant,
    val awakeMinutes: Int,
    val napMinutes: Int,
    /** The night's existing edits, if any: kept for every field the user doesn't touch. */
    val override: NightOverride?,
    /** Awake stretches inside the recorded window. Until the user sets "Awake", it follows the window. */
    val awakePeriods: List<AwakePeriod>,
    /** Offset saved with the edits: the night's own, or the device's for a new night. */
    val offset: ZoneOffset,
)

/**
 * Editor state for one night: fell asleep / woke up / awake / naps, with a live total.
 * Immutable; every change returns a new state. Shared by the Edit night screen and the morning review's Adjust step.
 */
data class NightEditorState(
    val wakeDate: LocalDate,
    val kind: EditKind,
    val fellAsleep: Instant,
    val wokeUp: Instant,
    /** Shown in the zone each time was recorded in (the device zone for a new night). */
    val fellAsleepZone: ZoneId,
    val wokeUpZone: ZoneId,
    val awakeMinutes: Int,
    val napMinutes: Int,
    val awakeSetByUser: Boolean = false,
    val napsSetByUser: Boolean = false,
    /** Sleep in other segments that night; not editable here, but part of the total. */
    val extraSleep: Duration,
    val source: NightSource,
    val isEdited: Boolean,
    val baseline: EditBaseline,
) {
    val timeInBed: Duration get() = Duration.between(fellAsleep, wokeUp)

    /** Main sleep + other segments + naps, as the engine will count it after saving. */
    val totalSleep: Duration
        get() {
            val main = (timeInBed - minutes(awakeMinutes)).let { if (it.isNegative) Duration.ZERO else it }
            return main + extraSleep + minutes(napMinutes)
        }

    /** Awake can't be longer than the time in bed (and the stepper stops at 4 h unless the night already had more). */
    val awakeMax: Int
        get() {
            val inBed = (timeInBed.toMinutes().coerceAtLeast(0) / STEP * STEP).toInt()
            return minOf(maxOf(MAX_STEPPER_MINUTES, roundUpToStep(baseline.awakeMinutes)), inBed)
        }

    val napMax: Int get() = maxOf(MAX_STEPPER_MINUTES, roundUpToStep(baseline.napMinutes))

    /** The scrubbers move up to six hours either way from where the night started. */
    val fellAsleepMin: Instant get() = baseline.fellAsleep.minus(SCRUB_RANGE)
    val fellAsleepMax: Instant get() = baseline.fellAsleep.plus(SCRUB_RANGE)
    val wokeUpMin: Instant get() = baseline.wokeUp.minus(SCRUB_RANGE)
    val wokeUpMax: Instant get() = baseline.wokeUp.plus(SCRUB_RANGE)

    val error: EditError?
        get() = when {
            !wokeUp.isAfter(fellAsleep) -> EditError.WAKE_BEFORE_SLEEP
            timeInBed > MAX_TIME_IN_BED -> EditError.TOO_LONG
            else -> null
        }

    /** True when saving would change something (a new night always does). */
    val hasChanges: Boolean get() = kind == EditKind.NEW || toSave(Instant.EPOCH) != NightSave.NoChange

    val canSave: Boolean get() = error == null && hasChanges

    /** Awake follows the window until the user (now or in an earlier edit) sets it. */
    private val awakeFixed: Boolean get() = awakeSetByUser || baseline.override?.awakeMinutes != null

    fun withFellAsleep(time: Instant): NightEditorState = withWindow(time, wokeUp)

    fun withWokeUp(time: Instant): NightEditorState = withWindow(fellAsleep, time)

    fun withAwake(minutes: Int): NightEditorState {
        val snapped = snapToStep(awakeMinutes, minutes)
        return copy(awakeMinutes = snapped.coerceIn(0, awakeMax.coerceAtLeast(0)), awakeSetByUser = true)
    }

    fun withNaps(minutes: Int): NightEditorState {
        val snapped = snapToStep(napMinutes, minutes)
        return copy(napMinutes = snapped.coerceIn(0, napMax), napsSetByUser = true)
    }

    private fun withWindow(fell: Instant, woke: Instant): NightEditorState {
        val moved = copy(fellAsleep = fell, wokeUp = woke)
        val awake = if (awakeFixed) awakeMinutes else derivedAwake(fell, woke)
        return moved.copy(awakeMinutes = awake.coerceIn(0, moved.awakeMax.coerceAtLeast(0)))
    }

    /** What the engine would count as awake for this window if the user doesn't set it. */
    private fun derivedAwake(fell: Instant, woke: Instant): Int {
        if (!woke.isAfter(fell)) return 0
        val clipped = baseline.awakePeriods
            .map { maxOf(it.start, fell) to minOf(it.end, woke) }
            .filter { (s, e) -> s.isBefore(e) }
            .sortedBy { it.first }
        var total = Duration.ZERO
        var cursor: Instant? = null
        for ((s, e) in clipped) {
            val start = if (cursor != null && cursor.isAfter(s)) cursor else s
            if (e.isAfter(start)) total += Duration.between(start, e)
            if (cursor == null || e.isAfter(cursor)) cursor = e
        }
        return roundMinutes(total)
    }

    /**
     * What to write. Recorded nights get an override holding only the fields the user changed (plus earlier edits
     * of the fields they left alone); a new night becomes a manual night.
     */
    fun toSave(now: Instant): NightSave {
        if (kind == EditKind.NEW) {
            val awake = awakeMinutes.takeIf { it > 0 }
            val naps = napMinutes.takeIf { napsSetByUser && it != baseline.napMinutes }
            val minutesOverride = if (awake != null || naps != null) {
                NightOverride(
                    wakeDate = wakeDate,
                    fellAsleep = fellAsleep,
                    wokeUp = wokeUp,
                    awakeMinutes = awake,
                    napMinutes = naps,
                    isManualNight = true,
                    offset = baseline.offset,
                    updatedAt = now,
                )
            } else {
                null
            }
            return NightSave.ManualNight(wakeDate, fellAsleep, wokeUp, baseline.offset, minutesOverride)
        }
        val base = baseline.override
        val manual = kind == EditKind.MANUAL
        val fell = if (manual || fellAsleep != baseline.fellAsleep) fellAsleep else base?.fellAsleep
        val woke = if (manual || wokeUp != baseline.wokeUp) wokeUp else base?.wokeUp
        val expectedAwake = base?.awakeMinutes ?: derivedAwake(fellAsleep, wokeUp)
        val awake = if (awakeSetByUser && awakeMinutes != expectedAwake) awakeMinutes else base?.awakeMinutes
        val naps = if (napsSetByUser && napMinutes != baseline.napMinutes) napMinutes else base?.napMinutes
        if (base == null && fell == null && woke == null && awake == null && naps == null) return NightSave.NoChange
        val override = NightOverride(
            wakeDate = wakeDate,
            fellAsleep = fell,
            wokeUp = woke,
            awakeMinutes = awake,
            napMinutes = naps,
            isManualNight = manual,
            offset = base?.offset ?: baseline.offset,
            updatedAt = now,
        )
        if (base != null && override.copy(updatedAt = base.updatedAt) == base) return NightSave.NoChange
        return NightSave.Override(override)
    }

    companion object {
        const val STEP = 5
        const val MAX_STEPPER_MINUTES = 240
        val SCRUB_RANGE: Duration = Duration.ofHours(6)
        val MAX_TIME_IN_BED: Duration = Duration.ofHours(16)

        /**
         * Starts editing [night] (null or not present = add a missing night: fell asleep = typical wake − need,
         * woke up = typical wake, in [deviceZone]).
         */
        fun from(
            wakeDate: LocalDate,
            night: Night?,
            sleepNeed: Duration,
            typicalWake: LocalTime,
            deviceZone: ZoneId,
        ): NightEditorState {
            val fell = night?.fellAsleep
            val woke = night?.wokeUp
            val naps = roundMinutes(night?.napSleep ?: Duration.ZERO)
            if (night != null && night.isPresent && fell != null && woke != null) {
                val offset = night.override?.offset ?: night.wokeUpOffset ?: night.fellAsleepOffset
                    ?: deviceZone.rules.getOffset(woke)
                val awake = roundMinutes(night.awakeInMain)
                return NightEditorState(
                    wakeDate = wakeDate,
                    kind = if (night.isManual) EditKind.MANUAL else EditKind.RECORDED,
                    fellAsleep = fell,
                    wokeUp = woke,
                    fellAsleepZone = night.fellAsleepOffset ?: offset,
                    wokeUpZone = night.wokeUpOffset ?: offset,
                    awakeMinutes = awake,
                    napMinutes = naps,
                    extraSleep = night.extraSleep,
                    source = NightSource.of(night, deviceZone, woke),
                    isEdited = night.isEdited,
                    baseline = EditBaseline(
                        fellAsleep = fell,
                        wokeUp = woke,
                        awakeMinutes = awake,
                        napMinutes = naps,
                        override = night.override,
                        awakePeriods = night.main?.awakePeriods.orEmpty(),
                        offset = offset,
                    ),
                )
            }
            val wake = wakeDate.atTime(typicalWake).atZone(deviceZone).toInstant()
            val asleep = wake.minus(sleepNeed)
            val offset = deviceZone.rules.getOffset(wake)
            return NightEditorState(
                wakeDate = wakeDate,
                kind = EditKind.NEW,
                fellAsleep = asleep,
                wokeUp = wake,
                fellAsleepZone = deviceZone,
                wokeUpZone = deviceZone,
                awakeMinutes = 0,
                napMinutes = naps,
                extraSleep = Duration.ZERO,
                source = NightSource.of(night, deviceZone, wake),
                isEdited = false,
                baseline = EditBaseline(
                    fellAsleep = asleep,
                    wokeUp = wake,
                    awakeMinutes = 0,
                    napMinutes = naps,
                    override = null,
                    awakePeriods = emptyList(),
                    offset = offset,
                ),
            )
        }

        /** Stepping from an off-grid value (e.g. 23 min) lands on the next 5-minute mark in that direction. */
        internal fun snapToStep(old: Int, new: Int): Int {
            val rest = Math.floorMod(new, STEP)
            return when {
                rest == 0 -> new
                new > old -> new - rest
                else -> new + (STEP - rest)
            }
        }

        private fun roundUpToStep(minutes: Int): Int = (minutes + STEP - 1) / STEP * STEP
    }
}

/** What saving the editor writes. */
sealed interface NightSave {
    data object NoChange : NightSave

    data class Override(val override: NightOverride) : NightSave

    /** A night with no data: added as a manual night, then [minutes] (awake/naps) saved on top when set. */
    data class ManualNight(
        val wakeDate: LocalDate,
        val fellAsleep: Instant,
        val wokeUp: Instant,
        val offset: ZoneOffset,
        val minutes: NightOverride?,
    ) : NightSave
}

/** Writes [save]. Returns true when something was written. */
internal suspend fun SleepRepository.apply(save: NightSave): Boolean = when (save) {
    NightSave.NoChange -> false
    is NightSave.Override -> {
        saveOverride(save.override)
        true
    }
    is NightSave.ManualNight -> {
        addManualNight(save.wakeDate, save.fellAsleep, save.wokeUp, save.offset)
        save.minutes?.let { saveOverride(it) }
        true
    }
}

/** True once [night] shows what [save] wrote (the data layer re-assembles nights asynchronously). */
internal fun NightSave.isVisibleIn(night: Night?): Boolean = when (this) {
    NightSave.NoChange -> true
    is NightSave.Override -> night?.override?.let {
        it.fellAsleep == override.fellAsleep && it.wokeUp == override.wokeUp &&
            it.awakeMinutes == override.awakeMinutes && it.napMinutes == override.napMinutes
    } == true
    is NightSave.ManualNight -> night?.isPresent == true
}

private fun minutes(value: Int): Duration = Duration.ofMinutes(value.toLong())

/** Whole minutes, rounded half up. */
internal fun roundMinutes(duration: Duration): Int = ((duration.toMillis() + 30_000) / 60_000).toInt()
