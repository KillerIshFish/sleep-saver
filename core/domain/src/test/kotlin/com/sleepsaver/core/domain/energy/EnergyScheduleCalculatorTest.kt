package com.sleepsaver.core.domain.energy

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.AwakeKind
import com.sleepsaver.core.domain.model.AwakePeriod
import com.sleepsaver.core.domain.model.BlockKind
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.domain.model.SleepBlock
import com.sleepsaver.core.domain.testing.Fx
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.jupiter.api.Test

class EnergyScheduleCalculatorTest {

    private val calculator = EnergyScheduleCalculator()
    private val date = LocalDate.of(2026, 9, 30)
    private val need = Duration.ofHours(8)
    private val fallback = LocalTime.of(7, 0)

    private fun nights(days: LongRange, fellAsleep: String, wokeUp: String, offset: ZoneOffset = Fx.UTC): List<Night> =
        days.map { Fx.night(date.minusDays(it), fellAsleep, wokeUp, offset = offset) }

    private fun at(day: LocalDate, time: String, zone: ZoneId = Fx.UTC): Instant =
        ZonedDateTime.of(day, LocalTime.parse(time), zone).toInstant()

    private fun assertNear(actual: Instant, expected: Instant, toleranceMinutes: Long) {
        assertThat(Duration.between(expected, actual).abs()).isAtMost(Duration.ofMinutes(toleranceMinutes))
    }

    private fun EnergySchedule.get(type: PhaseType) = phase(type)!!

    private fun assertOrderedWithoutGaps(schedule: EnergySchedule) {
        assertThat(schedule.phases.map { it.type }).containsExactlyElementsIn(PhaseType.entries).inOrder()
        schedule.phases.forEach { assertThat(it.end).isGreaterThan(it.start) }
        schedule.phases.zipWithNext().forEach { (a, b) -> assertThat(b.start).isAtLeast(a.start) }
    }

    @Test
    fun `early bird schedule`() {
        val s = calculator.schedule(date, Fx.UTC, nights(0L..6L, "22:00", "06:00"), need, Duration.ZERO, fallback)
        val tomorrow = date.plusDays(1)

        assertThat(s.typicalWake).isEqualTo(ZonedDateTime.of(date, LocalTime.of(6, 0), Fx.UTC))
        assertThat(s.actualWake).isEqualTo(at(date, "06:00"))
        assertThat(s.targetBedtime).isEqualTo(at(date, "21:45"))
        assertThat(s.get(PhaseType.MELATONIN_WINDOW).start).isEqualTo(at(date, "21:15"))
        assertThat(s.get(PhaseType.MELATONIN_WINDOW).end).isEqualTo(at(date, "22:15"))
        assertThat(s.get(PhaseType.WIND_DOWN).start).isEqualTo(at(date, "20:15"))
        assertThat(s.get(PhaseType.WIND_DOWN).end).isEqualTo(at(date, "21:15"))
        assertThat(s.wakeZone.start).isEqualTo(at(date, "05:30"))
        assertThat(s.wakeZone.end).isEqualTo(at(date, "06:30"))
        assertThat(s.tomorrowWakeZone.start).isEqualTo(at(tomorrow, "05:30"))
        assertThat(s.tomorrowWakeZone.end).isEqualTo(at(tomorrow, "06:30"))

        assertThat(s.get(PhaseType.GROGGINESS).start).isEqualTo(at(date, "06:00"))
        assertNear(s.get(PhaseType.GROGGINESS).end, at(date, "07:00"), 2)
        assertNear(s.get(PhaseType.MORNING_PEAK).start, at(date, "08:00"), 20)
        assertNear(s.get(PhaseType.MORNING_PEAK).end, at(date, "11:00"), 20)
        assertNear(s.get(PhaseType.AFTERNOON_DIP).start, at(date, "12:30"), 20)
        assertNear(s.get(PhaseType.AFTERNOON_DIP).end, at(date, "15:00"), 20)
        assertNear(s.get(PhaseType.EVENING_PEAK).start, at(date, "16:00"), 20)
        assertNear(s.get(PhaseType.EVENING_PEAK).end, at(date, "19:00"), 20)

        assertOrderedWithoutGaps(s)
        s.phases.zipWithNext().forEach { (a, b) -> assertThat(b.start).isAtLeast(a.end) }
        assertThat(s.phases.map { it.shiftVsYesterday }).containsExactlyElementsIn(List(6) { null })
        assertThat(s.anchor.isFallback).isFalse()
    }

