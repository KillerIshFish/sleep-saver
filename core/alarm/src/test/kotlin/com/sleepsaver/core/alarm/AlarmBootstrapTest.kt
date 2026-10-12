package com.sleepsaver.core.alarm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.alarm.testing.AlarmRobolectricTest
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.domain.schedule.AlarmTone
import com.sleepsaver.core.domain.schedule.SnoozeSettings
import com.sleepsaver.core.domain.schedule.SnoozeStyle
import com.sleepsaver.core.domain.schedule.WakeStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/** AlarmBootstrap's plan collector (SPEC_V2 §4.9): every plan edit the mirror copies triggers a reschedule. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
internal class AlarmBootstrapTest : AlarmRobolectricTest() {

    private val tomorrow = today.plusDays(1)
    private val softTuesday = planned(today, "06:30").with(AlarmTone.SOFT_CHIMES, 0.4f, WakeStyle.CALM, SnoozeSettings(style = SnoozeStyle.TAPERING))
    private val wednesday = planned(tomorrow, "06:30").with(AlarmTone.WARM_RISE, 0.5f, WakeStyle.STANDARD, SnoozeSettings(style = SnoozeStyle.TAPERING))

    @Before
    fun unlock() {
        setUnlocked(true)
    }

    @Test
    fun `changing the next alarm's tone, volume, style and snooze re-arms it with the new settings`() {
        plan(softTuesday, wednesday)
        graph.bootstrap.startUnlocked()
        settle()
        assertThat(m.armed?.alarmTone).isEqualTo(AlarmTone.SOFT_CHIMES)

        // Same occurrence, same window: only the settings change.
        plan(softTuesday.with(AlarmTone.BRIGHT_BELLS, 0.9f, WakeStyle.QUIET_BED, SnoozeSettings(style = SnoozeStyle.NONE)), wednesday)
        settle()

        val armed = m.armed!!
        assertThat(armed.occurrenceId).isEqualTo(softTuesday.occurrence.id)
        assertThat(armed.alarmTone).isEqualTo(AlarmTone.BRIGHT_BELLS)
        assertThat(armed.volume).isEqualTo(0.9f)
        assertThat(armed.wakeStyle).isEqualTo(WakeStyle.QUIET_BED)
        assertThat(armed.snooze.style).isEqualTo(SnoozeStyle.NONE)
        assertThat(ringPlanId).isEqualTo(softTuesday.occurrence.id)
    }

    @Test
    fun `changing a following alarm refreshes upcoming, which the locked re-arm promotes from`() {
        plan(softTuesday, wednesday)
        graph.bootstrap.startUnlocked()
        settle()

        plan(softTuesday, wednesday.with(AlarmTone.BRIGHT_BELLS, 0.8f, WakeStyle.FIRM, SnoozeSettings(style = SnoozeStyle.FIXED)))
        settle()

        val following = m.upcoming.single()
        assertThat(following.alarmTone).isEqualTo(AlarmTone.BRIGHT_BELLS)
        assertThat(following.volume).isEqualTo(0.8f)
        assertThat(following.wakeStyle).isEqualTo(WakeStyle.FIRM)
        assertThat(following.snooze.style).isEqualTo(SnoozeStyle.FIXED)
    }

    @Test
    fun `a later planned bedtime for a following alarm reaches the mirror's expected onset`() {
        plan(softTuesday, wednesday)
        graph.bootstrap.startUnlocked()
        settle()

        val laterBedtime = wednesday.copy(plannedBedtime = wednesday.plannedBedtime!! + Duration.ofHours(1))
        plan(softTuesday, laterBedtime)
        settle()

        assertThat(m.upcoming.single().expectedOnsetEpochMs)
            .isEqualTo((laterBedtime.plannedBedtime!! + laterBedtime.latency).toEpochMilli())
    }

    /** Past the collector's 1-s debounce (virtual time on the app scope). */
    private fun settle() {
        graph.dispatcher.scheduler.advanceTimeBy(2_000)
        graph.dispatcher.scheduler.runCurrent()
    }

    private fun PlannedAlarm.with(tone: AlarmTone, volume: Float, style: WakeStyle, snooze: SnoozeSettings): PlannedAlarm =
        copy(occurrence = occurrence.copy(settings = occurrence.settings.copy(tone = tone, volume = volume, style = style, snooze = snooze)))
}
