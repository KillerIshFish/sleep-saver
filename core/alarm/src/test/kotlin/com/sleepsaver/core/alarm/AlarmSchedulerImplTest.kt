package com.sleepsaver.core.alarm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.alarm.mirror.ExtraTrigger
import com.sleepsaver.core.alarm.mirror.MirroredExtra
import com.sleepsaver.core.alarm.testing.AlarmRobolectricTest
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.ClockFormat
import com.sleepsaver.core.domain.alarm.AlarmTuning
import com.sleepsaver.core.domain.alarm.DeepPrior
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingOutcome
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.alarm.SensingOutcome
import com.sleepsaver.core.domain.alarm.TuningReason
import com.sleepsaver.core.domain.journal.Anchor
import com.sleepsaver.core.domain.journal.AnchorSource
import com.sleepsaver.core.domain.schedule.AlarmMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.io.IOException
import java.time.Duration

@RunWith(AndroidJUnit4::class)
internal class AlarmSchedulerImplTest : AlarmRobolectricTest() {

    private val tomorrow = today.plusDays(1)
    private val todayAlarm = planned(today, "06:30")
    private val tomorrowAlarm = planned(tomorrow, "06:30")
    private val target = at(today, "06:30")

    @Test
    fun `RING_PLAN is an alarm clock at the wake time, opening Tonight`() {
        plan(todayAlarm, tomorrowAlarm)

        reschedule()

        val ring = scheduled(AlarmActions.RC_RING_PLAN).single()
        assertThat(ring.alarmClockInfo?.triggerTime).isEqualTo(target.toEpochMilli())
        assertThat(shadowOf(ring.alarmClockInfo?.showIntent).savedIntent.dataString).isEqualTo("sleepsaver://tonight")
        assertThat(ringPlanId).isEqualTo(todayAlarm.occurrence.id)
        assertThat(m.armed?.occurrenceId).isEqualTo(todayAlarm.occurrence.id)
        assertThat(m.upcoming.map { it.occurrenceId }).containsExactly(tomorrowAlarm.occurrence.id)
        assertThat(startedServices).isEmpty()
    }

    @Test
    fun `WINDOW is an exact allow-while-idle alarm 15 min before the window opens`() {
        plan(todayAlarm)

        reschedule()

        val window = scheduled(AlarmActions.RC_WINDOW).single()
        assertThat(window.triggerAtMs).isEqualTo(at(today, "05:45").toEpochMilli())
        assertThat(window.isAllowWhileIdle).isTrue()
        assertThat(window.windowLengthMs).isEqualTo(ShadowAlarmManager.WINDOW_EXACT)
        assertThat(shadowOf(window.operation).savedIntent.getStringExtra(AlarmActions.EXTRA_OCCURRENCE_ID)).isEqualTo(todayAlarm.occurrence.id)
    }

    @Test
    fun `inside the lead-in WINDOW fires in 5 s, and no service starts from the reschedule`() {
        plan(todayAlarm)
        now = at(today, "05:50")

        reschedule()

        assertThat(windowAt).isEqualTo(at(today, "05:50:05"))
        assertThat(startedServices).isEmpty()
    }

    @Test
    fun `inside the last 3 min there is no WINDOW and the ring will log sensing unavailable`() {
        plan(todayAlarm)
        now = at(today, "06:28")

        reschedule()

        assertThat(windowAt).isNull()
        assertThat(ringPlanAt).isEqualTo(target)
        assertThat(m.armed?.outcome).isEqualTo(SensingOutcome.UNAVAILABLE)
    }

    @Test
    fun `an UNAVAILABLE attempt (locked, refused) is tried again, a finished run is not`() {
        plan(todayAlarm)
        reschedule()
        now = at(today, "05:50")
        graph.seed { it.copy(armed = it.armed?.copy(sensingOutcome = SensingOutcome.UNAVAILABLE.name)) }

        reschedule()
        assertThat(windowAt).isEqualTo(at(today, "05:50:05"))

        now = at(today, "06:10")
        graph.seed { it.copy(armed = it.armed?.copy(sensingOutcome = SensingOutcome.NO_TRIGGER.name)) }
        reschedule()
        assertThat(windowAt).isNull()
        assertThat(m.armed?.outcome).isEqualTo(SensingOutcome.NO_TRIGGER)

        now = at(today, "06:28")
        reschedule()
        assertThat(m.armed?.outcome).isEqualTo(SensingOutcome.NO_TRIGGER) // never overwritten by "too late"
    }

