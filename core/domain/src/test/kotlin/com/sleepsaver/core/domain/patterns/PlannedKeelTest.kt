package com.sleepsaver.core.domain.patterns

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.BedtimeMode
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.model.WakeTimeMode
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.EveningPlan
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.OverrideKind
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.domain.testing.Fx
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import org.junit.jupiter.api.Test

class PlannedKeelTest {

    private val calculator = PlannedKeelCalculator()

    /** Monday 2026-10-12: the first wake date (tomorrow). */
    private val from = LocalDate.of(2026, 10, 12)

    /** Up at 07:00 and in bed at 23:00 by the Settings rules, 8 h need, 15 min to fall asleep. */
    private val settings = UserSleepSettings(
        sleepNeed = Duration.ofHours(8),
        typicalWake = LocalTime.of(7, 0),
        wakeTimeMode = WakeTimeMode.FIXED,
        bedtime = LocalTime.of(23, 0),
        bedtimeMode = BedtimeMode.FIXED,
    )

    private val weekdays = listOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)

    private fun alarms(days: List<DayOfWeek>, time: LocalTime): WeekPlan =
        Fx.weekPlan(*days.map { it to time }.toTypedArray())

    /** [plan] with a set wake time on [days] and no alarm there. */
    private fun setWake(plan: WeekPlan, time: LocalTime, vararg days: DayOfWeek): WeekPlan = days.fold(plan) { acc, day ->
        val current = acc.day(day)
        acc.with(current.copy(morning = current.morning.copy(wakeTime = time, alarm = current.morning.alarm.copy(mode = AlarmMode.OFF))))
    }

    private fun planCheck(week: WeekPlan, settings: UserSleepSettings = this.settings) =
        calculator.check(week, OneOffs(), emptyList(), settings, from, Fx.UTC)

    @Test
    fun `identical days keep an even keel without warnings`() {
        val result = planCheck(alarms(DayOfWeek.values().toList(), LocalTime.of(7, 0)))

        assertThat(result).isEqualTo(
            PlanCheck(
                plannedKeel = 100,
                plannedBand = KeelBand.EVEN,
                freeDayWakeShift = null, // every day has an alarm: no free days
                plannedSocialJetlag = null,
                warnFreeDayShift = false,
                warnSocialJetlag = false,
            ),
        )
        assertThat(result.warn).isFalse()
    }

    @Test
    fun `without any alarm weekdays and weekends are compared`() {
        val result = planCheck(WeekPlan.DEFAULT)

        assertThat(result.plannedKeel).isEqualTo(100)
        assertThat(result.freeDayWakeShift).isEqualTo(Duration.ZERO)
        assertThat(result.plannedSocialJetlag).isEqualTo(Duration.ZERO)
        assertThat(result.warn).isFalse()

        val lieIn = planCheck(setWake(WeekPlan.DEFAULT, LocalTime.of(9, 30), SATURDAY, SUNDAY))
        assertThat(lieIn.freeDayWakeShift).isEqualTo(Duration.ofMinutes(150))
        assertThat(lieIn.warnFreeDayShift).isTrue()
    }

    @Test
    fun `a two-hour weekend lie-in warns`() {
        val plan = setWake(alarms(weekdays, LocalTime.of(7, 0)), LocalTime.of(9, 0), SATURDAY, SUNDAY)

        val result = planCheck(plan)

        assertThat(result.freeDayWakeShift).isEqualTo(Duration.ofMinutes(120))
        // Asleep 23:15: mid-sleep 04:07:30 on free days, 03:07:30 on alarm days.
        assertThat(result.plannedSocialJetlag).isEqualTo(Duration.ofMinutes(60))
        assertThat(result.warnFreeDayShift).isTrue()
        assertThat(result.warnSocialJetlag).isTrue()
        assertThat(result.warn).isTrue()
        // Mon..Sun twice: Fri→Sat twice and Sun→Mon once disagree for the 2 h of the lie-in.
        assertThat(result.plannedKeel).isEqualTo(PatternFx.sri(pairs = 13 * 1440, mismatches = 3 * 120))
        assertThat(result.plannedKeel).isEqualTo(96)
        assertThat(result.plannedBand).isEqualTo(KeelBand.EVEN)
    }

    @Test
    fun `free days only warn when they wake later`() {
        val earlier = planCheck(setWake(alarms(weekdays, LocalTime.of(7, 0)), LocalTime.of(5, 0), SATURDAY, SUNDAY))

        assertThat(earlier.freeDayWakeShift).isEqualTo(Duration.ofMinutes(-120))
        assertThat(earlier.warnFreeDayShift).isFalse()
        assertThat(earlier.plannedSocialJetlag).isEqualTo(Duration.ofMinutes(-60))
        assertThat(earlier.warnSocialJetlag).isTrue() // |−60| reaches the threshold

        val anHourLater = planCheck(setWake(alarms(weekdays, LocalTime.of(7, 0)), LocalTime.of(8, 0), SATURDAY, SUNDAY))

        assertThat(anHourLater.freeDayWakeShift).isEqualTo(Duration.ofMinutes(60))
        assertThat(anHourLater.plannedSocialJetlag).isEqualTo(Duration.ofMinutes(30))
        assertThat(anHourLater.warn).isFalse() // 60 min isn't more than the 60-min threshold
    }

    @Test
    fun `the planned keel equals the measured keel of nights that follow the plan`() {
        // Varied on purpose: weekday alarms at 06:30, weekends up at 08:30, Friday night in bed at 00:30, the rest by
        // sleep need; in New York, away from any clock change.
        val plan = setWake(alarms(weekdays, LocalTime.of(6, 30)), LocalTime.of(8, 30), SATURDAY, SUNDAY).let { week ->
            week.with(week.day(FRIDAY).copy(evening = EveningPlan(bedtime = LocalTime.of(0, 30))))
        }
        val sleepNeed = settings.copy(bedtimeMode = BedtimeMode.SLEEP_NEED, personalLatency = Duration.ofMinutes(20))
        val last = from.plusDays(13)
        val followed = Fx.nightsFollowing(sleepNeed.copy(week = plan).timing, from, last, Fx.NEW_YORK, need = sleepNeed.sleepNeed)

        val planned = calculator.check(plan, OneOffs(), emptyList(), sleepNeed, from, Fx.NEW_YORK)
        val measured = KeelCalculator().keel(followed, last)

        assertThat(measured).isInstanceOf(KeelResult.Ready::class.java)
        assertThat(planned.plannedKeel).isEqualTo((measured as KeelResult.Ready).score)
        assertThat(planned.plannedKeel).isLessThan(100)
    }

    @Test
    fun `a shift worker's free days come from the plan`() {
        // Alarms Wednesday to Sunday at 06:00; Monday and Tuesday sleep to the usual 09:00.
        val plan = alarms(listOf(WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY), LocalTime.of(6, 0))

        val result = planCheck(plan, settings.copy(typicalWake = LocalTime.of(9, 0)))

        assertThat(result.freeDayWakeShift).isEqualTo(Duration.ofMinutes(180))
        // Asleep 23:15: mid-sleep 04:07:30 on Monday and Tuesday, 02:37:30 on alarm days.
        assertThat(result.plannedSocialJetlag).isEqualTo(Duration.ofMinutes(90))
        assertThat(result.warnFreeDayShift).isTrue()
        assertThat(result.warnSocialJetlag).isTrue()
    }

    @Test
    fun `one-offs in the next two weeks are part of the plan`() {
        val plan = alarms(weekdays, LocalTime.of(7, 0))
        val oneOffs = OneOffs(
            overrides = listOf(
                DateOverride(
                    date = from.plusDays(2),
                    kind = OverrideKind.JUST_ONCE,
                    time = LocalTime.of(5, 0),
                    mode = AlarmMode.FIXED,
                ),
            ),
        )

        val result = calculator.check(plan, oneOffs, emptyList(), settings, from, Fx.UTC)

        // The early Wednesday disagrees with Tuesday and Thursday for 2 h each.
        assertThat(result.plannedKeel).isEqualTo(PatternFx.sri(pairs = 13 * 1440, mismatches = 2 * 120))
        // Weekends at 07:00 against the circular mean of nine 07:00s and one 05:00 (06:48.4).
        assertThat(result.freeDayWakeShift).isEqualTo(Duration.ofMinutes(12))
    }

    @Test
    fun `a single one-off alarm doesn't turn the other days into free days`() {
        val noAlarms = WeekPlan.DEFAULT
        val oneOffs = OneOffs(
            overrides = listOf(
                DateOverride(date = from.plusDays(2), kind = OverrideKind.JUST_ONCE, time = LocalTime.of(5, 0), mode = AlarmMode.FIXED),
            ),
        )

        val result = calculator.check(noAlarms, oneOffs, emptyList(), settings, from, Fx.UTC)

        // Without weekly alarms the weekdays are the alarm days; the early Wednesday is just one of ten.
        assertThat(result.freeDayWakeShift).isEqualTo(Duration.ofMinutes(12))
        assertThat(result.warnFreeDayShift).isFalse()
    }

    @Test
    fun `too few alarm days give no shift`() {
        // Monday alarms only: 2 alarm days in 14, fewer than jetlagMinWork.
        val result = planCheck(alarms(listOf(MONDAY), LocalTime.of(5, 0)))

        assertThat(result.freeDayWakeShift).isNull()
        assertThat(result.plannedSocialJetlag).isNull()
        assertThat(result.warn).isFalse()
    }
}
