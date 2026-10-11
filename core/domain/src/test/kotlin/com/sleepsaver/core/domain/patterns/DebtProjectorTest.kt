package com.sleepsaver.core.domain.patterns

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.debt.SleepDebtCalculator
import com.sleepsaver.core.domain.model.BedtimeMode
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.model.WakeTimeMode
import com.sleepsaver.core.domain.schedule.EveningPlan
import com.sleepsaver.core.domain.testing.Fx
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.THURSDAY
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.jupiter.api.Test

class DebtProjectorTest {

    private val projector = DebtProjector()
    private val debt = SleepDebtCalculator()
    private val need = Duration.ofHours(8)

    /** Wednesday 2026-10-07. */
    private val wednesday = LocalDate.of(2026, 10, 7)
    private val thursday = wednesday.plusDays(1)
    private val saturday = wednesday.plusDays(3)
    private val evening = at(wednesday, "20:00")

    /** Two weeks of 23:00 → 07:00 with an hour awake (7 h asleep), the last one this morning. */
    private val history: List<Night> = (13L downTo 0L).map { Fx.night(wednesday.minusDays(it), "23:00", "07:00", total = Duration.ofHours(7)) }

    /** Wake 07:00, bed 23:00, 15 min to fall asleep. */
    private val settings = UserSleepSettings(
        sleepNeed = need,
        typicalWake = LocalTime.of(7, 0),
        wakeTimeMode = WakeTimeMode.FIXED,
        bedtime = LocalTime.of(23, 0),
        bedtimeMode = BedtimeMode.FIXED,
    )

    private fun at(date: LocalDate, time: String): Instant = Fx.instant(date, time)
    private fun h(hours: Long, minutes: Long = 0): Duration = Duration.ofMinutes(hours * 60 + minutes)
    private fun debtWith(asOf: LocalDate, nights: List<Night>): Duration = debt.debtAsOf(asOf, nights.associateBy { it.wakeDate }, need).debt

    @Test
    fun `tomorrow's debt equals the calculator's for the same night slept for real`() {
        val preview = projector.project(history, settings, evening, Fx.UTC, thursday)
        // 23:15 → 07:00 is 7 h 45; minus the usual hour awake.
        val real = Fx.night(thursday, "23:15", "07:00", total = h(6, 45))

        assertThat(preview.wakeDate).isEqualTo(thursday)
        assertThat(preview.asleepBy).isEqualTo(at(wednesday, "23:15"))
        assertThat(preview.projectedDebt).isEqualTo(debtWith(thursday, history + real))
        assertThat(preview.change).isEqualTo(preview.projectedDebt - debtWith(wednesday, history))
        assertThat(preview.enoughHistory).isTrue()
        assertThat(preview.presentNights).isEqualTo(14)
    }

    @Test
    fun `a projection over several days follows each day's plan`() {
        val week = Fx.weekPlan(THURSDAY to LocalTime.of(6, 0)).let { plan ->
            plan.with(plan.day(FRIDAY).copy(evening = EveningPlan(bedtime = LocalTime.of(0, 30))))
        }

        val preview = projector.project(history, settings.copy(week = week), evening, Fx.UTC, saturday)
        val real = listOf(
            Fx.night(thursday, "23:15", "06:00", total = h(5, 45)),
            Fx.night(thursday.plusDays(1), "23:15", "07:00", total = h(6, 45)),
            Fx.night(saturday, "00:45", "07:00", total = h(5, 15)),
        )

        assertThat(preview.asleepBy).isEqualTo(at(saturday, "00:45"))
        assertThat(preview.projectedDebt).isEqualTo(debtWith(saturday, history + real))
    }

    @Test
    fun `a later wake time never increases the projected debt`() {
        for (mode in BedtimeMode.values()) {
            val projected = (0L..28L).map { step ->
                val wake = LocalTime.of(4, 0).plusMinutes(15 * step)
                projector.project(history, settings.copy(bedtimeMode = mode), evening, Fx.UTC, thursday, wakeOverride = wake).projectedDebt
            }

            assertThat(projected).isInOrder(Comparator.reverseOrder<Duration>())
        }
    }