    @Test
    fun `curve spans actual wake to bedtime, normalised to 0 and 1`() {
        val s = calculator.schedule(date, Fx.UTC, nights(0L..6L, "22:00", "06:00"), need, Duration.ZERO, fallback)
        val values = s.curve.points.map { it.value }

        assertThat(s.curve.points.first().time).isEqualTo(at(date, "06:00"))
        assertThat(s.curve.points.last().time).isEqualTo(at(date, "21:45"))
        assertThat(s.curve.points).hasSize(190)
        s.curve.points.zipWithNext().forEach { (a, b) ->
            assertThat(Duration.between(a.time, b.time)).isEqualTo(Duration.ofMinutes(5))
        }
        values.forEach {
            assertThat(it).isAtLeast(0f)
            assertThat(it).isAtMost(1f)
        }
        assertThat(values.min()).isEqualTo(0f)
        assertThat(values.max()).isEqualTo(1f)
    }

    @Test
    fun `curve ends exactly at bedtime when the actual wake is off the 5-minute grid`() {
        val history = nights(1L..6L, "23:00", "07:00") + Fx.night(date, "23:00", "06:47:23")

        val s = calculator.schedule(date, Fx.UTC, history, need, Duration.ZERO, fallback)

        assertThat(s.curve.points.first().time).isEqualTo(s.actualWake)
        assertThat(s.curve.points.last().time).isEqualTo(s.targetBedtime)
        assertThat(s.curve.valueAt(s.targetBedtime)).isNotNull()
        s.curve.points.zipWithNext().forEach { (a, b) ->
            assertThat(Duration.between(a.time, b.time)).isGreaterThan(Duration.ZERO)
            assertThat(Duration.between(a.time, b.time)).isAtMost(Duration.ofMinutes(5))
        }
    }

    @Test
    fun `waking after the target bedtime gives a one-point flat curve`() {
        // Slept through the day: 13:00 → 23:30 on the schedule date.
        val history = nights(1L..6L, "23:00", "07:00") + Fx.night(date, "13:00", "23:30")

        val s = calculator.schedule(date, Fx.UTC, history, need, Duration.ZERO, fallback)

        assertThat(s.actualWake).isEqualTo(at(date, "23:30"))
        assertThat(s.actualWake).isGreaterThan(s.targetBedtime)
        assertThat(s.curve.points.map { it.time }).containsExactly(at(date, "23:30"))
        assertThat(s.curve.points.single().value).isEqualTo(0.5f)
        assertThat(s.phases.map { it.type }).containsExactlyElementsIn(PhaseType.entries).inOrder()
    }

    @Test
    fun `after a very late wake no energy phase starts inside grogginess`() {
        val history = nights(1L..6L, "23:00", "07:00") + nights(0L..0L, "04:00", "13:00")

        val s = calculator.schedule(date, Fx.UTC, history, need, Duration.ZERO, fallback)

        val grogginessEnd = s.get(PhaseType.GROGGINESS).end
        assertThat(s.actualWake).isEqualTo(at(date, "13:00"))
        listOf(PhaseType.MORNING_PEAK, PhaseType.AFTERNOON_DIP, PhaseType.EVENING_PEAK).forEach {
            assertThat(s.get(it).start).isAtLeast(grogginessEnd)
        }
        assertOrderedWithoutGaps(s)
    }

