package com.sleepsaver.core.data.mapping

import com.sleepsaver.core.data.db.DayPlanEntity
import com.sleepsaver.core.data.db.DayRoutineItemEntity
import com.sleepsaver.core.data.db.PlanOverrideEntity
import com.sleepsaver.core.data.db.PlanPauseEntity
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.AlarmSettings
import com.sleepsaver.core.domain.schedule.AlarmTone
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.DayPlan
import com.sleepsaver.core.domain.schedule.EveningPlan
import com.sleepsaver.core.domain.schedule.MorningPlan
import com.sleepsaver.core.domain.schedule.OverrideKind
import com.sleepsaver.core.domain.schedule.PlanPause
import com.sleepsaver.core.domain.schedule.SnoozeSettings
import com.sleepsaver.core.domain.schedule.SnoozeStyle
import com.sleepsaver.core.domain.schedule.WakeStyle
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.math.roundToInt

// ---------------------------------------------------------------- Shared decoding

/** The constant of [E] named [name]; null for null or a name this version doesn't know. */
internal inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    if (name == null) null else enumValues<E>().firstOrNull { it.name == name }

/** A stored minute of day ("06:45" = 405) as a time; values outside 0..1439 wrap. */
internal fun minuteOfDayToTime(minuteOfDay: Int): LocalTime =
    LocalTime.ofSecondOfDay(Math.floorMod(minuteOfDay, MINUTES_PER_DAY) * 60L)

/** A time as its minute of day (seconds dropped). */
internal fun LocalTime.toMinuteOfDay(): Int = hour * 60 + minute

private const val MINUTES_PER_DAY = 1440
private const val MAX_VOLUME_PCT = 100

private fun minutes(value: Int): Duration = Duration.ofMinutes(value.toLong())

/** The lowest stored volume (10 % with the default config). */
private fun minVolumePct(config: SleepConfig): Int = (config.alarm.minVolume * 100).roundToInt()

// ---------------------------------------------------------------- Day plans

/**
 * One stored weekday → its plan. Every range is clamped to [config] (window, volume, snooze, overrun, wind-down
 * length), and unknown names fall back: AlarmMode → OFF, WakeStyle → STANDARD, AlarmTone → SOFT_CHIMES,
 * SnoozeStyle → TAPERING. [routineIds] = the day's day_routine_items ids in position order; they are used only when
 * [DayPlanEntity.ownRoutine] (null routine = the every-evening list otherwise).
 */
fun DayPlanEntity.toDomain(routineIds: List<Long>?, config: SleepConfig = SleepConfig.DEFAULT): DayPlan {
    val alarm = config.alarm
    val week = config.week
    return DayPlan(
        day = DayOfWeek.of(dayOfWeek),
        morning = MorningPlan(
            wakeTime = wakeMinOfDay?.let(::minuteOfDayToTime),
            alarm = AlarmSettings(
                mode = enumOrNull<AlarmMode>(alarmMode) ?: AlarmMode.OFF,
                window = minutes(windowMin).coerceIn(alarm.windowMin, alarm.windowMax),
                style = enumOrNull<WakeStyle>(wakeStyle) ?: WakeStyle.STANDARD,
                tone = enumOrNull<AlarmTone>(tone) ?: AlarmTone.SOFT_CHIMES,
                volume = volumePct.coerceIn(minVolumePct(config), MAX_VOLUME_PCT) / 100f,
                snooze = SnoozeSettings(
                    style = enumOrNull<SnoozeStyle>(snoozeStyle) ?: SnoozeStyle.TAPERING,
                    fixedLength = minutes(snoozeFixedMin).coerceIn(alarm.fixedSnoozeMin, alarm.fixedSnoozeMax),
                    fixedCount = snoozeFixedCount.coerceIn(alarm.fixedSnoozeCountMin, alarm.fixedSnoozeCountMax),
                    overrun = minutes(overrunMin).coerceIn(Duration.ZERO, alarm.overrunMax),
                ),
            ),
        ),
        evening = EveningPlan(
            bedtime = bedtimeMinOfDay?.let(::minuteOfDayToTime),
            nightStepsOn = nightStepsOn,
            windDownLength = minutes(windDownMin).coerceIn(week.windDownMinLength, week.windDownMaxLength),
            routine = if (ownRoutine) routineIds.orEmpty() else null,
        ),
    )
}