    @Test
    fun `a scrubbed wake time moves a sleep-need bedtime with it`() {
        val sleepNeed = settings.copy(bedtimeMode = BedtimeMode.SLEEP_NEED)

        val preview = projector.project(history, sleepNeed, evening, Fx.UTC, thursday, wakeOverride = LocalTime.of(8, 0))

        // 08:00 − 8 h − 15 min, then 15 min to fall asleep.
        assertThat(preview.asleepBy).isEqualTo(at(thursday, "00:00"))
    }

    @Test
    fun `a scrubbed bedtime replaces the plan's`() {
        val preview = projector.project(history, settings, evening, Fx.UTC, thursday, bedtimeOverride = LocalTime.of(22, 0))
        val real = Fx.night(thursday, "22:15", "07:00", total = h(7, 45))

        assertThat(preview.asleepBy).isEqualTo(at(wednesday, "22:15"))
        assertThat(preview.projectedDebt).isEqualTo(debtWith(thursday, history + real))
    }

    @Test
    fun `the personal latency is used`() {
        val preview = projector.project(history, settings.copy(personalLatency = Duration.ofMinutes(30)), evening, Fx.UTC, thursday)
        val real = Fx.night(thursday, "23:30", "07:00", total = h(6, 30))

        assertThat(preview.asleepBy).isEqualTo(at(wednesday, "23:30"))
        assertThat(preview.projectedDebt).isEqualTo(debtWith(thursday, history + real))
        assertThat(preview.projectedDebt).isGreaterThan(projector.project(history, settings, evening, Fx.UTC, thursday).projectedDebt)
    }

    @Test
    fun `before last night is in, it is projected too`() {
        val earlier = history.dropLast(1)

        val preview = projector.project(earlier, settings, at(wednesday, "05:00"), Fx.UTC, wednesday)
        val real = Fx.night(wednesday, "23:15", "07:00", total = h(6, 45))

        assertThat(preview.asleepBy).isEqualTo(at(wednesday.minusDays(1), "23:15"))
        assertThat(preview.projectedDebt).isEqualTo(debtWith(wednesday, earlier + real))
        assertThat(preview.change).isEqualTo(preview.projectedDebt - debtWith(wednesday.minusDays(1), earlier))
    }

    @Test
    fun `a wake date whose night is in shows that night's debt unchanged`() {
        val preview = projector.project(history, settings, evening, Fx.UTC, wednesday)

        assertThat(preview.projectedDebt).isEqualTo(debtWith(wednesday, history))
        assertThat(preview.change).isEqualTo(Duration.ZERO)
        assertThat(preview.asleepBy).isEqualTo(at(wednesday.minusDays(1), "23:15"))
    }

    @Test
    fun `enough history needs three present nights in the last two weeks`() {
        val two = listOf(Fx.night(wednesday.minusDays(1)), Fx.night(wednesday))
        val oldAndTwo = listOf(Fx.night(wednesday.minusDays(14))) + two
        val three = listOf(Fx.night(wednesday.minusDays(13))) + two

        assertThat(projector.project(two, settings, evening, Fx.UTC, thursday).enoughHistory).isFalse()
        assertThat(projector.project(oldAndTwo, settings, evening, Fx.UTC, thursday).presentNights).isEqualTo(2)
        assertThat(projector.project(oldAndTwo, settings, evening, Fx.UTC, thursday).enoughHistory).isFalse()
        assertThat(projector.project(three, settings, evening, Fx.UTC, thursday).presentNights).isEqualTo(3)
        assertThat(projector.project(three, settings, evening, Fx.UTC, thursday).enoughHistory).isTrue()
    }

    @Test
    fun `without any nights nothing is subtracted for awake time and this morning is projected too`() {
        val preview = projector.project(emptyList(), settings, evening, Fx.UTC, thursday)
        val real = listOf(Fx.night(wednesday, "23:15", "07:00"), Fx.night(thursday, "23:15", "07:00"))

        // 7 h 45 planned against an 8 h need, tonight and (not synced yet) last night: 15 min + 0.85 · 15 min.
        assertThat(preview.projectedDebt).isEqualTo(Duration.ofSeconds(1665))
        assertThat(preview.projectedDebt).isEqualTo(debtWith(thursday, real))
        assertThat(preview.change).isEqualTo(preview.projectedDebt)
        assertThat(preview.enoughHistory).isFalse()
        assertThat(preview.presentNights).isEqualTo(0)
    }
}