    @Test
    fun `night owl schedule is shifted late and bedtime crosses midnight`() {
        val s = calculator.schedule(date, Fx.UTC, nights(0L..6L, "02:30", "10:30"), need, Duration.ZERO, fallback)
        val tomorrow = date.plusDays(1)

        assertThat(s.typicalWake.toLocalTime()).isEqualTo(LocalTime.of(10, 30))
        assertThat(s.targetBedtime).isEqualTo(at(tomorrow, "02:15"))
        assertThat(s.get(PhaseType.MELATONIN_WINDOW).start).isEqualTo(at(tomorrow, "01:45"))
        assertThat(s.get(PhaseType.MELATONIN_WINDOW).end).isEqualTo(at(tomorrow, "02:45"))
        assertThat(s.get(PhaseType.WIND_DOWN).start).isEqualTo(at(tomorrow, "00:45"))
        assertThat(s.get(PhaseType.WIND_DOWN).end).isEqualTo(at(tomorrow, "01:45"))
        assertThat(s.wakeZone.start).isEqualTo(at(date, "10:00"))
        assertThat(s.tomorrowWakeZone.end).isEqualTo(at(tomorrow, "11:00"))

        assertThat(s.get(PhaseType.GROGGINESS).start).isEqualTo(at(date, "10:30"))
        assertNear(s.get(PhaseType.GROGGINESS).end, at(date, "11:30"), 2)
        assertNear(s.get(PhaseType.MORNING_PEAK).start, at(date, "12:30"), 20)
        assertNear(s.get(PhaseType.MORNING_PEAK).end, at(date, "15:30"), 20)
        assertNear(s.get(PhaseType.AFTERNOON_DIP).start, at(date, "17:00"), 20)
        assertNear(s.get(PhaseType.AFTERNOON_DIP).end, at(date, "19:30"), 20)
        assertNear(s.get(PhaseType.EVENING_PEAK).start, at(date, "20:30"), 20)
        assertNear(s.get(PhaseType.EVENING_PEAK).end, at(date, "23:30"), 20)
        assertOrderedWithoutGaps(s)
        assertThat(s.curve.points.last().time).isEqualTo(at(tomorrow, "02:15"))
    }

    @Test
    fun `short night with debt lengthens grogginess and moves typical wake later`() {
        val history = nights(1L..6L, "23:00", "07:00") + nights(0L..0L, "03:00", "07:00")
        val steady = calculator.schedule(date, Fx.UTC, nights(0L..6L, "23:00", "07:00"), need, Duration.ZERO, fallback)
        val rested = calculator.schedule(date, Fx.UTC, history, need, Duration.ZERO, fallback)
        val indebted = calculator.schedule(date, Fx.UTC, history, need, Duration.ofHours(5), fallback)

        val groggy = indebted.get(PhaseType.GROGGINESS)
        val groggyLength = Duration.between(groggy.start, groggy.end)
        assertThat(groggyLength).isGreaterThan(rested.get(PhaseType.GROGGINESS).let { Duration.between(it.start, it.end) })
        assertThat(groggyLength).isAtMost(Duration.ofMinutes(120))
        // k = 0.15 · (1 + 5/4) = 0.3375 → 30 m · ln(0.3375 / 0.02) ≈ 84.8 m
        assertThat(groggyLength.seconds / 60.0).isWithin(0.1).of(84.77)
        assertThat(indebted.get(PhaseType.MORNING_PEAK).start).isAtLeast(groggy.end)

        // Midpoints: last night 05:00 (weight 1), six nights at 03:00 → weighted circular mean ≈ 03:26, so W = 07:26.
        assertThat(steady.typicalWake.toLocalTime()).isEqualTo(LocalTime.of(7, 0))
        assertThat(indebted.anchor.midpointMinuteOfDay).isWithin(0.5).of(206.0)
        assertThat(indebted.typicalWake.toLocalTime()).isEqualTo(LocalTime.of(7, 26))
        assertThat(indebted.actualWake).isEqualTo(at(date, "07:00"))
        assertThat(indebted.get(PhaseType.GROGGINESS).start).isEqualTo(at(date, "07:00"))
        assertOrderedWithoutGaps(indebted)
    }

    @Test
    fun `a phase squeezed by long grogginess keeps the minimum length`() {
        val longGrogginess = EnergyScheduleCalculator(
            SleepConfig(grogginessMin = Duration.ofHours(5), grogginessMax = Duration.ofHours(5)),
        )
        val s = longGrogginess.schedule(date, Fx.UTC, nights(0L..6L, "23:00", "07:00"), need, Duration.ZERO, fallback)

        val morning = s.get(PhaseType.MORNING_PEAK)
        assertThat(morning.start).isEqualTo(at(date, "12:00"))
        assertThat(morning.end).isEqualTo(at(date, "12:30"))
    }

    @Test
    fun `without last night the curve starts at typical wake`() {
        val s = calculator.schedule(date, Fx.UTC, nights(1L..7L, "23:00", "07:00"), need, Duration.ZERO, fallback)

        assertThat(s.actualWake).isNull()
        assertThat(s.curve.points.first().time).isEqualTo(at(date, "07:00"))
        assertThat(s.get(PhaseType.GROGGINESS).start).isEqualTo(at(date, "07:00"))
    }

