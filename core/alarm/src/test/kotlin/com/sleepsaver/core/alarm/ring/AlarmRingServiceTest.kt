package com.sleepsaver.core.alarm.ring

import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.alarm.AlarmActions
import com.sleepsaver.core.alarm.AlarmChannels
import com.sleepsaver.core.alarm.mirror.ExtraTrigger
import com.sleepsaver.core.alarm.mirror.MirroredCatnap
import com.sleepsaver.core.alarm.mirror.MirroredNight
import com.sleepsaver.core.alarm.testing.AlarmRobolectricTest
import com.sleepsaver.core.data.hooks.AlarmIntents
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingOutcome
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.AlarmTone
import com.sleepsaver.core.domain.schedule.WakeStyle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(AndroidJUnit4::class)
internal class AlarmRingServiceTest : AlarmRobolectricTest() {

    private val tomorrow = today.plusDays(1)
    private val todayAlarm = planned(today, "06:30", mode = AlarmMode.FIXED)
    private val tomorrowAlarm = planned(tomorrow, "06:30", mode = AlarmMode.FIXED)
    private val id = todayAlarm.occurrence.id
    private val target = at(today, "06:30")

    /** Arms today's and tomorrow's alarms at 05:00, then moves the clock to the wake time. */
    private fun armAndReachWakeTime() {
        plan(todayAlarm, tomorrowAlarm)
        reschedule()
        now = target
    }

    private val sounds get() = graph.ringOutputs.sounds
    private val events get() = graph.nightLog.eventsState.value

    // ---------------------------------------------------------------- T6

    @Test
    fun `a planned ring starts foreground with a full-screen alarm notification on alarm_ring`() {
        armAndReachWakeTime()

        val ring = startRing(AlarmActions.KIND_PLAN, id)

        val service = shadowOf(ring.get())
        assertThat(service.lastForegroundNotificationId).isEqualTo(AlarmActions.ID_RINGING)
        assertThat(ring.get().foregroundServiceType).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        val notification = service.lastForegroundNotification
        assertThat(notification.channelId).isEqualTo(AlarmIntents.CHANNEL_RING)
        assertThat(notification.category).isEqualTo(Notification.CATEGORY_ALARM)
        assertThat(notification.fullScreenIntent).isNotNull()
        assertThat(shadowOf(notification.fullScreenIntent).savedIntent.component?.className).isEqualTo(AlarmIntents.RINGING_ACTIVITY)
        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("Good morning")
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo("Your wake time")
        assertThat(notification.actions.map { it.title.toString() }).containsExactly("Snooze until 6:39\u00A0AM", "Stop").inOrder()
        assertThat(notification.deleteIntent).isNotNull()

        val ringing = checkNotNull(graph.nightController.state.value.ringing)
        assertThat(ringing.kind).isEqualTo(RingKind.PLANNED)
        assertThat(ringing.reason).isEqualTo(RingReason.FIXED_TIME)
        assertThat(ringing.target).isEqualTo(target)
        assertThat(ringing.wakeDate).isEqualTo(today)
        assertThat(ringing.offersMorningFeel).isTrue()
        assertThat(sounds.single().tone).isEqualTo(AlarmTone.SOFT_CHIMES)
        assertThat(sounds.single().started).isTrue()
        assertThat(m.ringing?.occurrenceId).isEqualTo(id)
        assertThat(events.single().firstRingAt).isEqualTo(target)
        assertThat(ringPlanId).isEqualTo(tomorrowAlarm.occurrence.id) // the plan moved on
    }

    @Test
    fun `a surfacing ring at the wake time is logged by tonight's sensing outcome`() {
        plan(planned(today, "06:30"))
        reschedule()
        graph.seed { it.copy(armed = it.armed?.copy(sensingOutcome = "FLAT")) }
        now = target

        startRing(AlarmActions.KIND_PLAN, id)

        assertThat(graph.nightController.state.value.ringing?.reason).isEqualTo(RingReason.PLACEMENT_FALLBACK)
        assertThat(events.single().reason).isEqualTo(RingReason.PLACEMENT_FALLBACK)
    }

    @Test
    fun `the cue timeline swells, vibrates and escalates to the day's volume`() {
        armAndReachWakeTime()
        startRing(AlarmActions.KIND_PLAN, id)
        val sound = sounds.single()
        val vibration = graph.ringOutputs.vibrations.single()

        assertThat(sound.gains.first()).isWithin(1e-4f).of(0.8f * 0.05f)
        assertThat(vibration.on).isFalse()
        advance(Duration.ofSeconds(31))
        assertThat(vibration.on).isTrue()
        advance(Duration.ofMinutes(5))
        assertThat(sound.gains.last()).isEqualTo(0.8f)
        assertThat(sound.stopped).isFalse()
    }

