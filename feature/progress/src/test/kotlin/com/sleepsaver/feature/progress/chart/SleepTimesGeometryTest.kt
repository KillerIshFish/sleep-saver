package com.sleepsaver.feature.progress.chart

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.SleepStage
import com.sleepsaver.core.domain.model.SleepStageType
import com.sleepsaver.feature.progress.ProgressFixtures.assemble
import com.sleepsaver.feature.progress.ProgressFixtures.at
import com.sleepsaver.feature.progress.ProgressFixtures.session
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class SleepTimesGeometryTest {

    private val zone = ZoneId.of("America/New_York")
    private val geometry = SleepTimesGeometry()
    private val axis = SleepTimesAxis()

    private val sat = LocalDate.of(2026, 10, 3)
    private val sun = sat.plusDays(1)

    private fun hours(h: Double) = (h / 14).toFloat()

    @Test
    fun `axis runs from 9 PM on the evening the night began to 11 AM`() {
        assertThat(axis.position(at(sat, 21), sat)).isEqualTo(0.0)
        assertThat(axis.position(at(sun, 0), sat)).isWithin(1e-9).of(3.0 / 14)
        assertThat(axis.position(at(sun, 11), sat)).isEqualTo(1.0)
        assertThat(axis.position(at(sat, 20), sat)).isLessThan(0.0)
        assertThat(axis.position(at(sun, 14), sat)).isGreaterThan(1.0)
    }

    @Test
    fun `ticks every two hours across midnight`() {
        assertThat(axis.ticks.map { it.time }).containsExactly(
            LocalTime.of(21, 0), LocalTime.of(23, 0), LocalTime.of(1, 0), LocalTime.of(3, 0),
            LocalTime.of(5, 0), LocalTime.of(7, 0), LocalTime.of(9, 0), LocalTime.of(11, 0),
        ).inOrder()
        assertThat(axis.ticks.first().fraction).isEqualTo(0f)
        assertThat(axis.ticks.last().fraction).isEqualTo(1f)
    }

    @Test
    fun `a plain night is one bar from fell asleep to woke up`() {
        val night = assemble(listOf(session("n", at(sat, 23), at(sun, 7), zone))).single()

        val column = geometry.column(sun, night, zone)

        assertThat(column.isMissing).isFalse()
        assertThat(column.nightOf).isEqualTo(sat)
        assertThat(column.segments).containsExactly(BarSegment(hours(2.0), hours(10.0), SegmentKind.MAIN))
        assertThat(column.total).isEqualTo(Duration.ofHours(8))
        assertThat(column.offAxis).isEmpty()
    }

    @Test
    fun `awake periods split the bar, short ones don't`() {
        val start = at(sat, 23).atZone(zone).toInstant()
        val stages = listOf(
            SleepStage(start.plusSeconds(3 * 3600), start.plusSeconds(3 * 3600 + 30 * 60), SleepStageType.AWAKE),
            SleepStage(start.plusSeconds(5 * 3600), start.plusSeconds(5 * 3600 + 3 * 60), SleepStageType.AWAKE),
        )
        val night = assemble(listOf(session("n", at(sat, 23), at(sun, 7), zone, stages))).single()

        val segments = geometry.column(sun, night, zone).segments

        assertThat(segments).hasSize(2)
        assertThat(segments[0].top).isEqualTo(hours(2.0))
        assertThat(segments[0].bottom).isWithin(1e-6f).of(hours(5.0))
        assertThat(segments[1].top).isWithin(1e-6f).of(hours(5.5))
        assertThat(segments[1].bottom).isEqualTo(hours(10.0))
    }

    @Test
    fun `fragments merged into one night show the gap between them`() {
        val nights = assemble(
            listOf(session("a", at(sat, 23), at(sun, 1, 30), zone), session("b", at(sun, 2), at(sun, 7), zone)),
        )

        val segments = geometry.column(sun, nights.single(), zone).segments

        assertThat(segments.map { it.top to it.bottom }).containsExactly(
            hours(2.0) to hours(4.5),
            hours(5.0) to hours(10.0),
        ).inOrder()
    }

    @Test
    fun `sleep running past the axis is clipped and fades`() {
        val nights = assemble(
            listOf(
                session("doze", at(sat, 20), at(sat, 21, 30), zone),
                session("main", at(sat, 23, 30), at(sun, 12), zone),
            ),
        )

        val segments = geometry.column(sun, nights.single(), zone).segments

        val extra = segments.single { it.kind == SegmentKind.EXTRA }
        assertThat(extra.top).isEqualTo(0f)
        assertThat(extra.fadesTop).isTrue()
        assertThat(extra.fadesBottom).isFalse()
        assertThat(extra.bottom).isWithin(1e-6f).of(hours(0.5))
        val main = segments.single { it.kind == SegmentKind.MAIN }
        assertThat(main.bottom).isEqualTo(1f)
        assertThat(main.fadesBottom).isTrue()
        assertThat(main.fadesTop).isFalse()
    }

    @Test
    fun `afternoon naps sit off the axis, morning naps are drawn`() {
        val nights = assemble(
            listOf(
                session("main", at(sat, 23), at(sun, 7), zone),
                session("morning", at(sun, 9, 30), at(sun, 10), zone),
                session("afternoon", at(sun, 14), at(sun, 14, 25), zone),
            ),
        )

        val column = geometry.column(sun, nights.single(), zone)

        val nap = column.segments.single { it.kind == SegmentKind.NAP }
        assertThat(nap.top).isWithin(1e-6f).of(hours(12.5))
        assertThat(nap.bottom).isWithin(1e-6f).of(hours(13.0))
        assertThat(column.offAxis).containsExactly(OffAxisBlock(SegmentKind.NAP, Duration.ofMinutes(25)))
        assertThat(column.offAxisTotal).isEqualTo(Duration.ofMinutes(25))
    }

    @Test
    fun `missing nights are placeholders, a day with only a nap still counts as missing`() {
        val napOnly = assemble(listOf(session("nap", at(sun, 14), at(sun, 14, 40), zone))).single()

        val missing = geometry.column(sun, null, zone)
        val napDay = geometry.column(sun, napOnly, zone)

        assertThat(missing.isMissing).isTrue()
        assertThat(missing.segments).isEmpty()
        assertThat(missing.total).isNull()
        assertThat(napDay.isMissing).isTrue()
        assertThat(napDay.total).isNull()
        assertThat(napDay.offAxisTotal).isEqualTo(Duration.ofMinutes(40))
    }

    @Test
    fun `fall-back night lines up with the wall clock and never folds back`() {
        // 2026-11-01 02:00 EDT → 01:00 EST. In bed 23:00 EDT, up 07:00 EST: 9 h elapsed, 8 h on the clock.
        val sat = LocalDate.of(2026, 10, 31)
        val sun = sat.plusDays(1)
        val start = at(sat, 23).atZone(zone).toInstant()
        // Awake from 01:30 EDT to 01:10 EST (40 min that the clock shows going backwards).
        val awakeStart = start.plusSeconds(150 * 60)
        val stages = listOf(SleepStage(awakeStart, awakeStart.plusSeconds(40 * 60), SleepStageType.AWAKE))
        val night = assemble(listOf(session("n", at(sat, 23), at(sun, 7), zone, stages))).single()

        val segments = geometry.column(sun, night, zone).segments

        assertThat(night.totalSleep).isEqualTo(Duration.ofMinutes(9 * 60 - 40))
        assertThat(segments.first().top).isEqualTo(hours(2.0))
        assertThat(segments.last().bottom).isEqualTo(hours(10.0))
        segments.zipWithNext().forEach { (a, b) -> assertThat(b.top).isAtLeast(a.bottom) }
        segments.forEach { assertThat(it.bottom).isAtLeast(it.top) }
    }

    @Test
    fun `spring-forward night ends at its wall-clock wake time`() {
        // 2026-03-08 02:00 EST → 03:00 EDT. 23:00 → 07:00 is 7 h elapsed.
        val sat = LocalDate.of(2026, 3, 7)
        val sun = sat.plusDays(1)
        val night = assemble(listOf(session("n", at(sat, 23), at(sun, 7), zone))).single()

        val segments = geometry.column(sun, night, zone).segments

        assertThat(night.totalSleep).isEqualTo(Duration.ofHours(7))
        assertThat(segments.single().top).isEqualTo(hours(2.0))
        assertThat(segments.single().bottom).isEqualTo(hours(10.0))
    }

    @Test
    fun `local time inside a block uses the recorded offsets`() {
        val travel = assemble(
            listOf(session("n", at(sat, 23), at(sun, 7), ZoneId.of("Europe/Paris"))),
        ).single()

        // Device zone is New York, but the night was recorded in Paris: the bar shows Paris clock times.
        val segments = geometry.column(sun, travel, zone).segments

        assertThat(segments.single().top).isEqualTo(hours(2.0))
        assertThat(travel.main!!.startOffset).isEqualTo(ZoneOffset.ofHours(2))
    }

    @Test
    fun `edited nights are flagged`() {
        val night = assemble(listOf(session("n", at(sat, 23), at(sun, 7), zone))).single()
        val edited = night.copy(
            override = com.sleepsaver.core.domain.model.NightOverride(
                wakeDate = sun,
                wokeUp = at(sun, 7).atZone(zone).toInstant(),
                offset = ZoneOffset.ofHours(-4),
                updatedAt = at(sun, 9).atZone(zone).toInstant(),
            ),
        )

        assertThat(geometry.column(sun, night, zone).isEdited).isFalse()
        assertThat(geometry.column(sun, edited, zone).isEdited).isTrue()
        assertThat(geometry.column(sun, edited, zone).topMost).isEqualTo(hours(2.0))
    }

    @Test
    fun `placeholders follow the window's typical night, or 11 PM to 7 AM without one`() {
        val mon = sun.plusDays(1)
        val tue = mon.plusDays(1)
        val nights = assemble(
            listOf(
                session("a", at(sat, 22), at(sun, 6), zone),
                session("b", at(sun, 23), at(mon, 7), zone),
                session("c", at(mon, 22), at(tue, 8), zone),
            ),
        ).associateBy { it.wakeDate }

        val chart = geometry.chart(listOf(sun, mon, tue, tue.plusDays(1)), nights, zone)
        val empty = geometry.chart(listOf(sun, mon), emptyMap(), zone)

        assertThat(chart.placeholderTop).isEqualTo(hours(1.0))
        assertThat(chart.placeholderBottom).isEqualTo(hours(10.0))
        assertThat(chart.columns.last().isMissing).isTrue()
        assertThat(empty.placeholderTop).isEqualTo(hours(2.0))
        assertThat(empty.placeholderBottom).isEqualTo(hours(10.0))
        assertThat(empty.hasOffAxis).isFalse()
    }
}
