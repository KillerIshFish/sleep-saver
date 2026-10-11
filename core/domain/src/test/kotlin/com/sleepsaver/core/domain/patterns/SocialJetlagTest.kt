package com.sleepsaver.core.domain.patterns

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.OverrideKind
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.domain.testing.Fx
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
import java.time.ZoneOffset
import org.junit.jupiter.api.Test

class SocialJetlagTest {

    private val calculator = SocialJetlagCalculator()

    /** Sunday 2026-10-11. */
    private val asOf = LocalDate.of(2026, 10, 11)

    /** The 28 wake dates in (asOf − 28, asOf], oldest first. */
    private val window: List<LocalDate> = PatternFx.dates(28, asOf)

    private val weekdayAlarms: WeekPlan = Fx.weekPlan(*listOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY).map { it to LocalTime.of(7, 0) }.toTypedArray())

    /** One night per date: [free] dates sleep [freeTimes], the others [workTimes]. */
    private fun nights(
        dates: List<LocalDate>,
        free: (LocalDate) -> Boolean,
        workTimes: Pair<String, String> = "23:00" to "07:00",
        freeTimes: Pair<String, String> = "00:00" to "09:00",
        freeOffset: ZoneOffset = Fx.UTC,
    ): List<Night> = dates.map { date ->
        if (free(date)) {
            Fx.night(date, freeTimes.first, freeTimes.second, offset = freeOffset)
        } else {
            Fx.night(date, workTimes.first, workTimes.second)
        }
    }

    private fun weekend(date: LocalDate) = date.dayOfWeek == SATURDAY || date.dayOfWeek == SUNDAY

    @Test
    fun `without alarms the weekend is free`() {
        // Mid-sleep 03:00 on weekdays, 04:30 on weekends.
        val result = calculator.compute(nights(window, ::weekend), WeekPlan.DEFAULT, OneOffs(), asOf)

        assertThat(result).isEqualTo(SocialJetlag(shift = Duration.ofMinutes(90), freeNights = 8, workNights = 20, freeDaysFromPlan = false))
    }

    @Test
    fun `with alarms the plan's free days count`() {
        // A shift worker: alarms Wednesday to Sunday at 06:00, Monday and Tuesday free.
        val plan = Fx.weekPlan(*listOf(WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY).map { it to LocalTime.of(6, 0) }.toTypedArray())
        val free = { date: LocalDate -> date.dayOfWeek == MONDAY || date.dayOfWeek == TUESDAY }
        val nights = nights(window, free, workTimes = "22:00" to "06:00", freeTimes = "01:00" to "10:00")

        val result = calculator.compute(nights, plan, OneOffs(), asOf)

        // Mid-sleep 02:00 on alarm days, 05:30 on free days.
        assertThat(result).isEqualTo(SocialJetlag(shift = Duration.ofMinutes(210), freeNights = 8, workNights = 20, freeDaysFromPlan = true))
    }

    @Test
    fun `a day off makes an alarm day free`() {
        val dayOff = asOf.minusDays(4) // Wednesday 7 October
        check(dayOff.dayOfWeek == WEDNESDAY)
        val oneOffs = OneOffs(overrides = listOf(DateOverride(dayOff, OverrideKind.DAY_OFF)))

        val result = calculator.compute(nights(window, { weekend(it) || it == dayOff }), weekdayAlarms, oneOffs, asOf)

        assertThat(result?.freeNights).isEqualTo(9)
        assertThat(result?.workNights).isEqualTo(19)
        assertThat(result?.shift).isEqualTo(Duration.ofMinutes(90))
        assertThat(result?.freeDaysFromPlan).isTrue()
    }

    @Test
    fun `free days that run earlier give a negative shift`() {
        val result = calculator.compute(nights(window, ::weekend, freeTimes = "22:00" to "05:00"), WeekPlan.DEFAULT, OneOffs(), asOf)

        // 01:30 against 03:00.
        assertThat(result?.shift).isEqualTo(Duration.ofMinutes(-90))
    }

    @Test
    fun `mid-sleep averages across midnight`() {
        // Alarm days 20:00–03:00 (mid 23:30), free days 22:00–05:00 (mid 01:30).
        val nights = nights(window, ::weekend, workTimes = "20:00" to "03:00", freeTimes = "22:00" to "05:00")

        assertThat(calculator.compute(nights, WeekPlan.DEFAULT, OneOffs(), asOf)?.shift).isEqualTo(Duration.ofMinutes(120))
    }

    @Test
    fun `mid-sleep is read in each night's own offset`() {
        // Weekend nights abroad at +05:00, same local times as at home.
        val nights = nights(window, ::weekend, freeOffset = ZoneOffset.ofHours(5))

        assertThat(calculator.compute(nights, WeekPlan.DEFAULT, OneOffs(), asOf)?.shift).isEqualTo(Duration.ofMinutes(90))
    }

    @Test
    fun `only the last 28 wake dates count`() {
        val old = PatternFx.dates(14, asOf.minusDays(28)) // asOf − 41 .. asOf − 28, all just outside
        val nights = nights(old, { true }, freeTimes = "04:00" to "13:00") + nights(window, ::weekend)

        val result = calculator.compute(nights, WeekPlan.DEFAULT, OneOffs(), asOf)

        assertThat(result).isEqualTo(SocialJetlag(shift = Duration.ofMinutes(90), freeNights = 8, workNights = 20, freeDaysFromPlan = false))
    }

    @Test
    fun `too few free or alarm nights give nothing`() {
        val lastWeek = PatternFx.dates(7, asOf) // Mon 5 .. Sun 11 October
        val oneFree = nights(lastWeek.filter { it.dayOfWeek != SATURDAY }, ::weekend)
        val twoWork = nights(lastWeek.filter { weekend(it) || it.dayOfWeek == MONDAY || it.dayOfWeek == TUESDAY }, ::weekend)
        val enough = nights(lastWeek.filter { weekend(it) || it.dayOfWeek in setOf(MONDAY, TUESDAY, WEDNESDAY) }, ::weekend)

        assertThat(calculator.compute(oneFree, WeekPlan.DEFAULT, OneOffs(), asOf)).isNull()
        assertThat(calculator.compute(twoWork, WeekPlan.DEFAULT, OneOffs(), asOf)).isNull()
        assertThat(calculator.compute(enough, WeekPlan.DEFAULT, OneOffs(), asOf))
            .isEqualTo(SocialJetlag(shift = Duration.ofMinutes(90), freeNights = 2, workNights = 3, freeDaysFromPlan = false))
    }

    @Test
    fun `nights that aren't present are skipped`() {
        val missing = Fx.night(asOf.minusDays(1), "02:00", "12:00").copy(main = null, fellAsleep = null, wokeUp = null)

        val result = calculator.compute(nights(window.dropLast(2), ::weekend) + missing, WeekPlan.DEFAULT, OneOffs(), asOf)

        assertThat(result?.freeNights).isEqualTo(6)
    }
}