    @Test
    fun `a night that hasn't synced yet counts as a typical night, not a night awake`() {
        val waiting = calculator.schedule(date, Fx.UTC, nights(1L..7L, "23:00", "07:00"), need, Duration.ZERO, fallback)
        val arrived = calculator.schedule(
            date, Fx.UTC, listOf(Fx.night(date, "23:10", "07:05")) + nights(1L..7L, "23:00", "07:00"),
            need, Duration.ZERO, fallback,
        )

        assertThat(waiting.actualWake).isNull()
        assertThat(arrived.actualWake).isEqualTo(at(date, "07:05"))
        waiting.phases.zip(arrived.phases).forEach { (before, after) ->
            assertThat(before.type).isEqualTo(after.type)
            assertNear(before.start, after.start, 5)
            assertNear(before.end, after.end, 5)
        }
        // The nominal night is exactly what a steady sleeper records, so the same night arriving changes nothing.
        val steady = calculator.schedule(date, Fx.UTC, nights(0L..7L, "23:00", "07:00"), need, Duration.ZERO, fallback)
        assertThat(waiting.phases).isEqualTo(steady.phases)
        assertThat(waiting.curve).isEqualTo(steady.curve)
    }

    @Test
    fun `a missing night inside the history counts as a typical night`() {
        val full = nights(0L..7L, "23:00", "07:00")
        val gap = full.filterNot { it.wakeDate == date.minusDays(2) }

        val withGap = calculator.schedule(date, Fx.UTC, gap, need, Duration.ZERO, fallback)
        val complete = calculator.schedule(date, Fx.UTC, full, need, Duration.ZERO, fallback)

        assertThat(withGap.curve).isEqualTo(complete.curve)
        assertThat(withGap.phases).isEqualTo(complete.phases)
    }

    @Test
    fun `a nap-only day adds its nap to a typical night`() {
        val full = nights(0L..7L, "23:00", "07:00")
        val napDay = date.minusDays(1)
        val afternoonNap = nap(at(napDay, "14:00"), at(napDay, "14:40"))
        val napOnly = full.map { if (it.wakeDate == napDay) it.copy(main = null, naps = listOf(afternoonNap)) else it }
        val nightAndNap = full.map { if (it.wakeDate == napDay) it.copy(naps = listOf(afternoonNap)) else it }

        val s = calculator.schedule(date, Fx.UTC, napOnly, need, Duration.ZERO, fallback)

        assertThat(s.curve).isEqualTo(calculator.schedule(date, Fx.UTC, nightAndNap, need, Duration.ZERO, fallback).curve)
    }

    @Test
    fun `a brand-new user gets the nominal phases`() {
        val s = calculator.schedule(date, Fx.UTC, emptyList(), need, Duration.ZERO, LocalTime.of(7, 0))

        assertThat(s.anchor.isFallback).isTrue()
        assertThat(s.actualWake).isNull()
        assertThat(s.phases).isEqualTo(
            calculator.schedule(date, Fx.UTC, nights(0L..6L, "23:00", "07:00"), need, Duration.ZERO, fallback).phases,
        )
        // Within the tuning tolerance of the guardrails' nominal windows (W = 07:00), far from the ±45 min limits.
        assertNear(s.get(PhaseType.MORNING_PEAK).start, at(date, "09:00"), 20)
        assertNear(s.get(PhaseType.AFTERNOON_DIP).start, at(date, "13:30"), 20)
        assertNear(s.get(PhaseType.EVENING_PEAK).start, at(date, "17:00"), 20)
        assertOrderedWithoutGaps(s)
    }

    @Test
    fun `nights after the schedule date are ignored`() {
        val past = nights(0L..6L, "23:00", "07:00")
        val future = (1L..5L).map { Fx.night(date.plusDays(it), "03:00", "11:00") }

        val s = calculator.schedule(date, Fx.UTC, past + future, need, Duration.ZERO, fallback)

        assertThat(s).isEqualTo(calculator.schedule(date, Fx.UTC, past, need, Duration.ZERO, fallback))
    }

