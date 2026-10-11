package com.sleepsaver.core.alarm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.alarm.mirror.MirroredNight
import com.sleepsaver.core.alarm.receiver.AlarmReceiver
import com.sleepsaver.core.alarm.ring.AlarmRingService
import com.sleepsaver.core.alarm.testing.AlarmRobolectricTest
import com.sleepsaver.core.data.hooks.AlarmIntents
import com.sleepsaver.core.domain.alarm.RingOutcome
import com.sleepsaver.core.domain.journal.FeelSource
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.AlarmSettings
import com.sleepsaver.core.domain.schedule.AlarmTone
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * Before the first unlock after a reboot only device-protected storage exists: every repository Lazy throws when
 * resolved, the user is locked, and the alarm must still arm, ring, stop and remember what happened (SPEC_V2 §4.9).
 * (The RingingViewModel half of this check lives in feature:tonight.)
 */
@RunWith(AndroidJUnit4::class)
internal class DirectBootTest : AlarmRobolectricTest() {

    private val todayAlarm = planned(today, "06:30").let { p ->
        // The phone's own alarm sound lives in credential-encrypted storage: before unlock it must play Soft chimes.
        p.copy(occurrence = p.occurrence.copy(settings = AlarmSettings(mode = AlarmMode.FIXED, tone = AlarmTone.SYSTEM_ALARM)))
    }
    private val tomorrowAlarm = planned(today.plusDays(1), "06:30")
    private val id = todayAlarm.occurrence.id
    private val night = MirroredNight(5, at(today.minusDays(1), "23:05").toEpochMilli(), -14_400, wholeNightLog = false, logId = null, endsAtEpochMs = Long.MAX_VALUE)

    @Before
    fun rebootLocked() {
        graph.seed { it.copy(armed = mirrored(todayAlarm), upcoming = listOf(mirrored(tomorrowAlarm)), night = night) }
        setUnlocked(false)
        graph.lockRepositories()
    }

    @Test
    fun `startLocked creates the channels, re-arms from the mirror and restores the night, resolving no repository`() {
        notificationManager.deleteNotificationChannel(AlarmIntents.CHANNEL_RING)

        graph.bootstrap.startLocked()

        assertThat(notificationManager.getNotificationChannel(AlarmIntents.CHANNEL_RING)).isNotNull()
        assertThat(ringPlanAt).isEqualTo(at(today, "06:30"))
        assertThat(ringPlanId).isEqualTo(id)
        assertThat(graph.nightController.state.value.night?.anchorId).isEqualTo(5)
        assertThat(graph.repositoryResolutions).isEqualTo(0)
        assertThat(startedServices).isEmpty()
    }

    @Test
    fun `the RING path rings before unlock, queues its events in the mirror and resolves no repository`() {
        graph.bootstrap.startLocked()
        now = at(today, "06:30")
        val ringBroadcast = shadowOf(scheduled(AlarmActions.RC_RING_PLAN).single().operation).savedIntent

        AlarmReceiver().onReceive(context, ringBroadcast)
        val start = startedServices.single()
        val ring = Robolectric.buildService(AlarmRingService::class.java, start).create().startCommand(0, 1)

        assertThat(shadowOf(ring.get()).lastForegroundNotificationId).isEqualTo(AlarmActions.ID_RINGING)
        assertThat(graph.nightController.state.value.ringing?.wakeDate).isEqualTo(today)
        val sound = graph.ringOutputs.sounds.single()
        assertThat(sound.locked).isTrue()
        assertThat(m.pendingEvents.single().occurrenceId).isEqualTo(id)
        assertThat(ringPlanId).isEqualTo(tomorrowAlarm.occurrence.id) // locked re-arm from `upcoming`

        ring.send(ringAction(AlarmActions.ACTION_STOP))
        graph.nightController.recordMorningFeel(today, MorningFeel.CLEAR)

        assertThat(m.pendingEvents.single().outcome).isEqualTo(RingOutcome.STOPPED.name)
        assertThat(m.handledIds).contains(id)
        assertThat(m.night).isNull()
        assertThat(m.pendingFeel?.feel).isEqualTo(MorningFeel.CLEAR)
        assertThat(graph.repositoryResolutions).isEqualTo(0)
        assertThat(graph.nightLog.recordedEvents).isEmpty()
    }

    @Test
    fun `after unlock the buffered events and Morning feel reach Room once`() {
        graph.bootstrap.startLocked()
        now = at(today, "06:30")
        val ring = startRing(AlarmActions.KIND_PLAN, id)
        now = at(today, "06:31")
        ring.send(ringAction(AlarmActions.ACTION_STOP))
        graph.nightController.recordMorningFeel(today, MorningFeel.OKAY)
        plan(tomorrowAlarm)

        setUnlocked(true)
        graph.unlockRepositories()
        graph.bootstrap.startUnlocked()
        graph.bootstrap.startUnlocked() // once only

        val event = graph.nightLog.eventsState.value.single()
        assertThat(event.outcome).isEqualTo(RingOutcome.STOPPED)
        assertThat(event.stoppedAt).isEqualTo(at(today, "06:31"))
        assertThat(graph.journal.feelsSet.single().let { Triple(it.wakeDate, it.feel, it.source) })
            .isEqualTo(Triple(today, MorningFeel.OKAY, FeelSource.RING))
        assertThat(m.pendingEvents).isEmpty()
        assertThat(m.pendingFeel).isNull()
        assertThat(graph.fakeSchedule.alarmPlanCalls).hasSize(1)
        assertThat(ringPlanId).isEqualTo(tomorrowAlarm.occurrence.id)
    }

    @Test
    fun `a WINDOW before unlock senses nothing, and the wake-time ring still comes`() {
        graph.seed { it.copy(armed = mirrored(planned(today, "06:30")), upcoming = listOf(mirrored(tomorrowAlarm))) }
        graph.bootstrap.startLocked()
        now = at(today, "05:45")

        AlarmReceiver().onReceive(context, shadowOf(scheduled(AlarmActions.RC_WINDOW).single().operation).savedIntent)

        assertThat(startedServices).isEmpty()
        assertThat(ringPlanAt).isEqualTo(at(today, "06:30"))
        assertThat(graph.repositoryResolutions).isEqualTo(0)
    }
}
