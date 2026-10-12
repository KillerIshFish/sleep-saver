package com.sleepsaver.core.alarm.receiver

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioAttributes
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.alarm.AlarmActions
import com.sleepsaver.core.alarm.AlarmChannels
import com.sleepsaver.core.alarm.mirror.ExtraTrigger
import com.sleepsaver.core.alarm.night.NightService
import com.sleepsaver.core.alarm.ring.AlarmRingService
import com.sleepsaver.core.alarm.testing.AlarmRobolectricTest
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingOutcome
import com.sleepsaver.core.domain.alarm.SensingOutcome
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(AndroidJUnit4::class)
internal class AlarmReceiverTest : AlarmRobolectricTest() {

    private val todayAlarm = planned(today, "06:30")
    private val tomorrowAlarm = planned(today.plusDays(1), "06:30")
    private val id = todayAlarm.occurrence.id

    private fun armToday() {
        plan(todayAlarm, tomorrowAlarm)
        reschedule()
    }

    /** The broadcast the armed PendingIntent with [requestCode] delivers. */
    private fun broadcastOf(requestCode: Int): Intent = shadowOf(scheduled(requestCode).single().operation).savedIntent

    private fun receive(intent: Intent, on: Context = context) = AlarmReceiver().onReceive(on, intent)

    // ---------------------------------------------------------------- RING (T5)

    @Test
    fun `RING starts the ring service in the foreground with the kind and occurrence`() {
        armToday()
        now = at(today, "06:30")

        receive(broadcastOf(AlarmActions.RC_RING_PLAN))

        val started = startedServices.single()
        assertThat(started.component?.className).isEqualTo(AlarmRingService::class.java.name)
        assertThat(started.action).isEqualTo(AlarmActions.ACTION_RING)
        assertThat(started.getStringExtra(AlarmActions.EXTRA_KIND)).isEqualTo(AlarmActions.KIND_PLAN)
        assertThat(started.getStringExtra(AlarmActions.EXTRA_OCCURRENCE_ID)).isEqualTo(id)
    }

    @Test
    fun `a refused ring service start shows notification 303 with the full-screen intent, Snooze and Stop`() {
        armToday()
        now = at(today, "06:30")

        receive(broadcastOf(AlarmActions.RC_RING_PLAN), on = RefusingContext(context))

        val fallback = notifications.getNotification(AlarmActions.ID_RING_FALLBACK)
        assertThat(fallback.channelId).isEqualTo(AlarmChannels.CHANNEL_RING_FALLBACK)
        assertThat(fallback.fullScreenIntent).isNotNull()
        assertThat(fallback.group).isNull() // nothing mutes its alerts
        assertThat(fallback.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("Your alarm")
        assertThat(fallback.actions.map { it.title.toString() }).containsExactly("Snooze until 6:39 AM", "Stop").inOrder()
        assertThat(m.ringing?.occurrenceId).isEqualTo(id)
        assertThat(m.ringing?.fallback).isTrue()
        assertThat(m.extra).isNull() // the reschedule after the ring doesn't turn it into a resume loop
        assertThat(graph.nightLog.eventsState.value.single().firstRingAt).isEqualTo(now)
        assertThat(ringPlanId).isEqualTo(tomorrowAlarm.occurrence.id)
        assertThat(startedServices).isEmpty()

        // Its Stop reaches the ring service (not sounding), which stops the ring from the mirror.
        val stop = shadowOf(fallback.actions[1].actionIntent).savedIntent
        Robolectric.buildService(AlarmRingService::class.java, stop).create().startCommand(0, 1)

        assertThat(m.ringing).isNull()
        assertThat(m.handledIds).contains(id)
        assertThat(notifications.getNotification(AlarmActions.ID_RING_FALLBACK)).isNull()
        assertThat(graph.nightLog.eventsState.value.single().outcome).isEqualTo(RingOutcome.STOPPED)
    }

    @Test
    fun `a refused ring times out at its guard, 303 goes and the backup ring still comes`() {
        armToday()
        now = at(today, "06:30")
        receive(broadcastOf(AlarmActions.RC_RING_PLAN), on = RefusingContext(context))
        assertThat(armedAt(AlarmActions.RC_RING_GUARD)).isEqualTo(at(today, "06:40"))

        now = at(today, "06:40")
        receive(broadcastOf(AlarmActions.RC_RING_GUARD))

        assertThat(m.ringing).isNull()
        assertThat(m.handledIds).contains(id)
        assertThat(m.extra?.extraTrigger).isEqualTo(ExtraTrigger.BACKUP)
        assertThat(ringExtraAt).isEqualTo(at(today, "06:45"))
        assertThat(notifications.getNotification(AlarmActions.ID_RING_FALLBACK)).isNull()
        assertThat(graph.nightLog.eventsState.value.single().outcome).isEqualTo(RingOutcome.TIMED_OUT)
        assertThat(scheduled(AlarmActions.RC_RING_GUARD)).isEmpty()
    }

    @Test
    fun `the guard of a ring that is still sounding changes nothing`() {
        armToday()
        now = at(today, "06:30")
        startRing(AlarmActions.KIND_PLAN, id)
        val ringing = m.ringing

        now = at(today, "06:40")
        receive(broadcastOf(AlarmActions.RC_RING_GUARD))

        assertThat(m.ringing).isEqualTo(ringing)
        assertThat(m.extra).isNull()
    }

    @Test
    fun `with no ring service, notification 303 itself rings with the phone's alarm sound until the ring times out`() {
        armToday()
        now = at(today, "06:30")

        receive(broadcastOf(AlarmActions.RC_RING_PLAN), on = RefusingContext(context))

        val fallback = notifications.getNotification(AlarmActions.ID_RING_FALLBACK)
        assertThat(fallback.flags and Notification.FLAG_INSISTENT).isNotEqualTo(0)
        assertThat(fallback.timeoutAfter).isEqualTo(Duration.ofMinutes(10).toMillis())
        val channel = notificationManager.getNotificationChannel(AlarmChannels.CHANNEL_RING_FALLBACK)
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_HIGH)
        assertThat(channel.sound).isEqualTo(Settings.System.DEFAULT_ALARM_ALERT_URI)
        assertThat(channel.audioAttributes.usage).isEqualTo(AudioAttributes.USAGE_ALARM)
        assertThat(channel.shouldVibrate()).isTrue()
    }