    @Test
    fun `awake periods and naps feed process S`() {
        val plain = nights(0L..6L, "23:00", "07:00")
        val lastNight = plain.first()
        val restless = lastNight.copy(
            main = lastNight.main!!.copy(
                awakePeriods = listOf(AwakePeriod(at(date, "01:00"), at(date, "04:00"), AwakeKind.STAGE)),
            ),
        )
        val napped = plain[1].copy(naps = listOf(nap(at(date.minusDays(1), "18:00"), at(date.minusDays(1), "19:30"))))

        val base = calculator.schedule(date, Fx.UTC, plain, need, Duration.ZERO, fallback)
        val withAwake = calculator.schedule(date, Fx.UTC, listOf(restless) + plain.drop(1), need, Duration.ZERO, fallback)
        val withNap = calculator.schedule(date, Fx.UTC, plain.take(1) + napped + plain.drop(2), need, Duration.ZERO, fallback)

        assertThat(withAwake.curve).isNotEqualTo(base.curve)
        assertThat(withNap.curve).isNotEqualTo(base.curve)
        assertThat(withAwake.typicalWake).isEqualTo(base.typicalWake)
    }

    @Test
    fun `awake time inside a nap counts as awake`() {
        val plain = nights(0L..6L, "23:00", "07:00")
        val napDay = date.minusDays(1)
        val restlessNap = nap(at(napDay, "14:00"), at(napDay, "16:00")).copy(
            awakePeriods = listOf(AwakePeriod(at(napDay, "14:10"), at(napDay, "15:50"), AwakeKind.STAGE)),
        )
        fun withNaps(vararg naps: SleepBlock) = plain.map { if (it.wakeDate == napDay) it.copy(naps = naps.toList()) else it }

        val restless = calculator.schedule(date, Fx.UTC, withNaps(restlessNap), need, Duration.ZERO, fallback)
        val twoShortNaps = calculator.schedule(
            date, Fx.UTC,
            withNaps(nap(at(napDay, "14:00"), at(napDay, "14:10")), nap(at(napDay, "15:50"), at(napDay, "16:00"))),
            need, Duration.ZERO, fallback,
        )
        val solidNap = calculator.schedule(
            date, Fx.UTC, withNaps(nap(at(napDay, "14:00"), at(napDay, "16:00"))), need, Duration.ZERO, fallback,
        )

        assertThat(restless.curve).isEqualTo(twoShortNaps.curve)
        assertThat(restless.curve).isNotEqualTo(solidNap.curve)
    }

    @Test
    fun `DST spring-forward day resolves a typical wake inside the gap forward`() {
        val dstDay = LocalDate.of(2026, 3, 8)
        val s = calculator.schedule(dstDay, Fx.NEW_YORK, emptyList(), need, Duration.ZERO, LocalTime.of(2, 30))

        assertThat(s.anchor.isFallback).isTrue()
        assertThat(s.typicalWake.toLocalTime()).isEqualTo(LocalTime.of(3, 30))
        assertThat(s.typicalWake.offset).isEqualTo(ZoneOffset.ofHours(-4))
        assertThat(s.targetBedtime).isEqualTo(at(LocalDate.of(2026, 3, 8), "18:15", Fx.NEW_YORK))
        assertOrderedWithoutGaps(s)
    }

    @Test
    fun `DST night recorded with two offsets doesn't break the schedule`() {
        val dstDay = LocalDate.of(2026, 3, 8)
        val nights = (0L..6L).map { nightInZone(dstDay.minusDays(it), "23:00", "07:00", Fx.NEW_YORK) }

        val s = calculator.schedule(dstDay, Fx.NEW_YORK, nights, need, Duration.ZERO, fallback)
        val dayAfter = calculator.schedule(dstDay.plusDays(1), Fx.NEW_YORK, nights, need, Duration.ZERO, fallback)

        // The DST night is 7 h long; its midpoint instant reads 03:30 EDT, so W drifts a few minutes later.
        val drift = Duration.between(LocalTime.of(7, 0), s.typicalWake.toLocalTime())
        assertThat(drift).isAtLeast(Duration.ZERO)
        assertThat(drift).isAtMost(Duration.ofMinutes(15))
        assertThat(s.actualWake).isEqualTo(at(dstDay, "07:00", Fx.NEW_YORK))
        assertOrderedWithoutGaps(s)
        assertOrderedWithoutGaps(dayAfter)
    }