    // ---------------------------------------------------------------- T8

    @Test
    fun `snooze stops the service, posts 302 and arms RING_EXTRA at until`() {
        armAndReachWakeTime()
        val ring = startRing(AlarmActions.KIND_PLAN, id)
        now = target + Duration.ofSeconds(20)

        ring.send(ringAction(AlarmActions.ACTION_SNOOZE))

        val service = shadowOf(ring.get())
        assertThat(service.isForegroundStopped).isTrue()
        assertThat(service.isStoppedBySelf).isTrue()
        assertThat(sounds.single().stopped).isTrue()
        val snoozed = notifications.getNotification(AlarmActions.ID_SNOOZED)
        assertThat(snoozed.channelId).isEqualTo(AlarmIntents.CHANNEL_QUIET)
        assertThat(snoozed.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("Snoozed until 6:39\u00A0AM · then the last ring")
        assertThat(snoozed.actions.single().title.toString()).isEqualTo("Stop")
        assertThat(ringExtraAt).isEqualTo(at(today, "06:39:20"))
        assertThat(m.extra?.extraTrigger).isEqualTo(ExtraTrigger.SNOOZE)
        assertThat(m.extra?.snoozesUsed).isEqualTo(1)
        assertThat(graph.nightController.state.value.ringing).isNull()
        assertThat(graph.nightController.state.value.snoozedUntil).isEqualTo(at(today, "06:39:20"))
        assertThat(events.single().snoozeTimes).containsExactly(now)
    }

    @Test
    fun `the snooze end is a fresh ring that cancels 302 and keeps counting snoozes`() {
        armAndReachWakeTime()
        startRing(AlarmActions.KIND_PLAN, id).send(ringAction(AlarmActions.ACTION_SNOOZE))
        now = at(today, "06:39")

        val again = startRing(AlarmActions.KIND_EXTRA)

        assertThat(shadowOf(again.get()).lastForegroundNotificationId).isEqualTo(AlarmActions.ID_RINGING)
        assertThat(notifications.getNotification(AlarmActions.ID_SNOOZED)).isNull()
        assertThat(m.ringing?.snoozesUsed).isEqualTo(1)
        assertThat(m.ringing?.ringReason).isEqualTo(RingReason.SNOOZE_END)
        assertThat(events.single().firstRingAt).isEqualTo(target) // one row per occurrence
    }

    // ---------------------------------------------------------------- T9

    @Test
    fun `stop records STOPPED, handles the occurrence, ends the night and reschedules`() {
        armAndReachWakeTime()
        graph.seed { it.copy(night = MirroredNight(4, at(today.minusDays(1), "23:00").toEpochMilli(), -14_400, false, null, Long.MAX_VALUE)) }
        val ring = startRing(AlarmActions.KIND_PLAN, id)
        val computes = graph.fakeSchedule.alarmPlanCalls.size
        now = target + Duration.ofMinutes(1)

        ring.send(ringAction(AlarmActions.ACTION_STOP))

        val event = events.single()
        assertThat(event.outcome).isEqualTo(RingOutcome.STOPPED)
        assertThat(event.stoppedAt).isEqualTo(now)
        assertThat(m.handledIds).contains(id)
        assertThat(m.ringing).isNull()
        assertThat(m.night).isNull()
        assertThat(graph.nightController.state.value.night).isNull()
        assertThat(graph.fakeSchedule.alarmPlanCalls.size).isGreaterThan(computes)
        assertThat(ringPlanId).isEqualTo(tomorrowAlarm.occurrence.id)
        assertThat(shadowOf(ring.get()).isStoppedBySelf).isTrue()
        assertThat(sounds.single().stopped).isTrue()
        assertThat(graph.nightLog.refreshTuningCalls).isEqualTo(0)
    }

    @Test
    fun `a test ring's stop doesn't end the night`() {
        armAndReachWakeTime()
        now = at(today, "05:10")
        val night = MirroredNight(4, at(today, "05:00").toEpochMilli(), -14_400, false, null, Long.MAX_VALUE)
        graph.seed { it.copy(night = night) }
        assertThat(graph.nightController.testRing()).isTrue()
        now = at(today, "05:11")

        val ring = startRing(AlarmActions.KIND_EXTRA)
        assertThat(graph.nightController.state.value.ringing?.kind).isEqualTo(RingKind.TEST)
        assertThat(graph.nightController.state.value.ringing?.snooze?.available).isFalse()
        ring.send(ringAction(AlarmActions.ACTION_STOP))

        assertThat(m.night).isEqualTo(night)
        assertThat(m.handledIds).isEmpty()
        assertThat(events.single().kind).isEqualTo(RingKind.TEST)
        assertThat(events.single().outcome).isEqualTo(RingOutcome.STOPPED)
        assertThat(ringPlanId).isEqualTo(id)
    }

    @Test
    fun `a test ring stops itself after its length`() {
        graph.nightController.testRing()
        now += Duration.ofSeconds(60)
        startRing(AlarmActions.KIND_EXTRA)

        advance(Duration.ofSeconds(61))

        assertThat(m.ringing).isNull()
        assertThat(m.extra).isNull() // no backup for a test
        assertThat(events.single().outcome).isEqualTo(RingOutcome.TIMED_OUT)
    }

    // ---------------------------------------------------------------- T10

    @Test
    fun `an unanswered ring times out into one backup ring, and the backup's timeout is MISSED`() {
        armAndReachWakeTime()
        val ring = startRing(AlarmActions.KIND_PLAN, id)

        advance(Duration.ofMinutes(10))

        assertThat(shadowOf(ring.get()).isStoppedBySelf).isTrue()
        assertThat(m.ringing).isNull()
        assertThat(m.handledIds).contains(id)
        val backup = checkNotNull(m.extra)
        assertThat(backup.extraTrigger).isEqualTo(ExtraTrigger.BACKUP)
        assertThat(backup.at).isEqualTo(now + Duration.ofMinutes(5))
        assertThat(ringExtraAt).isEqualTo(now + Duration.ofMinutes(5))
        assertThat(events.single().outcome).isEqualTo(RingOutcome.TIMED_OUT)

        now = backup.at
        startRing(AlarmActions.KIND_EXTRA)
        assertThat(m.ringing?.isBackup).isTrue()
        assertThat(graph.nightController.state.value.ringing?.reason).isEqualTo(RingReason.BACKUP)
        advance(Duration.ofMinutes(10))

        assertThat(m.ringing).isNull()
        assertThat(m.extra).isNull()
        assertThat(events.single().outcome).isEqualTo(RingOutcome.MISSED)
    }

    @Test
    fun `a catnap that times out gets no backup`() {
        graph.nightController.startCatnap(Duration.ofMinutes(20))
        now = at(today, "05:25")
        startRing(AlarmActions.KIND_CATNAP)

        advance(Duration.ofMinutes(10))

        assertThat(m.extra).isNull()
        assertThat(events.single().kind).isEqualTo(RingKind.NAP)
        assertThat(events.single().outcome).isEqualTo(RingOutcome.TIMED_OUT)
    }

    @Test
    fun `with notifications blocked a ring stops itself after 2 min`() {
        armAndReachWakeTime()
        notifications.setNotificationsEnabled(false)
        startRing(AlarmActions.KIND_PLAN, id)

        advance(Duration.ofMinutes(2))

        assertThat(m.ringing).isNull()
        assertThat(m.extra?.extraTrigger).isEqualTo(ExtraTrigger.BACKUP)
    }

    @Test
    fun `with the alarm channel off a ring stops after 2 min, and catnap and test ring are refused`() {
        armAndReachWakeTime()
        val channel = notificationManager.getNotificationChannel(AlarmIntents.CHANNEL_RING)
        channel.importance = NotificationManager.IMPORTANCE_NONE
        notificationManager.createNotificationChannel(channel)
        assertThat(AlarmChannels.ringUiBlocked(context)).isTrue()

        assertThat(graph.nightController.startCatnap(Duration.ofMinutes(20))).isFalse()
        assertThat(graph.nightController.testRing()).isFalse()
        startRing(AlarmActions.KIND_PLAN, id)
        assertThat(AlarmChannels.ringUiBlocked(context)).isTrue() // the service's ensure() doesn't raise it again
        advance(Duration.ofMinutes(2))

        assertThat(m.ringing).isNull()
        assertThat(m.catnap).isNull()
        assertThat(m.extra?.extraTrigger).isEqualTo(ExtraTrigger.BACKUP)
    }

    // ---------------------------------------------------------------- Duplicates and the queue

    @Test
    fun `RING_PLAN for the occurrence already ringing is ignored`() {
        armAndReachWakeTime()
        val ring = startRing(AlarmActions.KIND_PLAN, id)
        ring.send(ringAction(AlarmActions.ACTION_SNOOZE))
        now = at(today, "06:39")
        val again = startRing(AlarmActions.KIND_EXTRA)
        advance(Duration.ofSeconds(40))
        val since = m.ringing?.since
        val gains = sounds.last().gains.size

        again.send(ringIntent(AlarmActions.KIND_PLAN, id))

        assertThat(sounds).hasSize(2) // no new sound: the timeline wasn't restarted
        assertThat(m.ringing?.since).isEqualTo(since)
        assertThat(m.ringing?.snoozesUsed).isEqualTo(1)
        assertThat(events.single().snoozeTimes).hasSize(1)
        assertThat(sounds.last().stopped).isFalse()
        idleMain(Duration.ofSeconds(1))
        assertThat(sounds.last().gains.size).isGreaterThan(gains)
    }

    @Test
    fun `RING_PLAN while a snooze of the same occurrence is pending is ignored, and still reaches startForeground first`() {
        armAndReachWakeTime()
        startRing(AlarmActions.KIND_PLAN, id).send(ringAction(AlarmActions.ACTION_SNOOZE)).destroy()
        val snooze = m.extra

        val stale = startRing(AlarmActions.KIND_PLAN, id)

        val service = shadowOf(stale.get())
        assertThat(service.lastForegroundNotificationId).isEqualTo(AlarmActions.ID_RINGING)
        assertThat(service.isForegroundStopped).isTrue()
        assertThat(service.isStoppedBySelf).isTrue()
        assertThat(m.extra).isEqualTo(snooze)
        assertThat(m.ringing).isNull()
        assertThat(sounds).hasSize(1)
    }

    @Test
    fun `a planned ring during a catnap ring waits, and rings right after Stop`() {
        plan(planned(today, "05:26", mode = AlarmMode.FIXED))
        reschedule()
        graph.nightController.startCatnap(Duration.ofMinutes(20))
        now = at(today, "05:25")
        val ring = startRing(AlarmActions.KIND_CATNAP)
        assertThat(graph.nightController.state.value.ringing?.kind).isEqualTo(RingKind.NAP)
        assertThat(graph.nightController.state.value.ringing?.offersMorningFeel).isFalse()
        now = at(today, "05:26")

        ring.send(ringIntent(AlarmActions.KIND_PLAN, "2026-10-06T05:26"))
        assertThat(graph.nightController.state.value.ringing?.kind).isEqualTo(RingKind.NAP)
        ring.send(ringAction(AlarmActions.ACTION_STOP))

        val ringing = checkNotNull(graph.nightController.state.value.ringing)
        assertThat(ringing.kind).isEqualTo(RingKind.PLANNED)
        assertThat(m.ringing?.occurrenceId).isEqualTo("2026-10-06T05:26")
        assertThat(sounds).hasSize(2)
        assertThat(shadowOf(ring.get()).isStoppedBySelf).isFalse()
    }

    @Test
    fun `a catnap or test ring that comes while a planned alarm rings is dropped`() {
        armAndReachWakeTime()
        graph.seed { m ->
            m.copy(catnap = MirroredCatnap(0, target.toEpochMilli(), 20, "SOFT_CHIMES", 0.8f, Long.MAX_VALUE))
        }
        val ring = startRing(AlarmActions.KIND_PLAN, id)

        ring.send(ringIntent(AlarmActions.KIND_CATNAP))

        assertThat(m.ringing?.kind).isEqualTo(RingKind.PLANNED)
        assertThat(m.catnap).isNull()
        assertThat(sounds).hasSize(1)
    }

    @Test
    fun `a catnap ring uses the next alarm's tone and volume, Standard, one 5-min snooze, no Morning feel`() {
        plan(planned(today, "06:30", style = WakeStyle.FIRM))
        reschedule()
        graph.nightController.startCatnap(Duration.ofMinutes(20))
        now = at(today, "05:25")

        startRing(AlarmActions.KIND_CATNAP)

        val ringing = checkNotNull(graph.nightController.state.value.ringing)
        assertThat(ringing.style).isEqualTo(WakeStyle.STANDARD)
        assertThat(ringing.reason).isEqualTo(RingReason.CATNAP_END)
        assertThat(ringing.snooze.until).isEqualTo(at(today, "05:30"))
        assertThat(ringing.snooze.lastOne).isTrue()
        assertThat(ringing.offersMorningFeel).isFalse()
        assertThat(notifications.getNotification(AlarmActions.ID_CATNAP)).isNull()
    }

    @Test
    fun `swiping the ring away with no snooze left posts it again`() {
        graph.nightController.testRing()
        now += Duration.ofSeconds(60)
        val ring = startRing(AlarmActions.KIND_EXTRA)
        val notification = shadowOf(ring.get()).lastForegroundNotification
        assertThat(shadowOf(notification.deleteIntent).savedIntent.action).isEqualTo(AlarmActions.ACTION_REPOST)
        notificationManager.cancel(AlarmActions.ID_RINGING)

        ring.send(ringAction(AlarmActions.ACTION_REPOST))

        assertThat(notifications.getNotification(AlarmActions.ID_RINGING)).isNotNull()
    }
}