/**
 * The day's row; the routine items are written separately ([routineItems]). Volume is stored as a percent clamped to
 * 10..100; ownRoutine = the evening has its own list.
 */
fun DayPlan.toEntity(config: SleepConfig = SleepConfig.DEFAULT): DayPlanEntity {
    val alarm = morning.alarm
    return DayPlanEntity(
        dayOfWeek = day.value,
        wakeMinOfDay = morning.wakeTime?.toMinuteOfDay(),
        alarmMode = alarm.mode.name,
        windowMin = alarm.window.toMinutes().toInt(),
        wakeStyle = alarm.style.name,
        tone = alarm.tone.name,
        volumePct = (alarm.volume * 100).roundToInt().coerceIn(minVolumePct(config), MAX_VOLUME_PCT),
        snoozeStyle = alarm.snooze.style.name,
        snoozeFixedMin = alarm.snooze.fixedLength.toMinutes().toInt(),
        snoozeFixedCount = alarm.snooze.fixedCount,
        overrunMin = alarm.snooze.overrun.toMinutes().toInt(),
        bedtimeMinOfDay = evening.bedtime?.toMinuteOfDay(),
        nightStepsOn = evening.nightStepsOn,
        windDownMin = evening.windDownLength.toMinutes().toInt(),
        ownRoutine = evening.routine != null,
    )
}

/** The evening's own list as day_routine_items rows (position = list order; a repeated id is stored once). */
fun DayPlan.routineItems(): List<DayRoutineItemEntity> =
    evening.routine.orEmpty().distinct().mapIndexed { position, id -> DayRoutineItemEntity(day.value, id, position) }

// ---------------------------------------------------------------- One-offs

/**
 * null for an override kind this version doesn't know, or a "Just this once" row without its time or mode.
 * An unknown alarm mode falls back to OFF (the wake time still moves).
 */
fun PlanOverrideEntity.toDomainOrNull(): DateOverride? {
    val overrideKind = enumOrNull<OverrideKind>(kind) ?: return null
    val time = wakeMinOfDay?.let(::minuteOfDayToTime)
    val mode = alarmMode?.let { enumOrNull<AlarmMode>(it) ?: AlarmMode.OFF }
    if (overrideKind == OverrideKind.JUST_ONCE && (time == null || mode == null)) return null
    return DateOverride(date = LocalDate.parse(date), kind = overrideKind, time = time, mode = mode)
}

fun DateOverride.toEntity(createdAt: Instant, offset: ZoneOffset): PlanOverrideEntity = PlanOverrideEntity(
    date = date.toString(),
    kind = kind.name,
    wakeMinOfDay = time?.toMinuteOfDay(),
    alarmMode = mode?.name,
    createdAtEpochMs = createdAt.toEpochMilli(),
    offsetSec = offset.totalSeconds,
)

/** null for a row that ends before it starts. */
fun PlanPauseEntity.toDomainOrNull(): PlanPause? {
    val from = LocalDate.parse(fromDate)
    val until = LocalDate.parse(untilDate)
    return if (until.isBefore(from)) null else PlanPause(from, until)
}

fun PlanPause.toEntity(createdAt: Instant, offset: ZoneOffset): PlanPauseEntity = PlanPauseEntity(
    fromDate = from.toString(),
    untilDate = until.toString(),
    createdAtEpochMs = createdAt.toEpochMilli(),
    offsetSec = offset.totalSeconds,
)
