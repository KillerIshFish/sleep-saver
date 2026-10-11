package com.sleepsaver.core.alarm.night

import android.app.Notification
import android.content.pm.ServiceInfo
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.alarm.AlarmActions
import com.sleepsaver.core.alarm.mirror.ExtraTrigger
import com.sleepsaver.core.alarm.mirror.MirroredExtra
import com.sleepsaver.core.alarm.mirror.MirroredNight
import com.sleepsaver.core.alarm.testing.AlarmRobolectricTest
import com.sleepsaver.core.data.hooks.AlarmIntents
import com.sleepsaver.core.data.hooks.SensingMode
import com.sleepsaver.core.data.hooks.SensingState
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.alarm.MovementEpoch
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.alarm.SensingOutcome
import com.sleepsaver.core.domain.journal.AnchorSource
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.time.Duration

@RunWith(AndroidJUnit4::class)
internal class NightServiceTest : AlarmRobolectricTest() {

    private val todayAlarm = planned(today, "06:30")
    private val tomorrowAlarm = planned(today.plusDays(1), "06:30")
    private val id = todayAlarm.occurrence.id
    private val health = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
    private val media = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

    private fun armToday() {
        plan(todayAlarm, tomorrowAlarm)
        reschedule()
    }

    private fun hush(timer: Duration? = Duration.ofMinutes(90)): ServiceController<NightService> {
        graph.nightController.startHush(HushColor.PINK, timer, volume = 0.25f)
        return startNight(AlarmActions.ACTION_HUSH_START)
    }

    /** 30 quiet calibration epochs from 05:45 (activity 10/14), closed by a sample just after 06:00. */
    private fun calibrate(service: NightService) {
        val epochs = (0 until 30).map { MovementEpoch(at(today, "05:45").plusSeconds(30L * it), if (it % 2 == 0) 10.0 else 14.0) }
        service.onEpochs(epochs, at(today, "06:00:00.020"))
    }

    /** Quiet epochs from 06:00, then a big movement in the epoch that closes at 06:03. */
    private fun restlessAt0603(service: NightService) {
        now = at(today, "06:03:00.020")
        val epochs = (0 until 5).map { MovementEpoch(at(today, "06:00").plusSeconds(30L * it), 12.0) } +
            MovementEpoch(at(today, "06:02:30"), 200.0)
        service.onEpochs(epochs, now)
    }

    // ---------------------------------------------------------------- Reasons and the foreground mask

    @Test
    fun `with no reason left the service stops itself`() {
        val empty = startNight(AlarmActions.ACTION_HUSH_START) // nothing asked for in the mirror

        assertThat(shadowOf(empty.get()).isStoppedBySelf).isTrue()
        assertThat(shadowOf(empty.get()).isForegroundStopped).isTrue()

        val playing = hush()
        assertThat(shadowOf(playing.get()).isStoppedBySelf).isFalse()
        playing.send(nightIntent(AlarmActions.ACTION_HUSH_STOP))

        assertThat(playing.get().foregroundMask).isEqualTo(0)
        assertThat(shadowOf(playing.get()).isStoppedBySelf).isTrue()
        assertThat(m.hush).isNull()
    }

