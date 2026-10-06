package com.sleepsaver.core.data.demo

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.source.ChangeBatch
import com.sleepsaver.core.data.sync.FakeTimeProvider
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SleepStage
import com.sleepsaver.core.domain.model.SleepStageType
import com.sleepsaver.core.domain.model.VitalType
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class FakeSleepDataSourceTest {
    private val zone = ZoneId.of("America/New_York")

    /** Tuesday. */
    private val today = LocalDate.of(2026, 10, 6)
    private val time = FakeTimeProvider(today.atTime(12, 0).atZone(zone).toInstant(), zone)
    private val data = FakeSleepDataSource(time).generate()

    private val naps = data.sessions.filter { it.isNapByRule() }
    private val nights: Map<LocalDate, List<RawSleepSession>> =
        (data.sessions - naps.toSet()).groupBy { it.localEnd.toLocalDate() }

    private fun RawSleepSession.isNapByRule(): Boolean {
        val config = SleepConfig.DEFAULT
        val startTime = localStart.toLocalTime()
        return duration < config.napMaxDuration && !startTime.isBefore(config.napWindowStart) && startTime.isBefore(config.napWindowEnd)
    }

    private fun List<RawSleepSession>.inBed(): Duration = Duration.between(first().start, last().end)

    @Test
    fun isDeterministic() {
        assertThat(FakeSleepDataSource(time).generate()).isEqualTo(data)
        assertThat(FakeSleepDataSource(time, seed = 7).generate()).isNotEqualTo(data)
    }

    @Test
    fun sixtyNightsEndingThisMorning() {
        assertThat(nights.keys).containsExactlyElementsIn((0L until 60L).map { today.minusDays(it) })
        assertThat(data.sessions.all { !it.end.isAfter(time.now) }).isTrue()
        assertThat(nights.values.flatten().all { it.localEnd.toLocalTime().isBefore(LocalTime.of(14, 0)) }).isTrue()
        assertThat(data.sessions.map { it.origin }.toSet()).containsExactly(SleepConfig.DEMO_ORIGIN)
        assertThat(data.sessions.map { it.id }.toSet()).hasSize(data.sessions.size)
        assertThat(data.sessions.all { it.startOffset == zone.rules.getOffset(it.start) }).isTrue()
    }

    @Test
    fun beforeThisMorningsWake_endsWithYesterdaysNight() {
        val early = FakeTimeProvider(today.atTime(4, 0).atZone(zone).toInstant(), zone)
        val earlyData = FakeSleepDataSource(early).generate()

        val wakeDates = earlyData.sessions.filterNot { it.isNapByRule() }.map { it.localEnd.toLocalDate() }.toSet()
        assertThat(wakeDates).containsExactlyElementsIn((1L..60L).map { today.minusDays(it) })
        assertThat(earlyData.sessions.all { !it.end.isAfter(early.now) }).isTrue()
        // Same dates, same sleep: each day is generated on its own.
        val overlapping = data.sessions.filter { it.localEnd.toLocalDate() in wakeDates && it.end.isBefore(early.now) }
        assertThat(earlyData.sessions).containsAtLeastElementsIn(overlapping)
    }

    @Test
    fun weekendsAreLater() {
        fun minuteOfNight(session: RawSleepSession, wakeDate: LocalDate) =
            ChronoUnit.MINUTES.between(wakeDate.atStartOfDay(), session.localStart).toDouble()
        val (weekend, weekday) = nights.entries.partition { it.key.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }
        val weekendBed = weekend.map { minuteOfNight(it.value.first(), it.key) }.average()
        val weekdayBed = weekday.map { minuteOfNight(it.value.first(), it.key) }.average()
        val weekendWake = weekend.map { it.value.last().localEnd.toLocalTime().toSecondOfDay() / 60.0 }.average()
        val weekdayWake = weekday.map { it.value.last().localEnd.toLocalTime().toSecondOfDay() / 60.0 }.average()

        assertThat(weekendBed - weekdayBed).isAtLeast(45.0)
        assertThat(weekendWake - weekdayWake).isAtLeast(90.0)
        assertThat(weekday.map { it.value.last().localEnd.toLocalTime() }.all { it in LocalTime.of(6, 30)..LocalTime.of(7, 0) }).isTrue()
    }

    @Test
    fun shortNightsAboutEveryNineDays() {
        val short = nights.values.filter { it.inBed() in Duration.ofHours(5)..Duration.ofHours(6) }
        assertThat(short.size).isIn(5..8)
        assertThat(nights.values.filterNot { it in short }.all { it.inBed() > Duration.ofMinutes(6 * 60 + 30) }).isTrue()
    }

    @Test
    fun fragmentedNightsAboutWeekly() {
        val fragmented = nights.values.filter { it.size == 2 }
        assertThat(fragmented.size).isIn(4..10)
        assertThat(nights.values.all { it.size <= 2 }).isTrue()
        fragmented.forEach { (first, second) ->
            assertThat(Duration.between(first.end, second.start).toMinutes()).isIn(20L..50L)
        }
    }

    @Test
    fun napsOnSomeAfternoons() {
        assertThat(naps.size).isIn(4..18)
        assertThat(naps.all { it.localStart.toLocalTime() in LocalTime.of(13, 0)..LocalTime.of(15, 30) }).isTrue()
        assertThat(naps.all { it.duration in Duration.ofMinutes(20)..Duration.ofMinutes(80) }).isTrue()
        assertThat(naps.any { it.duration >= Duration.ofMinutes(60) }).isTrue()
        assertThat(naps.count { it.duration <= Duration.ofMinutes(45) }).isAtLeast(2)
    }

    @Test
    fun stagesStayInsideTheirSessionsWithoutOverlapping() {
        for (session in data.sessions) {
            val stages = session.stages
            assertThat(stages).isNotEmpty()
            assertThat(stages).isInStrictOrder(compareBy<SleepStage> { it.start })
            assertThat(stages.all { it.start.isBefore(it.end) }).isTrue()
            assertThat(stages.first().start).isAtLeast(session.start)
            assertThat(stages.last().end).isAtMost(session.end)
            assertThat(stages.zipWithNext().all { (a, b) -> !a.end.isAfter(b.start) }).isTrue()
        }
        val awakePerNight = nights.values.map { sessions ->
            sessions.flatMap { it.stages }.filter { it.type == SleepStageType.AWAKE }
        }
        assertThat(awakePerNight.all { it.size <= 4 }).isTrue()
        assertThat(awakePerNight.flatten().all { it.duration <= Duration.ofMinutes(25) }).isTrue()
        assertThat(awakePerNight.count { it.isEmpty() }).isAtLeast(5)
        assertThat(awakePerNight.count { it.size >= 2 }).isAtLeast(5)
    }

    @Test
    fun deepSleepComesEarlyAndRemLate() {
        var deepEarly = Duration.ZERO
        var deepLate = Duration.ZERO
        var remEarly = Duration.ZERO
        var remLate = Duration.ZERO
        for (sessions in nights.values) {
            val middle = sessions.first().start.plus(sessions.inBed().dividedBy(2))
            for (stage in sessions.flatMap { it.stages }) {
                val early = stage.start.isBefore(middle)
                when (stage.type) {
                    SleepStageType.DEEP -> if (early) deepEarly += stage.duration else deepLate += stage.duration
                    SleepStageType.REM -> if (early) remEarly += stage.duration else remLate += stage.duration
                    else -> Unit
                }
            }
        }
        assertThat(deepEarly).isGreaterThan(deepLate.multipliedBy(2))
        assertThat(remLate).isGreaterThan(remEarly)
        val total = nights.values.flatten().flatMap { it.stages }.groupBy { it.type }
            .mapValues { (_, stages) -> stages.fold(Duration.ZERO) { sum, s -> sum + s.duration } }
        assertThat(total.getValue(SleepStageType.LIGHT)).isGreaterThan(total.getValue(SleepStageType.DEEP))
        assertThat(total.getValue(SleepStageType.LIGHT)).isGreaterThan(total.getValue(SleepStageType.REM))
    }

    @Test
    fun vitalsAreRealistic() {
        val byType = data.vitals.groupBy { it.type }
        assertThat(byType.getValue(VitalType.RESTING_HR)).hasSize(60)
        assertThat(byType.getValue(VitalType.RESTING_HR).all { it.value in 52.0..60.0 }).isTrue()
        assertThat(byType.getValue(VitalType.HRV_RMSSD).all { it.value in 35.0..70.0 }).isTrue()
        val steps = byType.getValue(VitalType.STEPS_DAY)
        assertThat(steps).hasSize(59) // finished days only
        assertThat(steps.all { it.value in 4_000.0..14_000.0 }).isTrue()
        assertThat(steps.map { it.id }).doesNotContain("demo:steps:$today")
        val sleepingHr = byType.getValue(VitalType.SLEEP_HR_AVG)
        assertThat(sleepingHr.map { it.id }).containsExactlyElementsIn(data.sessions.map { "sleephr:${it.id}" })
        assertThat(data.vitals.map { it.id }.toSet()).hasSize(data.vitals.size)
    }

    @Test
    fun dataSourceReadsFilterTheDataset() = runTest {
        val source = FakeSleepDataSource(time)
        val from = today.minusDays(7).atStartOfDay(zone).toInstant()
        val to = time.now

        val sessions = source.readSleepSessions(from, to)
        assertThat(sessions).isNotEmpty()
        assertThat(sessions.all { it.end.isAfter(from) && it.start.isBefore(to) }).isTrue()
        assertThat(sessions).containsExactlyElementsIn(data.sessions.filter { it.end.isAfter(from) && it.start.isBefore(to) })

        val recorded = source.readVitals(from, to, sessions.take(2))
        val vitals = recorded.map { it.sample }
        assertThat(vitals.filter { it.type == VitalType.SLEEP_HR_AVG }.map { it.id })
            .containsExactlyElementsIn(sessions.take(2).map { "sleephr:${it.id}" })
        // One per wake date from a week ago through this morning.
        assertThat(vitals.filter { it.type == VitalType.RESTING_HR }).hasSize(8)
        // Generated in the device zone: each vital carries that zone's offset at its time.
        assertThat(recorded.all { it.offset == zone.rules.getOffset(it.sample.time) }).isTrue()

        assertThat(source.isReadable()).isTrue()
        assertThat(source.newChangesToken()).isEqualTo("demo")
        val changes = source.changesSince("demo") as ChangeBatch.Changes
        assertThat(changes.upserted).isEmpty()
        assertThat(changes.deletedIds).isEmpty()
        assertThat(changes.nextToken).isEqualTo("demo")
    }
}
