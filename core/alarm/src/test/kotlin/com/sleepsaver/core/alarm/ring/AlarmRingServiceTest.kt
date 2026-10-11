package com.sleepsaver.core.alarm.ring

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.alarm.AlarmActions
import com.sleepsaver.core.alarm.AlarmChannels
import com.sleepsaver.core.alarm.mirror.ExtraTrigger
import com.sleepsaver.core.alarm.mirror.MirroredCatnap
import com.sleepsaver.core.alarm.mirror.MirroredExtra
import com.sleepsaver.core.alarm.mirror.MirroredNight
import com.sleepsaver.core.alarm.receiver.AlarmReceiver
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
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Duration
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

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

    @Test
    fun `the snooze label is posted again when its minute changes, not on every tick`() {
        armAndReachWakeTime()
        startRing(AlarmActions.KIND_PLAN, id)
        // 06:30:02: "Snooze until 6:39 · last one" (the second snooze would fall under the minimum before 6:40).
        repeat(8) { advance(Duration.ofMillis(250)) }
        val shown = checkNotNull(graph.nightController.state.value.ringing)
        assertThat(shown.snooze.until?.truncatedTo(ChronoUnit.MINUTES)).isEqualTo(at(today, "06:39"))
        assertThat(shown.snooze.lastOne).isTrue()

        repeat(8) { advance(Duration.ofMillis(250)) } // 06:30:04: the offer moved to 06:39:04, its label didn't
        assertThat(graph.nightController.state.value.ringing).isSameInstanceAs(shown)

        repeat(240) { advance(Duration.ofMillis(250)) } // 06:31:04: capped at the wake time + overrun
        assertThat(graph.nightController.state.value.ringing?.snooze?.until).isEqualTo(at(today, "06:40"))
        assertThat(notifications.getNotification(AlarmActions.ID_RINGING).actions.first().title.toString())
            .isEqualTo("Snooze until 6:40 AM")
    }

    @Test
    fun `a catnap with no alarm armed is recorded in the phone's offset, not UTC`() {
        assertThat(graph.nightController.startCatnap(Duration.ofMinutes(20))).isTrue()
        now = checkNotNull(m.catnap).ringAt

        startRing(AlarmActions.KIND_CATNAP)

        val event = events.single()
        assertThat(event.kind).isEqualTo(RingKind.NAP)
        assertThat(event.offset).isEqualTo(ZoneOffset.ofHours(-4)) // New York in October
        assertThat(event.wakeDate).isEqualTo(today)
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
    fun `without exact alarms a snooze end the platform delivers late still rings, matched by its armed time`() {
        armAndReachWakeTime()
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        startRing(AlarmActions.KIND_PLAN, id).send(ringAction(AlarmActions.ACTION_SNOOZE))
        val snooze = checkNotNull(m.extra)
        val armed = scheduled(AlarmActions.RC_RING_EXTRA).single()
        assertThat(armed.alarmClockInfo).isNull() // T2: setWindow(at, 1 min), which Android stretches to 10 min
        now = snooze.at + Duration.ofMinutes(8)

        // A broadcast that isn't this extra's alarm (no armed time, or another one) is still too late to ring.
        startRing(AlarmActions.KIND_EXTRA)
        val other = ringIntent(AlarmActions.KIND_EXTRA).putExtra(AlarmActions.EXTRA_EPOCH_MS, snooze.atEpochMs - 60_000)
        Robolectric.buildService(AlarmRingService::class.java, other).create().startCommand(0, 1)
        assertThat(m.ringing).isNull()
        assertThat(m.extra?.atEpochMs).isEqualTo(snooze.atEpochMs)

        AlarmReceiver().onReceive(context, shadowOf(armed.operation).savedIntent)
        val forwarded = startedServices.last()
        assertThat(forwarded.getLongExtra(AlarmActions.EXTRA_EPOCH_MS, 0)).isEqualTo(snooze.atEpochMs)
        Robolectric.buildService(AlarmRingService::class.java, forwarded).create().startCommand(0, 1)

        assertThat(m.ringing?.trigger).isEqualTo(ExtraTrigger.SNOOZE.name)
        assertThat(m.ringing?.ringReason).isEqualTo(RingReason.SNOOZE_END)
        assertThat(m.ringing?.snoozesUsed).isEqualTo(1)
        assertThat(m.extra).isNull()
    }

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

    @Test
    fun `a Quiet bed test ring sounds at once and reaches the day's volume`() {
        plan(planned(today, "06:30", mode = AlarmMode.FIXED, style = WakeStyle.QUIET_BED), tomorrowAlarm)
        reschedule()
        assertThat(graph.nightController.testRing()).isTrue()
        now += Duration.ofSeconds(60)
        startRing(AlarmActions.KIND_EXTRA)

        assertThat(graph.nightController.state.value.ringing?.kind).isEqualTo(RingKind.TEST)
        assertThat(sounds.single().started).isTrue()
        assertThat(graph.ringOutputs.vibrations.single().on).isTrue()
        advance(Duration.ofSeconds(40))
        assertThat(sounds.single().gains.last()).isWithin(1e-4f).of(0.8f)
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

    // ---------------------------------------------------------------- T14

    @Test
    fun `a sounding ring is sticky and guarded until its timeout, and its end cancels the guard`() {
        armAndReachWakeTime()
        val ring = startRing(AlarmActions.KIND_PLAN, id)

        val guard = scheduled(AlarmActions.RC_RING_GUARD).single()
        assertThat(guard.triggerAtMs).isEqualTo((target + Duration.ofMinutes(10)).toEpochMilli())
        assertThat(guard.isAllowWhileIdle).isTrue()
        assertThat(guard.alarmClockInfo).isNull() // not shown as the next alarm
        assertThat(ring.get().onStartCommand(ringAction(AlarmActions.ACTION_REPOST), 0, 7)).isEqualTo(Service.START_STICKY)

        now = target + Duration.ofMinutes(1)
        ring.send(ringAction(AlarmActions.ACTION_STOP))

        assertThat(scheduled(AlarmActions.RC_RING_GUARD)).isEmpty()
        assertThat(ring.get().onStartCommand(ringAction(AlarmActions.ACTION_REPOST), 0, 8)).isEqualTo(Service.START_NOT_STICKY)
    }

    @Test
    fun `a ring whose process died times out at its guard, and its backup still comes`() {
        armAndReachWakeTime()
        startRing(AlarmActions.KIND_PLAN, id)
        val guard = shadowOf(scheduled(AlarmActions.RC_RING_GUARD).single().operation).savedIntent
        // The process dies a minute in: no onDestroy, no mirror change, and nothing is due until tomorrow's RING_PLAN.
        AlarmRingService.running = false
        AlarmRingService.current = null
        assertThat(ringExtraAt).isNull()

        now = target + Duration.ofMinutes(10)
        AlarmReceiver().onReceive(context, guard)

        assertThat(m.ringing).isNull()
        assertThat(m.handledIds).contains(id)
        assertThat(m.extra?.extraTrigger).isEqualTo(ExtraTrigger.BACKUP)
        assertThat(ringExtraAt).isEqualTo(target + Duration.ofMinutes(15))
        assertThat(events.single().outcome).isEqualTo(RingOutcome.TIMED_OUT)
        assertThat(scheduled(AlarmActions.RC_RING_GUARD)).isEmpty()
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
    fun `with notifications blocked a Quiet bed ring still gets 2 min of sound after its silent start`() {
        plan(planned(today, "06:30", mode = AlarmMode.FIXED, style = WakeStyle.QUIET_BED), tomorrowAlarm)
        reschedule()
        now = target
        notifications.setNotificationsEnabled(false)
        startRing(AlarmActions.KIND_PLAN, id)

        advance(Duration.ofMinutes(2))
        assertThat(m.ringing).isNotNull()
        assertThat(sounds.single().started).isTrue()
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

    /**
     * A catnap rings from 05:25. The 05:26 alarm's RING_PLAN waits behind it, then the minute tick's reschedule turns
     * the unanswered alarm into a CATCH_UP on the extra channel, which waits behind the nap too.
     */
    private fun napWithPlannedRingWaiting(): ServiceController<AlarmRingService> {
        plan(planned(today, "05:26", mode = AlarmMode.FIXED))
        reschedule()
        graph.nightController.startCatnap(Duration.ofMinutes(20))
        now = at(today, "05:25")
        val ring = startRing(AlarmActions.KIND_CATNAP)
        now = at(today, "05:26")
        ring.send(ringIntent(AlarmActions.KIND_PLAN, NAP_QUEUE_ID))
        now = at(today, "05:26:01")
        reschedule()
        val catchUp = checkNotNull(m.extra)
        assertThat(catchUp.extraTrigger).isEqualTo(ExtraTrigger.CATCH_UP)
        now = catchUp.at
        ring.send(ringIntent(AlarmActions.KIND_EXTRA))
        assertThat(m.ringing?.kind).isEqualTo(RingKind.NAP)
        assertThat(m.extra).isEqualTo(catchUp)
        return ring
    }

    @Test
    fun `snoozing a catnap while a planned ring waits gives way to that ring instead of overwriting it`() {
        val ring = napWithPlannedRingWaiting()
        now = at(today, "05:26:10")

        ring.send(ringAction(AlarmActions.ACTION_SNOOZE))

        assertThat(m.ringing?.kind).isEqualTo(RingKind.PLANNED)
        assertThat(m.ringing?.occurrenceId).isEqualTo(NAP_QUEUE_ID)
        assertThat(m.ringing?.ringReason).isEqualTo(RingReason.CATCH_UP)
        assertThat(m.extra).isNull()
        assertThat(sounds).hasSize(2)
        assertThat(sounds.first().stopped).isTrue()
        assertThat(notifications.getNotification(AlarmActions.ID_SNOOZED)).isNull()
        val nap = events.single { it.kind == RingKind.NAP }
        assertThat(nap.outcome).isEqualTo(RingOutcome.STOPPED)
        assertThat(nap.snoozeTimes).containsExactly(now)
    }

    @Test
    fun `a queued planned ring that left the channel meanwhile doesn't ring what replaced it`() {
        val ring = napWithPlannedRingWaiting()
        val test = MirroredExtra(
            trigger = ExtraTrigger.TEST.name,
            atEpochMs = at(today, "05:40").toEpochMilli(),
            ringKind = RingKind.TEST.name,
            occurrenceId = null,
            snoozesUsed = 0,
            reason = RingReason.TEST.name,
            alarm = mirrored(todayAlarm),
            expiresAtEpochMs = at(today, "05:50").toEpochMilli(),
        )
        graph.seed { it.copy(extra = test) }
        now = at(today, "05:27")

        ring.send(ringAction(AlarmActions.ACTION_STOP))

        assertThat(m.ringing).isNull()
        assertThat(m.extra).isEqualTo(test) // it rings at its own time
        assertThat(sounds).hasSize(1)
        assertThat(shadowOf(ring.get()).isStoppedBySelf).isTrue()
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

    private companion object {
        /** The planned alarm that waits behind a catnap ring. */
        const val NAP_QUEUE_ID = "2026-10-06T05:26"
    }
}
