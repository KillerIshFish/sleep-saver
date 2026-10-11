package com.sleepsaver.core.alarm.receiver

import android.app.AlarmManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.alarm.AlarmActions
import com.sleepsaver.core.alarm.mirror.ExtraTrigger
import com.sleepsaver.core.alarm.mirror.MirroredExtra
import com.sleepsaver.core.alarm.testing.AlarmRobolectricTest
import com.sleepsaver.core.data.hooks.AlarmIntents
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingReason
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
internal class AlarmBootReceiverTest : AlarmRobolectricTest() {

    private val tomorrow = today.plusDays(1)
    private val todayAlarm = planned(today, "06:30")
    private val tomorrowAlarm = planned(tomorrow, "06:30")
    private val id = todayAlarm.occurrence.id

    private fun boot(action: String) = AlarmBootReceiver().onReceive(context, Intent(action))

    /** What a reschedule left in the mirror before the phone went off (no AlarmManager state survives a reboot). */
    private fun mirrorBeforeReboot() {
        graph.seed { it.copy(armed = mirrored(todayAlarm), upcoming = listOf(mirrored(tomorrowAlarm))) }
    }

    private fun locked() {
        setUnlocked(false)
        graph.lockRepositories()
    }

    @Test
    fun `a locked boot starts no service and re-arms RING_PLAN from the date and local time`() {
        graph.seed {
            // The stored instant is stale (an hour off); the date + local time are what count.
            val stale = mirrored(todayAlarm).let { a -> a.copy(targetEpochMs = a.targetEpochMs - 3_600_000, windowStartEpochMs = a.windowStartEpochMs - 3_600_000) }
            it.copy(armed = stale, upcoming = listOf(mirrored(tomorrowAlarm)))
        }
        locked()

        boot(Intent.ACTION_LOCKED_BOOT_COMPLETED)

        assertThat(startedServices).isEmpty()
        assertThat(ringPlanAt).isEqualTo(at(today, "06:30"))
        assertThat(ringPlanId).isEqualTo(id)
        assertThat(windowAt).isEqualTo(at(today, "05:45"))
        assertThat(graph.repositoryResolutions).isEqualTo(0)
        assertThat(graph.fakeSchedule.alarmPlanCalls).isEmpty()
    }

    @Test
    fun `a reboot while snoozed re-arms RING_EXTRA at the snooze end`() {
        mirrorBeforeReboot()
        val armed = mirrored(todayAlarm)
        graph.seed {
            it.copy(
                extra = MirroredExtra(
                    trigger = ExtraTrigger.SNOOZE.name,
                    atEpochMs = at(today, "06:39").toEpochMilli(),
                    ringKind = RingKind.PLANNED.name,
                    occurrenceId = id,
                    snoozesUsed = 1,
                    reason = RingReason.SNOOZE_END.name,
                    alarm = armed,
                    expiresAtEpochMs = at(today, "06:49").toEpochMilli(),
                ),
            )
        }
        plan(todayAlarm, tomorrowAlarm)
        now = at(today, "06:35")

        boot(Intent.ACTION_BOOT_COMPLETED)

        assertThat(ringExtraAt).isEqualTo(at(today, "06:39"))
        assertThat(scheduled(AlarmActions.RC_RING_EXTRA).single().alarmClockInfo).isNotNull()
        assertThat(m.extra?.extraTrigger).isEqualTo(ExtraTrigger.SNOOZE)
        assertThat(ringPlanId).isEqualTo(tomorrowAlarm.occurrence.id) // the snoozed occurrence is in progress, not re-rung
        assertThat(graph.nightController.state.value.snoozedUntil).isEqualTo(at(today, "06:39"))
        assertThat(startedServices).isEmpty()
    }

    @Test
    fun `a ring missed while the phone was off catches up in 5 s on an alarm clock`() {
        mirrorBeforeReboot()
        locked()
        now = at(today, "06:40")

        boot(Intent.ACTION_LOCKED_BOOT_COMPLETED)

        val catchUp = scheduled(AlarmActions.RC_RING_EXTRA).single()
        assertThat(catchUp.alarmClockInfo?.triggerTime).isEqualTo(at(today, "06:40:05").toEpochMilli())
        assertThat(m.extra?.extraTrigger).isEqualTo(ExtraTrigger.CATCH_UP)
        assertThat(m.extra?.occurrenceId).isEqualTo(id)
        assertThat(ringPlanId).isEqualTo(tomorrowAlarm.occurrence.id)
        assertThat(startedServices).isEmpty()
    }

    @Test
    fun `a zone change moves RING_PLAN to the same local time`() {
        mirrorBeforeReboot()
        locked()
        val losAngeles = ZoneId.of("America/Los_Angeles")
        graph.time.zone = losAngeles

        boot(Intent.ACTION_TIMEZONE_CHANGED)

        val expected = ZonedDateTime.of(today, LocalTime.of(6, 30), losAngeles).toInstant()
        assertThat(ringPlanAt).isEqualTo(expected)
        assertThat(windowAt).isEqualTo(expected - Duration.ofMinutes(45))
        assertThat(m.armed?.target).isEqualTo(expected)
        assertThat(m.armed?.offsetSec).isEqualTo(-7 * 3600)
    }

    @Test
    fun `unlocked, the mirror re-arm is followed by a full reschedule from Room`() {
        mirrorBeforeReboot()
        val moved = planned(today, "07:00")
        plan(moved)

        boot(Intent.ACTION_MY_PACKAGE_REPLACED)

        assertThat(graph.fakeSchedule.alarmPlanCalls).isNotEmpty()
        assertThat(ringPlanAt).isEqualTo(at(today, "07:00"))
        assertThat(startedServices).isEmpty()
    }

    @Test
    fun `a locale change refreshes the channels, and the exact-alarm grant re-arms`() {
        notificationManager.deleteNotificationChannel(AlarmIntents.CHANNEL_RING)
        mirrorBeforeReboot()
        plan(todayAlarm, tomorrowAlarm)

        boot(Intent.ACTION_LOCALE_CHANGED)
        assertThat(notificationManager.getNotificationChannel(AlarmIntents.CHANNEL_RING)).isNotNull()

        val manager = context.getSystemService(AlarmManager::class.java)
        alarms.scheduledAlarms.toList().forEach { alarm -> alarm.operation?.let(manager::cancel) }
        boot(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        assertThat(ringPlanAt).isEqualTo(at(today, "06:30"))
    }

    @Test
    fun `an unknown broadcast does nothing`() {
        mirrorBeforeReboot()

        boot("com.example.SOMETHING")

        assertThat(alarms.scheduledAlarms).isEmpty()
    }
}
