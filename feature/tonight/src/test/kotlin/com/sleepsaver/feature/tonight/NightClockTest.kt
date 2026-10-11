package com.sleepsaver.feature.tonight

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.domain.alarm.AlarmEvent
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.feature.tonight.TonightSamples.at
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/** The Tonight phases and the no-clock night (bedtime 23:00, Mon–Fri alarm 6:45 with a 30-min window). */
class NightClockTest {

    private val s = TonightSamples
    private val tuesday = s.today
    private val wednesday = s.today.plusDays(1)

    private fun clock(
        now: Instant,
        anchoredAt: Instant? = null,
        endedAt: Instant? = null,
        oneOffs: OneOffs = OneOffs(),
        alarms: List<PlannedAlarm> = s.alarms(now, oneOffs = oneOffs),
        todayEvent: AlarmEvent? = null,
    ): NightClock {
        val schedule = s.insights(now, oneOffs = oneOffs).insights.schedule
        return nightClock(
            now = now,
            zone = s.zone,
            schedule = schedule,
            session = PlanSamples.nightStepsSession(nightOf = schedule.date),
            inputs = s.debtInputs(),
            alarms = alarms,
            todayEvent = todayEvent,
            anchoredAt = anchoredAt,
            endedAt = endedAt,
        )
    }

    @Test
    fun `the morning runs from the wake time until noon`() {
        val morning = clock(at(8, 0))
        assertThat(morning.phase).isEqualTo(TonightPhase.MORNING)
        assertThat(morning.nightOf).isEqualTo(tuesday)
        assertThat(morning.wakeDate).isEqualTo(wednesday)

        assertThat(clock(at(11, 59)).phase).isEqualTo(TonightPhase.MORNING)
        assertThat(clock(at(12, 0)).phase).isEqualTo(TonightPhase.DAY)
    }

    @Test
    fun `the Night steps window is the evening`() {
        assertThat(clock(at(21, 29)).phase).isEqualTo(TonightPhase.DAY)
        val evening = clock(at(21, 30))
        assertThat(evening.phase).isEqualTo(TonightPhase.EVENING)
        assertThat(evening.stepsFrom).isEqualTo(at(21, 30))
        assertThat(evening.bedtime).isEqualTo(at(23, 0))
        assertThat(clock(at(22, 59)).phase).isEqualTo(TonightPhase.EVENING)
    }

    @Test
    fun `from the bedtime it is night until the surfacing window opens`() {
        val bedtime = clock(at(23, 0))
        assertThat(bedtime.phase).isEqualTo(TonightPhase.NIGHT)
        assertThat(bedtime.end).isEqualTo(at(6, 15, date = wednesday))

        val smallHours = clock(at(2, 0, date = wednesday))
        assertThat(smallHours.phase).isEqualTo(TonightPhase.NIGHT)
        assertThat(smallHours.nightOf).isEqualTo(tuesday)
        assertThat(smallHours.wakeDate).isEqualTo(wednesday)

        assertThat(clock(at(6, 14, date = wednesday)).isNight).isTrue()
        val windowOpen = clock(at(6, 15, date = wednesday))
        assertThat(windowOpen.isNight).isFalse()
        assertThat(windowOpen.nightOf).isEqualTo(wednesday)
    }

    @Test
    fun `a dropped anchor makes it night before the bedtime`() {
        assertThat(clock(at(22, 10)).isNight).isFalse()
        assertThat(clock(at(22, 10), anchoredAt = at(22, 5)).phase).isEqualTo(TonightPhase.NIGHT)
    }

    @Test
    fun `last night's anchor ends with last night`() {
        val anchor = at(22, 55)
        assertThat(clock(at(2, 0, date = wednesday), anchoredAt = anchor).isNight).isTrue()
        // The window opened at 6:15; the anchor stays until the alarm is stopped but no longer means night.
        assertThat(clock(at(6, 20, date = wednesday), anchoredAt = anchor).isNight).isFalse()
    }