    @Test
    fun `a fallback ring shows as ringing, so the ringing screen can stop it, until its timeout`() {
        armToday()
        now = at(today, "06:30")
        receive(broadcastOf(AlarmActions.RC_RING_PLAN), on = RefusingContext(context))

        val ringing = checkNotNull(graph.nightController.state.value.ringing)
        assertThat(ringing.kind).isEqualTo(RingKind.PLANNED)
        assertThat(ringing.since).isEqualTo(now)
        assertThat(ringing.wakeDate).isEqualTo(today)
        assertThat(ringing.snooze.until).isEqualTo(at(today, "06:39"))

        now = at(today, "06:31")
        graph.nightController.stopRinging() // the ringing screen's Stop

        assertThat(graph.nightController.state.value.ringing).isNull()
        assertThat(m.ringing).isNull()
        assertThat(m.handledIds).contains(id)
        assertThat(notifications.getNotification(AlarmActions.ID_RING_FALLBACK)).isNull()
        assertThat(graph.nightLog.eventsState.value.single().outcome).isEqualTo(RingOutcome.STOPPED)
    }

    @Test
    fun `a fallback ring left unanswered stops showing as ringing at its timeout`() {
        armToday()
        now = at(today, "06:30")
        receive(broadcastOf(AlarmActions.RC_RING_PLAN), on = RefusingContext(context))
        assertThat(graph.nightController.state.value.ringing).isNotNull()

        now = at(today, "06:40")
        graph.nightController.refresh()

        assertThat(graph.nightController.state.value.ringing).isNull()
    }

    @Test
    fun `the ringing screen's Snooze on a fallback ring snoozes it from the mirror`() {
        armToday()
        now = at(today, "06:30")
        receive(broadcastOf(AlarmActions.RC_RING_PLAN), on = RefusingContext(context))

        graph.nightController.snooze()

        assertThat(graph.nightController.state.value.ringing).isNull()
        assertThat(graph.nightController.state.value.snoozedUntil).isEqualTo(at(today, "06:39"))
        assertThat(notifications.getNotification(AlarmActions.ID_RING_FALLBACK)).isNull()
        assertThat(notifications.getNotification(AlarmActions.ID_SNOOZED)).isNotNull()
    }

    // ---------------------------------------------------------------- WINDOW (T3)

    @Test
    fun `WINDOW while locked starts nothing and leaves sensing unavailable`() {
        armToday()
        now = at(today, "05:45")
        setUnlocked(false)

        receive(broadcastOf(AlarmActions.RC_WINDOW))

        assertThat(startedServices).isEmpty()
        assertThat(m.armed?.outcome).isEqualTo(SensingOutcome.UNAVAILABLE)
    }