    @Test
    fun `schedule in another zone uses the wall-clock midpoints the nights were recorded in`() {
        val day = LocalDate.of(2026, 10, 6)
        val newYork = ZoneOffset.ofHours(-4)
        val nights = (1L..7L).map { Fx.night(day.minusDays(it), "23:00", "07:00", offset = newYork) }

        val s = calculator.schedule(day, Fx.LONDON, nights, need, Duration.ZERO, fallback)

        assertThat(s.anchor.midpointMinuteOfDay).isWithin(1e-6).of(180.0)
        assertThat(s.typicalWake).isEqualTo(ZonedDateTime.of(day, LocalTime.of(7, 0), Fx.LONDON))
        assertThat(s.targetBedtime).isEqualTo(at(day, "22:45", Fx.LONDON))
        assertThat(s.actualWake).isNull()
        assertOrderedWithoutGaps(s)
    }

    @Test
    fun `shifts compare start times of day with yesterday`() {
        val yesterday = calculator.schedule(date.minusDays(1), Fx.UTC, emptyList(), need, Duration.ZERO, LocalTime.of(7, 0))
        val today = calculator.schedule(date, Fx.UTC, emptyList(), need, Duration.ZERO, LocalTime.of(6, 45))

        val shifted = calculator.withShifts(today, yesterday)

        assertThat(shifted.phases.map { it.shiftVsYesterday }).containsExactlyElementsIn(List(6) { Duration.ofMinutes(-15) })
        assertThat(shifted.phases.map { it.copy(shiftVsYesterday = null) }).isEqualTo(today.phases)
        assertThat(shifted.copy(phases = today.phases)).isEqualTo(today)
    }

    @Test
    fun `shifts wrap around midnight`() {
        // Melatonin window starts 23:50 yesterday and 00:10 (the next calendar day) today: 20 min later, not 23 h 40 m earlier.
        val yesterday = calculator.schedule(date.minusDays(1), Fx.UTC, emptyList(), need, Duration.ZERO, LocalTime.of(8, 35))
        val today = calculator.schedule(date, Fx.UTC, emptyList(), need, Duration.ZERO, LocalTime.of(8, 55))

        val shifted = calculator.withShifts(today, yesterday)

        assertThat(yesterday.get(PhaseType.MELATONIN_WINDOW).start).isEqualTo(at(date.minusDays(1), "23:50"))
        assertThat(today.get(PhaseType.MELATONIN_WINDOW).start).isEqualTo(at(date.plusDays(1), "00:10"))
        assertThat(shifted.phases.map { it.shiftVsYesterday }).containsExactlyElementsIn(List(6) { Duration.ofMinutes(20) })
    }

    @Test
    fun `no yesterday or a missing phase leaves shifts null`() {
        val yesterday = calculator.schedule(date.minusDays(1), Fx.UTC, emptyList(), need, Duration.ZERO, LocalTime.of(7, 0))
        val today = calculator.schedule(date, Fx.UTC, emptyList(), need, Duration.ZERO, LocalTime.of(7, 10))

        val noYesterday = calculator.withShifts(today, null)
        val partial = calculator.withShifts(
            today,
            yesterday.copy(phases = yesterday.phases.filterNot { it.type == PhaseType.GROGGINESS }),
        )

        assertThat(noYesterday.phases.map { it.shiftVsYesterday }).containsExactlyElementsIn(List(6) { null })
        assertThat(partial.get(PhaseType.GROGGINESS).shiftVsYesterday).isNull()
        assertThat(partial.get(PhaseType.WIND_DOWN).shiftVsYesterday).isEqualTo(Duration.ofMinutes(10))
    }

    private fun nap(start: Instant, end: Instant) = SleepBlock(
        kind = BlockKind.NAP,
        start = start,
        end = end,
        startOffset = Fx.UTC,
        endOffset = Fx.UTC,
        asleep = Duration.between(start, end),
    )

    /** A night whose endpoints carry the offsets [zone] had at those local times. */
    private fun nightInZone(wakeDate: LocalDate, fellAsleep: String, wokeUp: String, zone: ZoneId): Night {
        val startLocal = LocalDateTime.of(wakeDate.minusDays(1), LocalTime.parse(fellAsleep))
        val endLocal = LocalDateTime.of(wakeDate, LocalTime.parse(wokeUp))
        val startOffset = Fx.offsetAt(zone, startLocal)
        val endOffset = Fx.offsetAt(zone, endLocal)
        val base = Fx.night(wakeDate, fellAsleep, wokeUp, offset = endOffset)
        val start = startLocal.toInstant(startOffset)
        return base.copy(
            main = base.main!!.copy(start = start, startOffset = startOffset),
            fellAsleep = start,
            fellAsleepOffset = startOffset,
        )
    }
}