    @Test
    fun `without an alarm the night ends where the wake zone starts`() {
        val dayOff = PlanSamples.oneOffs(dayOff = wednesday)
        val night = clock(at(23, 30), oneOffs = dayOff)
        val schedule = s.insights(at(23, 30), oneOffs = dayOff).insights.schedule

        assertThat(night.isNight).isTrue()
        assertThat(night.end).isEqualTo(schedule.tomorrowWakeZone.start)
    }

    @Test
    fun `I'm up ends night mode until the next anchor`() {
        assertThat(clock(at(23, 40), endedAt = at(23, 30)).isNight).isFalse()
        assertThat(clock(at(23, 40), endedAt = at(23, 30)).phase).isEqualTo(TonightPhase.EVENING)
        assertThat(clock(at(23, 50), anchoredAt = at(23, 45), endedAt = at(23, 30)).isNight).isTrue()
    }

    @Test
    fun `I'm up ends only the night it was tapped in`() {
        val thursday = wednesday.plusDays(1)
        assertThat(clock(at(23, 30, date = wednesday)).isNight).isTrue()

        // Up at 5:30, before the window: the rest of that night shows the clock ...
        val beforeWindow = at(5, 30, date = wednesday)
        assertThat(clock(at(5, 40, date = wednesday), endedAt = beforeWindow).isNight).isFalse()
        // ... but the next bedtime (less than 18 h later) is night as usual.
        val next = clock(at(23, 30, date = wednesday), endedAt = beforeWindow)
        assertThat(next.wakeDate).isEqualTo(thursday)
        assertThat(next.isNight).isTrue()

        // The same for a tap while the window is open (the anchor lasts until the alarm is stopped).
        assertThat(clock(at(23, 30, date = wednesday), endedAt = at(6, 20, date = wednesday)).isNight).isTrue()
    }

    @Test
    fun `a stopped alarm ends the night and starts the morning`() {
        val now = at(6, 20, date = wednesday)
        val handled = s.alarms(now).filterNot { it.occurrence.date == wednesday }
        val stopped = PlanSamples.alarmEvent(RingReason.RESTLESS, wakeDate = wednesday)
            .copy(stoppedAt = at(6, 12, date = wednesday))

        val clock = clock(now, alarms = handled, todayEvent = stopped)

        assertThat(clock.isNight).isFalse()
        assertThat(clock.nightOf).isEqualTo(wednesday)
        assertThat(clock.phase).isEqualTo(TonightPhase.MORNING)
    }

    @Test
    fun `without nights the evening comes from the Night steps session`() {
        val now = at(22, 0)
        val session = PlanSamples.nightStepsSession(nightOf = tuesday, fromSettingsOnly = true)
        val clock = nightClock(now, s.zone, schedule = null, session = session, inputs = null, alarms = s.alarms(now),
            todayEvent = null, anchoredAt = null, endedAt = null)

        assertThat(clock.phase).isEqualTo(TonightPhase.EVENING)
        assertThat(clock.bedtime).isEqualTo(at(23, 0))
        assertThat(clock.end).isEqualTo(at(6, 15, date = wednesday))
    }

    @Test
    fun `rounding keeps whole steps`() {
        assertThat(LocalTime.of(7, 3).roundTo(Duration.ofMinutes(5))).isEqualTo(LocalTime.of(7, 5))
        assertThat(LocalTime.of(7, 2).roundTo(Duration.ofMinutes(5))).isEqualTo(LocalTime.of(7, 0))
        assertThat(LocalTime.of(23, 58).roundTo(Duration.ofMinutes(5))).isEqualTo(LocalTime.MIDNIGHT)
        assertThat(formatHoursSpaced(Duration.ofMinutes(100))).isEqualTo("1 h 40 m")
        assertThat(formatHoursSpaced(Duration.ofMinutes(120))).isEqualTo("2 h")
    }
}