    @Test
    fun `a FIXED alarm has no WINDOW`() {
        plan(planned(today, "06:30", mode = AlarmMode.FIXED))

        reschedule()

        assertThat(ringPlanAt).isEqualTo(target)
        assertThat(windowAt).isNull()
    }

    @Test
    fun `a failing plan leaves AlarmManager and the mirror as they are`() {
        plan(todayAlarm, tomorrowAlarm)
        reschedule()
        val before = m.armed

        graph.fakeSchedule.alarmPlanError = IOException("disk")
        reschedule()

        assertThat(ringPlanAt).isEqualTo(target)
        assertThat(windowAt).isEqualTo(at(today, "05:45"))
        assertThat(m.armed).isEqualTo(before)
    }

    @Test
    fun `a successful plan of no alarm cancels the plan channel`() {
        plan(todayAlarm)
        reschedule()

        planResult(next = null, plannedIds = emptySet())
        reschedule()

        assertThat(scheduled(AlarmActions.RC_RING_PLAN)).isEmpty()
        assertThat(scheduled(AlarmActions.RC_WINDOW)).isEmpty()
        assertThat(m.armed).isNull()
    }

    @Test
    fun `stopping a 06_12 early ring for a 06_30 target leaves no alarm at 06_30 today and arms tomorrow`() {
        plan(todayAlarm, tomorrowAlarm)
        reschedule()
        // T4: a restless moment at 06:12 arms SMART on RING_EXTRA.
        now = at(today, "06:12")
        val armed = checkNotNull(m.armed).copy(sensingOutcome = SensingOutcome.RESTLESS.name)
        graph.seed {
            it.copy(
                armed = armed,
                extra = MirroredExtra(
                    trigger = ExtraTrigger.SMART.name,
                    atEpochMs = (now + Duration.ofSeconds(5)).toEpochMilli(),
                    ringKind = RingKind.PLANNED.name,
                    occurrenceId = armed.occurrenceId,
                    snoozesUsed = 0,
                    reason = RingReason.RESTLESS.name,
                    alarm = armed,
                    expiresAtEpochMs = (now + Duration.ofMinutes(10)).toEpochMilli(),
                ),
            )
        }
        now = at(today, "06:12:05")

        val ring = startRing(AlarmActions.KIND_EXTRA)
        assertThat(ringPlanId).isEqualTo(tomorrowAlarm.occurrence.id) // T6: the plan moved on while it rings
        ring.send(ringAction(AlarmActions.ACTION_STOP))

        assertThat(alarms.scheduledAlarms.map { it.triggerAtMs }).doesNotContain(target.toEpochMilli())
        assertThat(ringPlanAt).isEqualTo(at(tomorrow, "06:30"))
        assertThat(ringPlanId).isEqualTo(tomorrowAlarm.occurrence.id)
        assertThat(scheduled(AlarmActions.RC_RING_EXTRA)).isEmpty()
        assertThat(m.handledIds).contains(todayAlarm.occurrence.id)
        assertThat(graph.fakeSchedule.alarmPlanCalls.last().second).contains(todayAlarm.occurrence.id)
        val event = graph.nightLog.eventsState.value.single()
        assertThat(event.outcome).isEqualTo(RingOutcome.STOPPED)
        assertThat(event.reason).isEqualTo(RingReason.RESTLESS)
        assertThat(event.earlyBy).isEqualTo(Duration.ofSeconds(17 * 60 + 55))
    }

    @Test
    fun `a catnap and a test ring don't remove RING_PLAN`() {
        plan(todayAlarm)
        reschedule()

        assertThat(graph.nightController.startCatnap(Duration.ofMinutes(20))).isTrue()
        assertThat(graph.nightController.testRing()).isTrue()

        assertThat(ringCatnapAt).isEqualTo(now + Duration.ofMinutes(25))
        assertThat(ringExtraAt).isEqualTo(now + Duration.ofSeconds(60))
        assertThat(ringPlanAt).isEqualTo(target)
        reschedule()
        assertThat(ringPlanAt).isEqualTo(target)
        assertThat(ringCatnapAt).isEqualTo(now + Duration.ofMinutes(25))
        assertThat(ringExtraAt).isEqualTo(now + Duration.ofSeconds(60))
    }

