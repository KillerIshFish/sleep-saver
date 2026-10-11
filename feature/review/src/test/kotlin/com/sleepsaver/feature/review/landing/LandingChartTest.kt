package com.sleepsaver.feature.review.landing

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.MovementTrace
import com.sleepsaver.core.domain.alarm.AlarmEvent
import com.sleepsaver.core.domain.alarm.LandingStage
import com.sleepsaver.core.domain.alarm.MovementEpoch
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.alarm.WakeLanding
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.WakeStyle
import com.sleepsaver.feature.review.TestNight
import com.sleepsaver.feature.review.TestNight.at
import com.sleepsaver.feature.review.TestNight.wakeDate
import com.sleepsaver.feature.review.detail.StageRow
import org.junit.Test
import java.time.Duration
import java.time.Instant

class LandingChartTest {

    /** A 6:30 surfacing alarm (window 6:00–6:30) that rang at 6:11, snoozed at 6:12 and was stopped at 6:20. */
    private fun landing(
        firstRing: Instant = at(wakeDate, 6, 11),
        snoozes: List<Instant> = listOf(at(wakeDate, 6, 12)),
        stoppedAt: Instant? = at(wakeDate, 6, 20),
        windowStart: Instant = at(wakeDate, 6),
    ) = WakeLanding(
        event = AlarmEvent(
            kind = RingKind.PLANNED,
            occurrenceId = "$wakeDate" + "T06:30",
            wakeDate = wakeDate,
            target = at(wakeDate, 6, 30),
            windowStart = windowStart,
            mode = AlarmMode.SURFACING,
            style = WakeStyle.STANDARD,
            firstRingAt = firstRing,
            reason = RingReason.RESTLESS,
            snoozeTimes = snoozes,
            stoppedAt = stoppedAt,
            offset = TestNight.offset,
        ),
        stageAtRing = LandingStage.DEEP,
        majorityBefore = LandingStage.DEEP,
        minutesEarly = 19,
        windowDeepShare = 1.0,
        windowNonDeepMinutes = 0,
        hasStages = true,
    )

    @Test
    fun `the span is 90 minutes centred on the ring and holds the whole window`() {
        val chart = LandingChart.of(landing(), TestNight.night())

        assertThat(chart.start).isEqualTo(at(wakeDate, 5, 26))
        assertThat(chart.end).isEqualTo(at(wakeDate, 6, 56))
        assertThat(chart.windowStart).isEqualTo(at(wakeDate, 6))
        assertThat(chart.windowEnd).isEqualTo(at(wakeDate, 6, 30))
        assertThat(chart.hasWindow).isTrue()
        assertThat(chart.ring).isEqualTo(at(wakeDate, 6, 11))
        assertThat(chart.snoozes).containsExactly(at(wakeDate, 6, 12))
        assertThat(chart.stop).isEqualTo(at(wakeDate, 6, 20))
    }

    @Test
    fun `stages are clipped to the span`() {
        val chart = LandingChart.of(landing(), TestNight.night())

        // Deep sleep runs 2:30 → 7:00: only 5:26 → 6:56 is drawn.
        val deep = chart.segments.single()
        assertThat(deep.row).isEqualTo(StageRow.DEEP)
        assertThat(deep.start).isEqualTo(chart.start)
        assertThat(deep.end).isEqualTo(chart.end)
    }

    @Test
    fun `stages end where the night ends`() {
        val chart = LandingChart.of(landing(firstRing = at(wakeDate, 6, 45), snoozes = emptyList(), stoppedAt = null), TestNight.night())

        assertThat(chart.segments.single().end).isEqualTo(at(wakeDate, 7))
        assertThat(chart.stop).isNull()
    }

    @Test
    fun `a fixed-time alarm has no window`() {
        val chart = LandingChart.of(landing(windowStart = at(wakeDate, 6, 30), firstRing = at(wakeDate, 6, 30)), TestNight.night())

        assertThat(chart.hasWindow).isFalse()
    }

    @Test
    fun `movement in the span is scaled to its busiest epoch, noise stays low`() {
        fun epoch(minute: Int, second: Int, activity: Double?) = MovementEpoch(at(wakeDate, 6, minute).plusSeconds(second.toLong()), activity)
        val window = MovementTrace(
            logId = 1,
            mode = "WINDOW",
            start = at(wakeDate, 5, 45),
            end = at(wakeDate, 6, 20),
            epochs = listOf(epoch(5, 0, 4.0), epoch(5, 30, null), epoch(10, 30, 80.0), epoch(11, 0, 40.0)),
        )
        val early = MovementTrace(2, "WHOLE_NIGHT", at(wakeDate, 1), at(wakeDate, 2), listOf(MovementEpoch(at(wakeDate, 1, 30), 300.0)))

        val chart = LandingChart.of(landing(), TestNight.night(), listOf(window, early))

        assertThat(chart.movement.map { it.level }).containsExactly(0.05f, 1f, 0.5f).inOrder()
        assertThat(chart.movement.first().end).isEqualTo(chart.movement.first().start + Duration.ofSeconds(30))

        val still = MovementTrace(3, "WINDOW", at(wakeDate, 5, 45), null, listOf(epoch(10, 0, 2.0)))
        assertThat(LandingChart.of(landing(), TestNight.night(), listOf(still)).movement.single().level).isEqualTo(0.2f)
    }

    @Test
    fun `without a night there are no stages`() {
        assertThat(LandingChart.of(landing(), night = null).segments).isEmpty()
    }
}
