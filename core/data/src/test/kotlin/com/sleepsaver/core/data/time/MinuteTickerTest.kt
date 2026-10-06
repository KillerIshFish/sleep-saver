package com.sleepsaver.core.data.time

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.testing.instant
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MinuteTickerTest {

    @Test
    fun `emits now at once and then on every minute boundary`() = runTest {
        val start = instant("2026-10-06T07:00:30.250Z")
        val clock = { start.plusMillis(testScheduler.currentTime) }

        val ticks = minuteTicker(clock).take(4).toList()

        assertThat(ticks.map { it.toString() }).containsExactly(
            "2026-10-06T07:00:30.250Z",
            "2026-10-06T07:01:00Z",
            "2026-10-06T07:02:00Z",
            "2026-10-06T07:03:00Z",
        ).inOrder()
    }

    @Test
    fun `starting exactly on a boundary waits a full minute`() = runTest {
        val start = instant("2026-10-06T07:00:00Z")
        val clock = { start.plusMillis(testScheduler.currentTime) }

        val ticks = minuteTicker(clock).take(2).toList()

        assertThat(ticks.map { it.toString() }).containsExactly("2026-10-06T07:00:00Z", "2026-10-06T07:01:00Z").inOrder()
    }
}