    @Test
    fun `Hush runs as media playback with a quiet night notification that shows no time`() {
        armToday()

        val night = hush()

        val service = shadowOf(night.get())
        assertThat(night.get().foregroundServiceType).isEqualTo(media)
        val notification = service.lastForegroundNotification
        assertThat(notification.channelId).isEqualTo(AlarmIntents.CHANNEL_NIGHT)
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("Hush · Pink")
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo("Never later than 6:30 AM")
        assertThat(notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN)).isFalse()
        assertThat(notification.actions.map { it.title.toString() }).containsExactly("Stop Hush", "+15 min").inOrder()
        assertThat(shadowOf(notification.contentIntent).savedIntent.dataString).isEqualTo(AlarmIntents.DEEP_LINK_NIGHT)
        assertThat(graph.hushOutputs.sounds.single().color).isEqualTo(HushColor.PINK)
        assertThat(graph.nightLog.hushPlaysStarted.single().second).isEqualTo(HushColor.PINK)
        assertThat(m.hush?.playId).isNotNull()
    }

    @Test
    fun `the night notification promises no alarm days away`() {
        // Friday 23:00: Saturday has no alarm, so Monday's 6:30 is the one armed.
        val friday = today.plusDays(3)
        now = at(friday, "23:00")
        plan(planned(friday.plusDays(3), "06:30"))
        reschedule()

        val night = hush()

        val notification = shadowOf(night.get()).lastForegroundNotification
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("Hush · Pink")
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT)).isNull()
    }

    @Test
    fun `Hush fades in, then stops at the end of its timer`() {
        val night = hush(timer = Duration.ofMinutes(15))
        val noise = graph.hushOutputs.sounds.single()
        advance(Duration.ofSeconds(5))
        assertThat(noise.gains.last()).isWithin(1e-3f).of(0.25f * 0.5f)

        advance(Duration.ofMinutes(15))

        assertThat(noise.stopped).isTrue()
        assertThat(m.hush).isNull()
        assertThat(shadowOf(night.get()).isStoppedBySelf).isTrue()
        assertThat(graph.nightLog.hushPlaysEnded).hasSize(1)
    }

    @Test
    fun `the Hush gain fades in from the start and out before the end`() {
        val start = at(today, "23:00")
        val end = start + Duration.ofMinutes(45)
        val fadeIn = Duration.ofSeconds(10)
        val fadeOut = Duration.ofMinutes(5)

        assertThat(NightService.hushGain(start, start, end, 0.4f, fadeIn, fadeOut)).isEqualTo(0f)
        assertThat(NightService.hushGain(start + Duration.ofMinutes(20), start, end, 0.4f, fadeIn, fadeOut)).isEqualTo(0.4f)
        assertThat(NightService.hushGain(end - Duration.ofSeconds(150), start, end, 0.4f, fadeIn, fadeOut)).isWithin(1e-4f).of(0.2f)
        assertThat(NightService.hushGain(end, start, end, 0.4f, fadeIn, fadeOut)).isEqualTo(0f)
        assertThat(NightService.hushGain(start + Duration.ofHours(3), start, null, 0.4f, fadeIn, fadeOut)).isEqualTo(0.4f)
    }

    // ---------------------------------------------------------------- WINDOW (T3, T7)

    @Test
    fun `ACTION_WINDOW to a service already playing Hush adds the health type in onStartCommand`() {
        armToday()
        val night = hush()
        assertThat(night.get().foregroundServiceType).isEqualTo(media)
        now = at(today, "05:45")

        night.send(nightIntent(AlarmActions.ACTION_WINDOW, id))

        assertThat(night.get().foregroundServiceType).isEqualTo(media or health)
        assertThat(night.get().windowRunId).isEqualTo(id)
        assertThat(graph.motion.sources.single().started).isTrue()
        assertThat(graph.nightController.state.value.sensing).isEqualTo(SensingState(SensingMode.WINDOW, now, running = true))
        assertThat(m.armed?.sensingStartEpochMs).isEqualTo(at(today, "05:45").toEpochMilli())
        assertThat(graph.nightLog.movementLogsStarted.single().let { it.mode to it.occurrenceId }).isEqualTo("WINDOW" to id)
        assertThat(graph.hushOutputs.sounds.single().playing).isTrue()
    }

    @Test
    fun `a run that starts after an UNAVAILABLE attempt drops that result`() {
        armToday()
        graph.seed { it.copy(armed = it.armed?.copy(sensingOutcome = SensingOutcome.UNAVAILABLE.name)) }
        now = at(today, "05:50")

        val night = startNight(AlarmActions.ACTION_WINDOW, id)

        assertThat(night.get().windowRunId).isEqualTo(id)
        assertThat(m.armed?.outcome).isNull()
    }

    @Test
    fun `a refused health start leaves sensing unavailable and Hush playing`() {
        armToday()
        val night = hush()
        now = at(today, "05:45")
        shadowOf(night.get()).setThrowInStartForeground(IllegalStateException("Not allowed"))

        night.send(nightIntent(AlarmActions.ACTION_WINDOW, id))

        assertThat(m.armed?.outcome).isEqualTo(SensingOutcome.UNAVAILABLE)
        assertThat(night.get().windowRunId).isNull()
        assertThat(night.get().foregroundMask).isEqualTo(2) // mediaPlayback only
        assertThat(graph.hushOutputs.sounds.single().playing).isTrue()
        assertThat(m.hush).isNotNull()
    }

    @Test
    fun `the same WINDOW twice keeps one run, and a stale id runs nothing`() {
        armToday()
        now = at(today, "05:45")
        val night = startNight(AlarmActions.ACTION_WINDOW, id)

        night.send(nightIntent(AlarmActions.ACTION_WINDOW, id))
        assertThat(graph.motion.sources).hasSize(1)
        assertThat(night.get().foregroundServiceType).isEqualTo(health)

        val stale = startNight(AlarmActions.ACTION_WINDOW, "2026-10-05T06:30")
        assertThat(stale.get().windowRunId).isNull()
        assertThat(shadowOf(stale.get()).isStoppedBySelf).isTrue()
    }

    @Test
    fun `a window run ends without any AlarmManager call when the armed id changes`() {
        armToday()
        now = at(today, "05:45")
        val night = startNight(AlarmActions.ACTION_WINDOW, id)
        val before = alarms.scheduledAlarms.map { shadowOf(it.operation).requestCode to it.triggerAtMs }
        graph.seed { it.copy(armed = mirrored(tomorrowAlarm)) } // the plan moved on

        night.get().onEpochs(listOf(MovementEpoch(at(today, "05:45"), 12.0)), at(today, "05:45:30.020"))
        idleMain()

        assertThat(night.get().windowRunId).isNull()
        assertThat(alarms.scheduledAlarms.map { shadowOf(it.operation).requestCode to it.triggerAtMs }).isEqualTo(before)
        assertThat(m.extra).isNull()
        assertThat(graph.nightLog.movementLogsEnded).hasSize(1)
        assertThat(shadowOf(night.get()).isStoppedBySelf).isTrue()
    }

    @Test
    fun `a reschedule that arms another occurrence ends the run (T7)`() {
        armToday()
        now = at(today, "05:45")
        val night = startNight(AlarmActions.ACTION_WINDOW, id)

        plan(tomorrowAlarm) // today's alarm switched off
        reschedule()

        assertThat(night.get().windowRunId).isNull()
        assertThat(m.armed?.occurrenceId).isEqualTo(tomorrowAlarm.occurrence.id)
    }

    @Test
    fun `a WINDOW for another occurrence replaces the run and keeps the service running`() {
        armToday()
        now = at(today, "05:45")
        val night = startNight(AlarmActions.ACTION_WINDOW, id)
        graph.seed { it.copy(armed = mirrored(tomorrowAlarm)) } // armed moved on without telling the service

        night.send(nightIntent(AlarmActions.ACTION_WINDOW, tomorrowAlarm.occurrence.id))
        idleMain()

        assertThat(night.get().windowRunId).isEqualTo(tomorrowAlarm.occurrence.id)
        assertThat(night.get().foregroundServiceType).isEqualTo(health)
        assertThat(shadowOf(night.get()).isStoppedBySelf).isFalse()
        assertThat(graph.nightLog.movementLogsEnded).hasSize(1) // the old run's log
        assertThat(graph.nightLog.movementLogsStarted.map { it.occurrenceId })
            .containsExactly(id, tomorrowAlarm.occurrence.id).inOrder()
    }

    @Test
    fun `I'm up closes a whole-night log whose service is gone`() {
        graph.seed {
            it.copy(
                night = MirroredNight(
                    anchorId = 4,
                    anchoredAtEpochMs = at(today, "00:10").toEpochMilli(),
                    offsetSec = -4 * 3600,
                    wholeNightLog = true,
                    logId = 7,
                    endsAtEpochMs = at(today, "16:10").toEpochMilli(),
                ),
            )
        }
        assertThat(graph.nightController.state.value.sensing?.running).isFalse() // Resume is offered

        graph.nightController.endNight()

        assertThat(graph.nightLog.movementLogsEnded).containsExactly(7L to now)
        assertThat(graph.nightController.state.value.night).isNull()
        assertThat(graph.nightController.state.value.sensing).isNull()
    }

    // ---------------------------------------------------------------- Fire (T4)

    @Test
    fun `a restless moment arms SMART on RING_EXTRA in 5 s and keeps RING_PLAN as the backstop`() {
        armToday()
        now = at(today, "05:45")
        val night = startNight(AlarmActions.ACTION_WINDOW, id)
        calibrate(night.get())
        assertThat(m.extra).isNull()

        restlessAt0603(night.get())

        val smart = checkNotNull(m.extra)
        assertThat(smart.extraTrigger).isEqualTo(ExtraTrigger.SMART)
        assertThat(smart.kind).isEqualTo(RingKind.PLANNED)
        assertThat(smart.ringReason).isEqualTo(RingReason.RESTLESS)
        assertThat(smart.occurrenceId).isEqualTo(id)
        assertThat(smart.at).isEqualTo(now + Duration.ofSeconds(5))
        assertThat(ringExtraAt).isEqualTo(now + Duration.ofSeconds(5))
        assertThat(ringPlanAt).isEqualTo(at(today, "06:30"))
        assertThat(m.armed?.outcome).isEqualTo(SensingOutcome.RESTLESS)
        assertThat(m.armed?.maxZ).isNotNull()
        assertThat(night.get().windowRunId).isNull()
        assertThat(startedServices.none { it.component?.className == NightService::class.java.name }).isTrue()
    }

    @Test
    fun `a restless moment arms nothing when the armed id differs`() {
        armToday()
        now = at(today, "05:45")
        val night = startNight(AlarmActions.ACTION_WINDOW, id)
        calibrate(night.get())
        graph.seed { it.copy(armed = it.armed?.copy(occurrenceId = "2026-10-06T07:00")) }

        restlessAt0603(night.get())

        assertThat(m.extra).isNull()
        assertThat(ringExtraAt).isNull()
        assertThat(night.get().windowRunId).isNull()
    }

    @Test
    fun `a restless moment arms nothing while a planned ring is pending`() {
        armToday()
        now = at(today, "05:45")
        val night = startNight(AlarmActions.ACTION_WINDOW, id)
        calibrate(night.get())
        val pending = MirroredExtra(
            trigger = ExtraTrigger.CATCH_UP.name,
            atEpochMs = at(today, "06:10").toEpochMilli(),
            ringKind = RingKind.PLANNED.name,
            occurrenceId = id,
            snoozesUsed = 0,
            reason = RingReason.CATCH_UP.name,
            alarm = m.armed,
            expiresAtEpochMs = Long.MAX_VALUE,
        )
        graph.seed { it.copy(extra = pending) }

        restlessAt0603(night.get())

        assertThat(m.extra).isEqualTo(pending)
    }

    @Test
    fun `a run that reaches the wake time records its outcome for the ring`() {
        armToday()
        now = at(today, "05:45")
        val night = startNight(AlarmActions.ACTION_WINDOW, id)
        calibrate(night.get())

        now = at(today, "06:30")
        idleMain(Duration.ofMinutes(46))

        assertThat(m.armed?.outcome).isEqualTo(SensingOutcome.NO_TRIGGER)
        assertThat(m.extra).isNull()
        assertThat(night.get().windowRunId).isNull()
    }

    // ---------------------------------------------------------------- Hush and the ring

    @Test
    fun `Hush pauses for a transient focus loss, but never resumes after stopHushForRing`() {
        val night = hush()
        val service = night.get()
        val noise = graph.hushOutputs.sounds.single()

        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertThat(noise.playing).isFalse()
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertThat(noise.playing).isTrue()

        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        service.stopHushForRing()
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertThat(noise.playing).isFalse()
        assertThat(noise.stopped).isTrue()
        assertThat(graph.hushOutputs.sounds).hasSize(1)
        assertThat(m.hush).isNull()
        assertThat(graph.nightLog.hushPlaysEnded).hasSize(1)
    }

    @Test
    fun `a permanent focus loss stops Hush`() {
        val night = hush()

        night.get().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        assertThat(graph.hushOutputs.sounds.single().stopped).isTrue()
        assertThat(m.hush).isNull()
    }

    // ---------------------------------------------------------------- Whole-night log

    @Test
    fun `the whole-night log runs on the health type, and a window run shares its stream`() {
        armToday()
        runBlocking { graph.nightController.dropAnchor(AnchorSource.TONIGHT, wholeNightLog = true) }
        assertThat(startedServices.last().action).isEqualTo(AlarmActions.ACTION_LOG_START)

        val night = startNight(AlarmActions.ACTION_LOG_START)

        assertThat(night.get().foregroundServiceType).isEqualTo(health)
        val log = graph.nightLog.movementLogsStarted.single()
        assertThat(log.mode).isEqualTo("WHOLE_NIGHT")
        assertThat(log.anchorId).isEqualTo(m.night?.anchorId)
        assertThat(m.night?.logId).isNotNull()
        assertThat(graph.nightController.state.value.sensing?.mode).isEqualTo(SensingMode.WHOLE_NIGHT)
        assertThat(notifications.getNotification(AlarmActions.ID_NIGHT).extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            .isEqualTo("Movement log on")

        now = at(today, "05:45")
        night.send(nightIntent(AlarmActions.ACTION_WINDOW, id))

        assertThat(night.get().windowRunId).isEqualTo(id)
        assertThat(graph.motion.sources).hasSize(1)
        assertThat(graph.nightLog.movementLogsStarted).hasSize(1)
        // sensingStart = max(stream start, window start − lead-in) (lead amendment 5)
        assertThat(m.armed?.sensingStartEpochMs).isEqualTo(at(today, "05:45").toEpochMilli())
    }

    @Test
    fun `stopAll closes the log and the Hush play, then stops`() {
        armToday()
        runBlocking { graph.nightController.dropAnchor(AnchorSource.TONIGHT, wholeNightLog = true) }
        val night = startNight(AlarmActions.ACTION_LOG_START)
        graph.nightController.startHush(HushColor.BROWN, Duration.ofMinutes(90), 0.3f)
        night.send(nightIntent(AlarmActions.ACTION_HUSH_START))
        assertThat(night.get().foregroundServiceType).isEqualTo(health or media)

        night.get().stopAll()

        assertThat(graph.nightLog.movementLogsEnded).hasSize(1)
        assertThat(graph.nightLog.hushPlaysEnded).hasSize(1)
        assertThat(graph.motion.sources.single().stopped).isTrue()
        assertThat(shadowOf(night.get()).isStoppedBySelf).isTrue()
        assertThat(m.hush).isNull()
    }
}
