package com.sleepsaver.core.alarm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.alarm.testing.AlarmRobolectricTest
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.domain.schedule.AlarmSettings
import com.sleepsaver.core.domain.schedule.AlarmTone
import com.sleepsaver.core.domain.schedule.SnoozeStyle
import com.sleepsaver.core.domain.schedule.WakeStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/** After unlock the bootstrap re-arms whenever what the mirror holds would change (SPEC_V2 §4.9). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
internal class AlarmBootstrapTest : AlarmRobolectricTest() {

    private val todayAlarm = planned(today, "06:30")
    private val tomorrowAlarm = planned(today.plusDays(1), "06:30")

    @Before
    fun startUnlocked() {
        setUnlocked(true)
        plan(todayAlarm, tomorrowAlarm)
        graph.bootstrap.startUnlocked()
        settle()
    }

    /** Runs the bootstrap's plan collector past its debounce. */
    private fun settle() {
        graph.dispatcher.scheduler.advanceTimeBy(Duration.ofSeconds(2).toMillis())
        graph.dispatcher.scheduler.runCurrent()
    }

    private fun PlannedAlarm.withSettings(change: (AlarmSettings) -> AlarmSettings): PlannedAlarm =
        copy(occurrence = occurrence.copy(settings = change(occurrence.settings)))

    @Test
    fun `a new wake-up style, volume and snooze for the next alarm re-arm it with them`() {
        assertThat(m.armed?.wakeStyle).isEqualTo(WakeStyle.STANDARD)
        // 22:30 the evening before: same time and window, now Quiet bed at 30 % with no snooze.
        val quiet = todayAlarm.withSettings { it.copy(style = WakeStyle.QUIET_BED, volume = 0.3f, snooze = it.snooze.copy(style = SnoozeStyle.NONE)) }

        plan(quiet, tomorrowAlarm)
        settle()

        val armed = m.armed!!
        assertThat(armed.occurrenceId).isEqualTo(todayAlarm.occurrence.id)
        assertThat(armed.wakeStyle).isEqualTo(WakeStyle.QUIET_BED)
        assertThat(armed.volume).isEqualTo(0.3f)
        assertThat(armed.snooze.style).isEqualTo(SnoozeStyle.NONE)
        assertThat(ringPlanId).isEqualTo(todayAlarm.occurrence.id)
    }

    @Test
    fun `a change to an alarm after the next one reaches the mirror for a locked re-arm`() {
        val bells = tomorrowAlarm.withSettings { it.copy(tone = AlarmTone.BRIGHT_BELLS) }
        val laterBedtime = bells.copy(plannedBedtime = bells.plannedBedtime!! + Duration.ofHours(1))

        plan(todayAlarm, laterBedtime)
        settle()

        val following = m.upcoming.single()
        assertThat(following.alarmTone).isEqualTo(AlarmTone.BRIGHT_BELLS)
        assertThat(following.expectedOnsetEpochMs).isEqualTo((laterBedtime.plannedBedtime!! + laterBedtime.latency).toEpochMilli())
    }
}
