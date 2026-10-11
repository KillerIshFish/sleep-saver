package com.sleepsaver.core.domain.alarm

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.alarm.AlarmFixtures.at
import com.sleepsaver.core.domain.schedule.SnoozeSettings
import com.sleepsaver.core.domain.schedule.SnoozeStyle
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Test

class SnoozeMathTest {

    private val math = SnoozeMath()
    private val target = at("06:30")

    /** Snoozes one after the other from a ring at [firstRing], each taken at the moment the last one ends. */
    private fun sequence(settings: SnoozeSettings, firstRing: Instant): List<SnoozeOffer> {
        val offers = ArrayList<SnoozeOffer>()
        var now = firstRing
        while (true) {
            val offer = math.offer(settings, offers.size, now, target)
            offers += offer
            now = offer.until ?: break
        }
        return offers
    }

    @Test
    fun `tapering snoozes from an early ring end by the wake time plus the overrun`() {
        // First ring at 6:12 for a 6:30 wake time with a 10-min overrun.
        val offers = sequence(SnoozeSettings(style = SnoozeStyle.TAPERING, overrun = Duration.ofMinutes(10)), at("06:12"))

        assertThat(offers.map { it.until }).containsExactly(
            at("06:21"), at("06:28"), at("06:30"), at("06:33"), at("06:35"), null,
        ).inOrder()
        assertThat(offers.map { it.lastOne }).containsExactly(false, false, false, false, true, false).inOrder()
        assertThat(offers.last().available).isFalse()
    }

    @Test
    fun `fixed snoozes stop after the count`() {
        val settings = SnoozeSettings(
            style = SnoozeStyle.FIXED,
            fixedLength = Duration.ofMinutes(9),
            fixedCount = 3,
            overrun = Duration.ofMinutes(20),
        )

        val offers = sequence(settings, target)

        // The third one is capped at 6:50 (wake time + 20 min).
        assertThat(offers.map { it.until }).containsExactly(at("06:39"), at("06:48"), at("06:50"), null).inOrder()
        assertThat(offers.map { it.lastOne }).containsExactly(false, false, true, false).inOrder()
    }

    @Test
    fun `no snooze is offered with snooze off`() {
        val offer = math.offer(SnoozeSettings(style = SnoozeStyle.NONE), used = 0, now = target, target = target)

        assertThat(offer).isEqualTo(SnoozeOffer(until = null, lastOne = false))
        assertThat(offer.available).isFalse()
    }

    @Test
    fun `with no overrun a ring at the wake time can't be snoozed`() {
        val settings = SnoozeSettings(style = SnoozeStyle.TAPERING, overrun = Duration.ZERO)

        assertThat(math.offer(settings, used = 0, now = target, target = target).available).isFalse()
        val early = sequence(settings, at("06:12"))
        assertThat(early.map { it.until }).containsExactly(at("06:21"), at("06:28"), at("06:30"), null).inOrder()
        assertThat(early.map { it.lastOne }).containsExactly(false, false, true, false).inOrder()
    }

    @Test
    fun `after an early ring a snooze never passes the wake time`() {
        val settings = SnoozeSettings(style = SnoozeStyle.FIXED, fixedLength = Duration.ofMinutes(9), fixedCount = 3)

        assertThat(math.offer(settings, used = 0, now = at("06:25"), target = target).until).isEqualTo(target)
    }

    @Test
    fun `a snooze shorter than a minute isn't offered`() {
        val settings = SnoozeSettings(style = SnoozeStyle.TAPERING)

        assertThat(math.offer(settings, used = 0, now = at("06:29:30"), target = target).available).isFalse()
        assertThat(math.offer(settings, used = 0, now = at("06:29"), target = target).until).isEqualTo(target)
        // The fifth tapering snooze, shortened to one minute, is the last one.
        assertThat(math.offer(settings, used = 4, now = at("06:29"), target = target))
            .isEqualTo(SnoozeOffer(until = at("06:30"), lastOne = true))
    }
}
