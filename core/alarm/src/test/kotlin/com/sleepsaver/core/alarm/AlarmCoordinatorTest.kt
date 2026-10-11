package com.sleepsaver.core.alarm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.alarm.mirror.AlarmAction
import com.sleepsaver.core.alarm.mirror.MirrorState
import com.sleepsaver.core.alarm.mirror.MirrorUpdate
import com.sleepsaver.core.alarm.mirror.MirroredHush
import com.sleepsaver.core.alarm.mirror.MirroredNight
import com.sleepsaver.core.alarm.mirror.PendingAlarm
import com.sleepsaver.core.alarm.testing.AlarmRobolectricTest
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

@RunWith(AndroidJUnit4::class)
internal class AlarmCoordinatorTest : AlarmRobolectricTest() {

    private val todayAlarm = planned(today, "06:30")
    private val tomorrowAlarm = planned(today.plusDays(1), "06:30")
    private val hush = MirroredHush(color = "PINK", volume = 0.25f, timerMin = 45, endsAtEpochMs = 1L, playId = null)
    private val night = MirroredNight(anchorId = 3, anchoredAtEpochMs = 0, offsetSec = -14_400, wholeNightLog = false, logId = null, endsAtEpochMs = Long.MAX_VALUE)

    @Test
    fun `apply commits the change with the next generation, performs the alarm calls and tells the listeners`() {
        val seen = mutableListOf<MirrorState>()
        graph.coordinator.addListener { seen += it }
        val before = m.generation

        val value = graph.coordinator.apply { m ->
            MirrorUpdate(m.copy(hush = hush), "done", listOf(AlarmAction.ArmCatnap(now + Duration.ofMinutes(20))))
        }

        assertThat(value).isEqualTo("done")
        assertThat(m.hush).isEqualTo(hush)
        assertThat(m.generation).isEqualTo(before + 1)
        assertThat(ringCatnapAt).isEqualTo(now + Duration.ofMinutes(20))
        assertThat(seen.last()).isEqualTo(m)
    }

    @Test
    fun `a Hush write and an anchor write during a reschedule's compute don't drop the plan`() {
        plan(todayAlarm)
        graph.onComputeAlarmPlan = { call ->
            if (call == 1) {
                graph.seed { it.copy(hush = hush) }
                graph.seed { it.copy(night = night) }
            }
        }

        reschedule()

        assertThat(graph.fakeSchedule.alarmPlanCalls).hasSize(1)
        assertThat(m.armed?.occurrenceId).isEqualTo(todayAlarm.occurrence.id)
        assertThat(ringPlanAt).isEqualTo(todayAlarm.occurrence.target)
        assertThat(m.hush).isEqualTo(hush)
        assertThat(m.night).isEqualTo(night)
        assertThat(graph.coordinator.rescheduleAgain.get()).isFalse()
    }

    @Test
    fun `a stop during the compute changes the excluded ids, so the plan is computed again`() {
        plan(todayAlarm, tomorrowAlarm)
        graph.onComputeAlarmPlan = { call ->
            if (call == 1) graph.seed { it.handle(todayAlarm.occurrence.id, kept = 8) }
        }

        reschedule()

        assertThat(graph.fakeSchedule.alarmPlanCalls).hasSize(2)
        assertThat(graph.fakeSchedule.alarmPlanCalls[1].second).contains(todayAlarm.occurrence.id)
        assertThat(m.armed?.occurrenceId).isEqualTo(tomorrowAlarm.occurrence.id)
        assertThat(ringPlanId).isEqualTo(tomorrowAlarm.occurrence.id)
        assertThat(graph.coordinator.rescheduleAgain.get()).isFalse()
    }

    @Test
    fun `inputs changing twice ask for one more reschedule, which runs once the plan mutex is free`() {
        plan(todayAlarm)
        var flagWhileThirdComputes: Boolean? = null
        var mutexWhileThirdComputes: Boolean? = null
        graph.onComputeAlarmPlan = { call ->
            if (call <= 2) graph.seed { it.handle("2026-10-0${call}T07:00", kept = 8) }
            if (call == 3) {
                flagWhileThirdComputes = graph.coordinator.rescheduleAgain.get()
                mutexWhileThirdComputes = graph.coordinator.planMutex.isLocked
            }
        }

        reschedule()

        assertThat(graph.fakeSchedule.alarmPlanCalls).hasSize(3)
        // The flag was consumed when the first reschedule released the mutex; the third compute is a fresh reschedule.
        assertThat(flagWhileThirdComputes).isFalse()
        assertThat(mutexWhileThirdComputes).isTrue()
        assertThat(m.armed?.occurrenceId).isEqualTo(todayAlarm.occurrence.id)
        assertThat(graph.fakeSchedule.alarmPlanCalls[2].second).containsAtLeast("2026-10-01T07:00", "2026-10-02T07:00")
        assertThat(graph.coordinator.rescheduleAgain.get()).isFalse()
        assertThat(graph.coordinator.planMutex.isLocked).isFalse()
    }

    @Test
    fun `cancelAllAlarms cancels the four PendingIntents and clear empties the mirror`() {
        graph.coordinator.apply { m ->
            MirrorUpdate(
                m.copy(hush = hush),
                Unit,
                listOf(
                    AlarmAction.ArmPlan(now + Duration.ofHours(1), "x"),
                    AlarmAction.ArmWindow(now + Duration.ofMinutes(30), "x"),
                    AlarmAction.ArmExtra(now + Duration.ofMinutes(1)),
                    AlarmAction.ArmCatnap(now + Duration.ofMinutes(25)),
                ),
            )
        }
        assertThat(alarms.scheduledAlarms).hasSize(4)

        graph.coordinator.cancelAllAlarms()
        graph.coordinator.clear()

        assertThat(alarms.scheduledAlarms).isEmpty()
        assertThat(m).isEqualTo(MirrorState())
    }

    @Test
    fun `each PendingAlarm cancels only its own alarm`() {
        graph.coordinator.apply { m ->
            MirrorUpdate(m, Unit, listOf(AlarmAction.ArmPlan(now + Duration.ofHours(1), "x"), AlarmAction.ArmExtra(now + Duration.ofMinutes(1))))
        }

        graph.coordinator.apply { m -> MirrorUpdate(m, Unit, listOf(AlarmAction.Cancel(PendingAlarm.RING_EXTRA))) }

        assertThat(ringPlanAt).isEqualTo(now + Duration.ofHours(1))
        assertThat(ringExtraAt).isNull()
    }
}