    @Test
    fun `a snooze doesn't remove RING_PLAN`() {
        plan(todayAlarm)
        reschedule()
        graph.nightController.startCatnap(Duration.ofMinutes(20))
        now = at(today, "05:25")
        val ring = startRing(AlarmActions.KIND_CATNAP)

        ring.send(ringAction(AlarmActions.ACTION_SNOOZE))

        assertThat(ringExtraAt).isEqualTo(at(today, "05:30"))
        assertThat(ringPlanAt).isEqualTo(target)
        reschedule()
        assertThat(ringPlanAt).isEqualTo(target)
        assertThat(ringExtraAt).isEqualTo(at(today, "05:30"))
    }

    @Test
    fun `without exact-alarm access the wake-time ring may come up to 10 min early, and there is no WINDOW`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        plan(todayAlarm)

        reschedule()
        graph.nightController.testRing()

        val ring = scheduled(AlarmActions.RC_RING_PLAN).single()
        assertThat(ring.triggerAtMs).isEqualTo((target - Duration.ofMinutes(10)).toEpochMilli())
        assertThat(ring.windowLengthMs).isEqualTo(Duration.ofMinutes(10).toMillis())
        assertThat(scheduled(AlarmActions.RC_WINDOW)).isEmpty()
        val test = scheduled(AlarmActions.RC_RING_EXTRA).single()
        assertThat(test.triggerAtMs).isEqualTo((now + Duration.ofSeconds(60)).toEpochMilli())
        assertThat(test.windowLengthMs).isEqualTo(Duration.ofMinutes(1).toMillis())
    }

    @Test
    fun `a snooze of an alarm that was switched off is cancelled and recorded CANCELLED`() {
        plan(todayAlarm, tomorrowAlarm)
        reschedule()
        now = target
        val ring = startRing(AlarmActions.KIND_PLAN, todayAlarm.occurrence.id)
        ring.send(ringAction(AlarmActions.ACTION_SNOOZE))
        assertThat(notifications.getNotification(AlarmActions.ID_SNOOZED)).isNotNull()

        plan(tomorrowAlarm) // the user turns today's alarm off
        reschedule()

        assertThat(m.extra).isNull()
        assertThat(scheduled(AlarmActions.RC_RING_EXTRA)).isEmpty()
        assertThat(notifications.getNotification(AlarmActions.ID_SNOOZED)).isNull()
        assertThat(graph.nightLog.eventsState.value.single().outcome).isEqualTo(RingOutcome.CANCELLED)
        assertThat(ringPlanId).isEqualTo(tomorrowAlarm.occurrence.id)
    }

    @Test
    fun `the armed alarm carries the tuned trigger, tonight's onset, the prior and the clock format`() {
        val anchorAt = at(today.minusDays(1), "23:10")
        graph.nightLog.tuningState.value = AlarmTuning(2.75, TuningReason.RAISED_AFTER_DEEP_RINGS, anchorAt, todayAlarm.occurrence.offset)
        graph.nightLog.activeAnchorState.value = Anchor(1, anchorAt, todayAlarm.occurrence.offset, today, AnchorSource.TONIGHT, wholeNightLog = false)
        val prior = DeepPrior(Duration.ofMinutes(10), listOf(0.2, 0.7), listOf(0.1), nightsUsed = 9)
        graph.patterns.deepPriorState.value = prior
        graph.settings.state.value = AppSettings(clockFormat = ClockFormat.H24)
        plan(todayAlarm)

        reschedule()

        val armed = checkNotNull(m.armed)
        assertThat(armed.zThreshold).isEqualTo(2.75)
        assertThat(armed.expectedOnsetEpochMs).isEqualTo((anchorAt + todayAlarm.latency).toEpochMilli())
        assertThat(m.prior?.toDomain()).isEqualTo(prior)
        assertThat(m.use24h).isTrue()
    }

    @Test
    fun `excluded ids passed to the plan are the handled and in-progress ones`() {
        graph.seed { it.handle("2026-10-05T06:30", kept = 8) }
        plan(todayAlarm)

        reschedule()

        assertThat(graph.fakeSchedule.alarmPlanCalls.last().second).containsExactly("2026-10-05T06:30")
    }

    @Test
    fun `cancelAll cancels the four alarms and clears the mirror`() {
        plan(todayAlarm)
        reschedule()
        graph.nightController.startCatnap(Duration.ofMinutes(10))
        graph.nightController.testRing()

        graph.scheduler.cancelAll()

        assertThat(alarms.scheduledAlarms).isEmpty()
        assertThat(m.armed).isNull()
        assertThat(m.catnap).isNull()
        assertThat(m.extra).isNull()
    }
}
