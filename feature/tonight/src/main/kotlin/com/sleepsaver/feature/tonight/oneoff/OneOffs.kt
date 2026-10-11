package com.sleepsaver.feature.tonight.oneoff

import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.OverrideKind
import com.sleepsaver.core.domain.schedule.PlanPause
import com.sleepsaver.core.domain.schedule.ResolvedDay
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.feature.tonight.TonightWriter
import com.sleepsaver.feature.tonight.roundTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * What the one-off sheet pre-fills from: the week plan, its one-offs, the usual wake time of each weekday and today.
 * Example: Tuesday has a 6:45 alarm, so "Just this once" starts at 6:45 Surfacing; Saturday has none, so it starts
 * at Saturday's usual 8:10 with the default mode.
 */
internal data class OneOffContext(
    val week: WeekPlan,
    val oneOffs: OneOffs,
    val usualWake: Map<DayOfWeek, LocalTime>,
    val today: LocalDate,
) {
    fun resolve(date: LocalDate): ResolvedDay = week.resolve(date, oneOffs)

    /** The date's own wake time, else its weekday's usual wake rounded to `week.timeRounding`. */
    fun startTime(date: LocalDate, config: SleepConfig = SleepConfig.DEFAULT): LocalTime =
        resolve(date).wakeTime ?: (usualWake[date.dayOfWeek] ?: DEFAULT_WAKE).roundTo(config.week.timeRounding)

    /** The date's alarm mode when it has one, else the mode a new alarm gets (`alarm.defaultMode`). */
    fun startMode(date: LocalDate, config: SleepConfig = SleepConfig.DEFAULT): AlarmMode =
        resolve(date).alarmMode.takeIf { it != AlarmMode.OFF } ?: config.alarm.defaultMode

    companion object {
        /** Used only if the usual wake times haven't loaded (the repository never fails them). */
        private val DEFAULT_WAKE: LocalTime = LocalTime.of(7, 0)

        /** The context as the plan changes; null when it can't be read (the sheet then doesn't open). */
        fun flow(schedule: ScheduleRepository, time: TimeProvider): Flow<OneOffContext?> = combine(
            schedule.weekPlan,
            schedule.oneOffs,
            schedule.usualWake,
            time.minuteTicks.map { it.atZone(time.zone()).toLocalDate() }.distinctUntilChanged(),
        ) { week, oneOffs, usualWake, today -> OneOffContext(week, oneOffs, usualWake, today) as OneOffContext? }
            .catch { emit(null) }
    }
}

/**
 * The one-off writes Tonight and the Week plan share. Each is a separate row: the week plan itself never changes.
 * Writes go through [writer] (ordered, never thrown), launched in [scope].
 */
internal class OneOffWrites(
    private val schedule: ScheduleRepository,
    private val writer: TonightWriter,
    private val scope: CoroutineScope,
    private val today: () -> LocalDate,
) {
    /** "Day off": no alarm on [date]. */
    fun dayOff(date: LocalDate) = write("Taking $date off") { schedule.setOverride(DateOverride(date, OverrideKind.DAY_OFF)) }

    /** "Just this once": [time] with [mode] on [date] (OFF = only the wake time moves). */
    fun justOnce(date: LocalDate, time: LocalTime, mode: AlarmMode) =
        write("Setting $date just this once") { schedule.setOverride(DateOverride(date, OverrideKind.JUST_ONCE, time, mode)) }

    /** Undoes [date]'s one-off ("Turn back on", "Undo"). */
    fun clear(date: LocalDate) = write("Clearing $date") { schedule.clearOverride(date) }

    /** "Pause alarms until …": from today through [until]. */
    fun pause(until: LocalDate) = write("Pausing until $until") {
        val from = today()
        schedule.setPause(PlanPause(from, maxOf(from, until)))
    }

    /** "Resume alarms". */
    fun resume() = write("Resuming alarms") { schedule.setPause(null) }

    private fun write(what: String, block: suspend () -> Unit) {
        scope.launch { writer.write(what, block) }
    }
}