    @Test
    fun `WINDOW starts the night service in the foreground, also when it already plays Hush`() {
        armToday()
        graph.nightController.startHush(HushColor.PINK, Duration.ofMinutes(90), 0.25f)
        val night = startNight(AlarmActions.ACTION_HUSH_START)
        shadowOf(context).clearStartedServices()
        now = at(today, "05:45")

        receive(broadcastOf(AlarmActions.RC_WINDOW))

        val started = startedServices.single()
        assertThat(started.component?.className).isEqualTo(NightService::class.java.name)
        assertThat(started.action).isEqualTo(AlarmActions.ACTION_WINDOW)
        assertThat(started.getStringExtra(AlarmActions.EXTRA_OCCURRENCE_ID)).isEqualTo(id)
        assertThat(night.get().windowRunId).isNull() // no in-process shortcut: only the exempt start opens the window
    }

    @Test
    fun `a stale WINDOW is ignored`() {
        armToday()
        now = at(today, "05:45")
        val stale = broadcastOf(AlarmActions.RC_WINDOW).putExtra(AlarmActions.EXTRA_OCCURRENCE_ID, "2026-10-05T06:30")

        receive(stale)

        assertThat(startedServices).isEmpty()
        assertThat(m.armed?.outcome).isNull()
    }

    @Test
    fun `a refused night service start leaves sensing unavailable`() {
        armToday()
        now = at(today, "05:45")

        receive(broadcastOf(AlarmActions.RC_WINDOW), on = RefusingContext(context))

        assertThat(m.armed?.outcome).isEqualTo(SensingOutcome.UNAVAILABLE)
    }

    // ---------------------------------------------------------------- Notification actions

    @Test
    fun `Stop on the snoozed notification ends the snooze and the occurrence`() {
        armToday()
        now = at(today, "06:30")
        startRing(AlarmActions.KIND_PLAN, id).send(ringAction(AlarmActions.ACTION_SNOOZE))
        val stop = shadowOf(notifications.getNotification(AlarmActions.ID_SNOOZED).actions.single().actionIntent).savedIntent
        now = at(today, "06:33")

        receive(stop)

        assertThat(m.extra).isNull()
        assertThat(scheduled(AlarmActions.RC_RING_EXTRA)).isEmpty()
        assertThat(m.handledIds).contains(id)
        assertThat(notifications.getNotification(AlarmActions.ID_SNOOZED)).isNull()
        val event = graph.nightLog.eventsState.value.single()
        assertThat(event.outcome).isEqualTo(RingOutcome.STOPPED)
        assertThat(event.stoppedAt).isEqualTo(now)
        assertThat(ringPlanId).isEqualTo(tomorrowAlarm.occurrence.id)
    }

    @Test
    fun `Cancel on the catnap notification cancels the catnap`() {
        assertThat(graph.nightController.startCatnap(Duration.ofMinutes(20))).isTrue()
        val pending = notifications.getNotification(AlarmActions.ID_CATNAP)
        assertThat(pending.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("Catnap · rings at 5:25 AM")
        val cancel = shadowOf(pending.actions.single().actionIntent).savedIntent

        receive(cancel)

        assertThat(m.catnap).isNull()
        assertThat(ringCatnapAt).isNull()
        assertThat(notifications.getNotification(AlarmActions.ID_CATNAP)).isNull()
        assertThat(graph.nightController.state.value.catnap).isNull()
    }

    @Test
    fun `Hush +15 min moves the end, and Stop Hush stops it`() {
        graph.nightController.startHush(HushColor.WHITE, Duration.ofMinutes(30), 0.25f)
        val night = startNight(AlarmActions.ACTION_HUSH_START)
        val endsAt = checkNotNull(m.hush?.endsAt)
        val actions = shadowOf(night.get()).lastForegroundNotification.actions

        receive(shadowOf(actions[1].actionIntent).savedIntent)
        assertThat(m.hush?.endsAt).isEqualTo(endsAt + Duration.ofMinutes(15))

        receive(shadowOf(actions[0].actionIntent).savedIntent)
        assertThat(m.hush).isNull()
        assertThat(graph.hushOutputs.sounds.single().stopped).isTrue()
    }

    /** startForegroundService is refused (Android 12+: started from the background without an exemption). */
    private class RefusingContext(base: Context) : ContextWrapper(base) {
        override fun startForegroundService(service: Intent): ComponentName? =
            throw ForegroundServiceStartNotAllowedException("startForegroundService() not allowed")
    }
}
