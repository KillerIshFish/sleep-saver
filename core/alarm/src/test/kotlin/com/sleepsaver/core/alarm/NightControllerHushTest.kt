package com.sleepsaver.core.alarm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.alarm.testing.AlarmRobolectricTest
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.config.SleepConfig
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/** Hush's "Until my window" (SPEC_V2 §4.6): it waits for this night's alarm only, never for one days away. */
@RunWith(AndroidJUnit4::class)
internal class NightControllerHushTest : AlarmRobolectricTest() {

    private val defaultTimer = SleepConfig.DEFAULT.hush.defaultTimer

    /** Friday 2026-10-09. */
    private val friday = today.plusDays(3)

    private fun untilMyWindow() = graph.nightController.startHush(HushColor.PINK, timer = null, volume = 0.25f)

    @Test
    fun `Until my window fades out before this night's surfacing window`() {
        now = at(today, "23:00")
        plan(planned(today.plusDays(1), "06:30"))
        reschedule()

        untilMyWindow()

        // The 30-min window opens at 6:00 and sensing starts 15 min before it.
        assertThat(m.hush?.endsAt).isEqualTo(at(today.plusDays(1), "05:45"))
        assertThat(m.hush?.timer).isNull()
    }

    @Test
    fun `in the small hours this morning's alarm is this night's`() {
        now = at(today, "02:00")
        plan(planned(today, "06:30"))
        reschedule()

        untilMyWindow()

        assertThat(m.hush?.endsAt).isEqualTo(at(today, "05:45"))
    }

    @Test
    fun `with no alarm this night Until my window plays the default timer, not on to Monday`() {
        // Friday 23:00: Saturday has no alarm, so Monday's 6:30 is the one armed.
        now = at(friday, "23:00")
        plan(planned(friday.plusDays(3), "06:30"))
        reschedule()
        assertThat(m.armed?.wakeDate).isEqualTo(friday.plusDays(3))

        untilMyWindow()

        assertThat(m.hush?.endsAt).isEqualTo(now + defaultTimer)
        assertThat(m.hush?.timer).isEqualTo(defaultTimer)

        // At 2 AM on Saturday too.
        now = at(friday.plusDays(1), "02:00")
        untilMyWindow()
        assertThat(m.hush?.endsAt).isEqualTo(now + defaultTimer)
    }

    @Test
    fun `a chosen timer is kept whatever the alarm`() {
        now = at(friday, "23:00")
        plan(planned(friday.plusDays(3), "06:30"))
        reschedule()

        graph.nightController.startHush(HushColor.BROWN, Duration.ofMinutes(90), volume = 0.25f)

        assertThat(m.hush?.endsAt).isEqualTo(now + Duration.ofMinutes(90))
        assertThat(m.hush?.timer).isEqualTo(Duration.ofMinutes(90))
    }
}
