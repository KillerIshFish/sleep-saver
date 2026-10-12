package com.sleepsaver.feature.tonight.oneoff

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.alarm.EndedAlarms
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.PlanPause
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.feature.tonight.TonightSamples
import org.junit.Test
import java.time.LocalTime

/** Which one-off changes bring an alarm back (Mon–Fri alarms; today is Tuesday). */
class OneOffContextTest {

    private val today = TonightSamples.today

    private fun context(oneOffs: OneOffs) = OneOffContext(TonightSamples.week, oneOffs, TonightSamples.usualWake, today)

    @Test
    fun `a pause that ends sooner brings back the alarms after it`() {
        val paused = context(OneOffs(pause = PlanPause(today, today.plusDays(10))))

        assertThat(paused.pausingArms(today.plusDays(1))).isTrue()
        assertThat(paused.pausingArms(today.plusDays(10))).isFalse()
        assertThat(paused.pausingArms(today.plusDays(20))).isFalse()
    }

    @Test
    fun `just this once at the time whose alarm already ended is that alarm, another time isn't`() {
        // Wednesday's 6:45 rang, then the clock went back to Tuesday.
        val wednesday = today.plusDays(1)
        val ended = context(OneOffs()).copy(ended = EndedAlarms(ids = setOf("${wednesday}T06:45"), dates = setOf(wednesday)))

        assertThat(ended.alreadyEnded(wednesday, LocalTime.of(6, 45))).isTrue()
        assertThat(ended.alreadyEnded(wednesday, LocalTime.of(6, 50))).isFalse()
        assertThat(ended.alreadyEnded(wednesday.plusDays(7), LocalTime.of(6, 45))).isFalse()
        assertThat(context(OneOffs()).alreadyEnded(wednesday, LocalTime.of(6, 45))).isFalse()
    }

    @Test
    fun `a first pause only takes alarms away`() {
        assertThat(context(OneOffs()).pausingArms(today.plusDays(5))).isFalse()
    }

    @Test
    fun `ending a pause sooner over days without an alarm brings nothing back`() {
        // Paused through Sunday; ending it Friday frees only the weekend, which has no alarm.
        val friday = today.plusDays(3)
        val sunday = today.plusDays(5)
        assertThat(context(OneOffs(pause = PlanPause(today, sunday))).pausingArms(friday)).isFalse()

        // A day off on the one alarm day it frees keeps it quiet too.
        val monday = today.plusDays(6)
        val untilMonday = PlanSamples.oneOffs(dayOff = monday, pause = PlanPause(today, monday))
        assertThat(context(untilMonday).pausingArms(sunday)).isFalse()
    }
}
