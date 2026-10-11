package com.sleepsaver.feature.winddown

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.SampleData
import org.junit.Test
import java.time.Instant
import java.time.LocalTime

/** [winddownFocus]: which evening the Night steps screens are about, and whether it is night. */
class WinddownNightTest {

    private val tuesday = PlanSamples.today
    private val wednesday = tuesday.plusDays(1)

    /** The energy schedule once it has rolled over to Wednesday (just after midnight with a 23:00 bedtime). */
    private val wednesdaySchedule = SampleData.schedule(date = wednesday)

    @Test
    fun `after midnight the schedule has moved on, but the night in progress is still the evening before`() {
        val focus = winddownFocus(
            now = PlanSamples.at(wednesday, LocalTime.of(0, 20)),
            zone = PlanSamples.zone,
            schedule = wednesdaySchedule,
            session = null,
            inputs = null,
            alarms = listOf(PlanSamples.plannedAlarm(date = wednesday)),
            morningStop = null,
            anchoredAt = null,
        )

        assertThat(focus.nightOf).isEqualTo(tuesday)
        assertThat(focus.isNight).isTrue()
    }

    @Test
    fun `without an alarm that night ends at the wake zone`() {
        val wakeZoneStart = wednesdaySchedule.wakeZone.start

        fun at(now: Instant) =
            winddownFocus(now, PlanSamples.zone, wednesdaySchedule, session = null, inputs = null, alarms = emptyList(), morningStop = null, anchoredAt = null)

        assertThat(at(wakeZoneStart.minusSeconds(60)).nightOf).isEqualTo(tuesday)
        assertThat(at(wakeZoneStart.minusSeconds(60)).isNight).isTrue()
        assertThat(at(wakeZoneStart).nightOf).isEqualTo(wednesday)
        assertThat(at(wakeZoneStart).isNight).isFalse()
    }

    @Test
    fun `a stopped morning alarm ends last night at once, and its anchor no longer counts`() {
        val anchoredAt = PlanSamples.at(tuesday, LocalTime.of(22, 55))
        val stopped = PlanSamples.at(wednesday, LocalTime.of(6, 5))

        val focus = winddownFocus(
            now = stopped.plusSeconds(60),
            zone = PlanSamples.zone,
            schedule = wednesdaySchedule,
            session = null,
            inputs = null,
            alarms = listOf(PlanSamples.plannedAlarm(date = wednesday)),
            morningStop = stopped,
            anchoredAt = anchoredAt,
        )

        assertThat(focus.nightOf).isEqualTo(wednesday)
        assertThat(focus.isNight).isFalse()
    }
}
